package dev.otake.rmssdh10n;

import android.content.Context;
import android.util.Log;

import org.json.JSONObject;

import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Date;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.TimeZone;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

import dev.otake.rmssdh10n.hrv.Analysis;
import dev.otake.rmssdh10n.hrv.Backfill;
import dev.otake.rmssdh10n.hrv.BodyState;
import dev.otake.rmssdh10n.hrv.Posture;
import dev.otake.rmssdh10n.hrv.Respiration;
import dev.otake.rmssdh10n.hrv.Rmssd;
import dev.otake.rmssdh10n.hrv.Steps;

/**
 * The native 1 Hz HRV reporting loop — the service-side counterpart of
 * app/src/monitor.js's _tick(). Runs on a {@link ScheduledExecutorService} (no
 * WebView/JS timer), so it keeps ticking with the screen off. Receives RR/HR/ACC
 * from {@link BleNative}; computes RMSSD/SDNN/HR, posture+sleep-position, steps,
 * respiration (RSA/Welch), the resting baseline and the autonomic/body state;
 * writes every frame to {@link HrvDb} (source of truth) and pushes live frames to
 * the WebView via the {@link Emitter}. Produces the same status/point keys the
 * WebView pipeline does, so the dashboard renders native data unchanged.
 */
public final class HrvEngine {
    private static final String TAG = "HrvEngine";
    private static final long RESP_WINDOW_MS = 120000;
    // Respiration is a ~1–2 min average, so a single failed 3 s recompute must not
    // blank the readout. Hold the last good estimate and decay its confidence to 0
    // over RESP_HOLD_MS of staleness; only then drop to null. (Schäfer & Kratky
    // 2008: RSA-derived rate is stable over >1 min windows.)
    private static final long RESP_HOLD_MS = 120000;
    // Only persist a chart point when a live RR arrived this recently. While
    // disconnected the RMSSD window keeps stale values (it evicts by beat-time, not
    // wall-time), so writing then would fill the gap with a flat fake line AND block
    // the offline backfill (its "skip seconds already present" would skip the gap).
    private static final long POINT_FRESH_MS = 5000;
    // GATT can report 'connected' while the streams never deliver (an abrupt OS kill can
    // orphan the H10's side, leaving a half-open link). If no RR arrives within this window
    // after a connection begins, the stream watchdog forces a reconnect to re-establish
    // streams + the recording recovery on a fresh GATT.
    private static final long STALE_STREAM_MS = 35000;

    public interface Emitter {
        void status(String json);
        void point(String json);
        void backfill(String json);
    }

    private static final SimpleDateFormat ISO;
    static {
        ISO = new SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss.SSS'+09:00'", Locale.US);
        ISO.setTimeZone(TimeZone.getTimeZone("GMT+09:00")); // JST, matching src/time.js
    }
    private static synchronized String localIso(long epochMs) { return ISO.format(new Date(epochMs)); }

    private final Context ctx;
    private final HrvDb db;
    private final Object gate = new Object();

    private final Rmssd win = new Rmssd(30000);
    private final Rmssd win5 = new Rmssd(300000);
    private final Posture posture;
    private final Steps steps = new Steps();
    private final Analysis.Baseline baseline = new Analysis.Baseline();
    private final Analysis.Classifier classifier = new Analysis.Classifier(45000);
    private final BodyState bodyState = new BodyState();

    private int user = 1;
    private volatile String deviceMac;          // mac of the H10 we record (for recording meta)
    private int baselineVersion = 0;            // bumped on re-baseline; stamped into restored ranges
    private final boolean withAcc;
    private volatile boolean connected = false;
    private volatile Integer deviceHr = null;
    private volatile long lastRrAt = 0;          // wall-clock of the last RR received (point-freshness gate)
    private volatile long connectedSince = 0;    // wall-clock the current BLE connection began (0 = disconnected)
    private long lastReconnectNudge = 0;         // stream-watchdog throttle (tick thread only)
    private volatile int lastBackfillRestored = 0; // points restored by the most recent gap backfill
    private double lastPeakMs = 0;
    private long beats = 0;
    private int lastStepCount = 0;

    // raw RR log for the Kubios/Elite-HRV export: {wallMs, rr, accepted}
    private final List<double[]> rrLog = new ArrayList<>();
    // respiration buffer (accepted NN beats) + smoothing history + throttle
    private final List<double[]> respBuffer = new ArrayList<>(); // {tMs, rr}
    private final List<double[]> respHistory = new ArrayList<>(); // {brpm, conf}
    private int respEvery = 3;
    private boolean respPreview = false;
    private long respLastGoodMs = 0;       // wall-clock of the most recent accepted estimate
    private long respLastLogMs = 0;        // throttle for dropout-reason logging

    // daily steps {day, total}
    private long stepDay = 0;
    private int stepTotal = 0;
    private long lastStepsSavedAt = 0;
    private Long lastPostureSavedAt = null;

    private PolarBle ble;
    private ScheduledExecutorService ticker;
    private volatile Emitter emitter;

    // [batt-native] telemetry
    private int tickCount = 0;
    private long lastTickAt = 0;
    private long battWindowStart = 0;
    private int battTicks = 0;
    private long battMaxGap = 0;
    private long battRr0 = 0;
    private long accSamples = 0;
    private long battAcc0 = 0;
    private int sinceFlush = 0;
    private int sincePrune = 0;

    public HrvEngine(Context ctx, HrvDb db, boolean withAcc) {
        this.ctx = ctx.getApplicationContext();
        this.db = db;
        this.withAcc = withAcc;
        Posture.Vec ref = vecFromKv("postureRef");
        Posture.Vec sup = vecFromKv("supineRef");
        int latSign = "-1".equals(db.kvGet("latSign")) ? -1 : 1;
        this.posture = new Posture(ref, sup, latSign, 25);
        // restore daily steps + a persisted baseline so a restart resumes calibrated
        loadStepsDay();
        loadBaselineKv();
        try { baselineVersion = Integer.parseInt(db.kvGet("baselineVersion")); } catch (Exception ignored) {}
    }

    public void setEmitter(Emitter e) { this.emitter = e; }
    public void setUser(int u) { this.user = u; }

    /** Seed posture refs + baseline from the WebView's persisted values (JSON). */
    public void seed(String json) {
        if (json == null) return;
        try {
            JSONObject o = new JSONObject(json);
            Posture.Vec ref = vecFromObj(o.optJSONObject("ref"));
            Posture.Vec sup = vecFromObj(o.optJSONObject("supine"));
            if (ref != null) { posture.ref = ref; db.kvPut("postureRef", vecJson(ref)); }
            if (sup != null) { posture.supineRef = sup; db.kvPut("supineRef", vecJson(sup)); }
            if (o.has("latSign")) { posture.latSign = o.optInt("latSign", 1) == -1 ? -1 : 1; db.kvPut("latSign", String.valueOf(posture.latSign)); }
            JSONObject b = o.optJSONObject("baseline");
            if (b != null && b.has("rmssd") && b.has("hr")) baseline.loadFrozen(b.optDouble("rmssd"), b.optDouble("hr"));
        } catch (Exception e) { Log.w(TAG, "seed", e); }
    }

    public boolean setPostureRef() {
        synchronized (gate) {
            Posture.Vec v = posture.setReference();
            if (v == null) return false;
            db.kvPut("postureRef", vecJson(v));
            lastPostureSavedAt = posture.calibratedAt;
            return true;
        }
    }

    public boolean setSupineRef() {
        synchronized (gate) {
            Posture.Vec v = posture.setSupineReference();
            if (v == null) return false;
            db.kvPut("supineRef", vecJson(v));
            return true;
        }
    }

    public boolean toggleSleepLR() {
        synchronized (gate) {
            posture.latSign = posture.latSign == 1 ? -1 : 1;
            db.kvPut("latSign", String.valueOf(posture.latSign));
            return posture.latSign == -1;
        }
    }

    /** Re-calibrate: drop the frozen baseline so it re-measures over the next ~60s. */
    public void resetBaseline() {
        synchronized (gate) { baseline.reset(); db.kvPut("baseline", ""); bumpBaselineVersion(); }
    }

    /** Manual baseline override (and persist so a restart keeps it). */
    public boolean setBaseline(double r, double h) {
        synchronized (gate) {
            if (!(r > 0) || !(h > 0)) return false;
            baseline.loadFrozen(r, h);
            try { db.kvPut("baseline", new JSONObject().put("rmssd", r).put("hr", h).toString()); }
            catch (Exception ignored) {}
            bumpBaselineVersion();
            return true;
        }
    }

    /** Bump + persist the baseline version so a restored range records which baseline
     *  produced its tone (the WebView can recompute past tone after a re-baseline). */
    private void bumpBaselineVersion() {
        baselineVersion++;
        db.kvPut("baselineVersion", String.valueOf(baselineVersion));
    }

    /** Recent raw RR beats as a JSON array of {wall (JST ISO), rr, accepted}, for
     *  the dashboard's Kubios/Elite-HRV CSV export (mirrors Monitor.getRrLog). */
    public String rrLogJson() {
        synchronized (gate) {
            StringBuilder sb = new StringBuilder("[");
            for (int i = 0; i < rrLog.size(); i++) {
                double[] e = rrLog.get(i);
                if (i > 0) sb.append(',');
                sb.append("{\"wall\":\"").append(localIso((long) e[0]))
                  .append("\",\"rr\":").append(round1(e[1]))
                  .append(",\"accepted\":").append((int) e[2]).append('}');
            }
            return sb.append(']').toString();
        }
    }

    public void start(String mac) {
        battWindowStart = System.currentTimeMillis();
        deviceMac = mac;
        ble = new PolarBle(ctx, mac, withAcc, new PolarBle.Sink() {
            @Override public void onHr(int hr) { deviceHr = hr; }
            @Override public void onRr(double rrMs) {
                lastRrAt = System.currentTimeMillis();
                synchronized (gate) {
                    lastPeakMs += rrMs;
                    beats++;
                    boolean accepted = win.add(lastPeakMs, rrMs);
                    win5.add(lastPeakMs, rrMs);
                    rrLog.add(new double[]{ System.currentTimeMillis(), rrMs, accepted ? 1 : 0 });
                    if (rrLog.size() > 2500) rrLog.remove(0);
                    if (accepted) {
                        respBuffer.add(new double[]{ lastPeakMs, rrMs });
                        double cutoff = lastPeakMs - RESP_WINDOW_MS;
                        while (!respBuffer.isEmpty() && respBuffer.get(0)[0] < cutoff) respBuffer.remove(0);
                    }
                }
            }
            @Override public void onAcc(int x, int y, int z) {
                synchronized (gate) { accSamples++; posture.add(x, y, z); steps.add(x, y, z); }
            }
            @Override public void onConnected(boolean c) {
                connected = c;
                connectedSince = c ? System.currentTimeMillis() : 0;
                if (!c) deviceHr = null;
            }
            @Override public void log(String m) { Log.i(TAG, "[ble] " + m); }
        });
        ble.setRecordingStore(recStore);
        ble.start();
        ticker = Executors.newSingleThreadScheduledExecutor();
        ticker.scheduleAtFixedRate(this::tickSafe, 1000, 1000, TimeUnit.MILLISECONDS);
        Log.i(TAG, "engine started (mac=" + mac + ", acc=" + withAcc + ")");
    }

    public void stop() {
        if (ticker != null) { ticker.shutdownNow(); ticker = null; }
        if (ble != null) { ble.stop(); ble = null; }
        saveStepsDay();
        db.flush();
        Log.i(TAG, "engine stopped");
    }

    /** Explicit (user) stop: mark the H10 recording discarded so the next launch does
     *  NOT auto-recover it. Distinct from an OS kill, which leaves it 'active'. */
    public void markUserStopped() {
        try { db.recordingMarkDiscardedByUser(); } catch (Throwable t) { Log.w(TAG, "markUserStopped", t); }
    }

    /** App returned to foreground — let the BLE driver restart its scan if needed. */
    public void foregroundEntered() {
        PolarBle b = ble;
        if (b != null) b.foregroundEntered();
    }

    // --- recording store: DB-backed lifecycle so the H10 recording survives an app/OS
    //     restart (the start-anchor + state live in HrvDb, not just process memory) -----
    private static final String REC_OWNER = "rmssd-h10n";
    private static final int REC_SCHEMA = 2;

    private final PolarBle.RecordingStore recStore = new PolarBle.RecordingStore() {
        @Override public PolarBle.RecordingStore.OpenRec getOpenRecording() {
            HrvDb.Rec r = db.recordingGetOpen();
            return (r == null) ? null
                    : new PolarBle.RecordingStore.OpenRec(r.exId, r.anchorStartMs, r.state);
        }
        @Override public void recStarting(String exId, long startRequestMs) {
            db.recordingStarting(exId, deviceMac, user, REC_OWNER, REC_SCHEMA, startRequestMs);
        }
        @Override public void recActive(String exId, long startAckMs) { db.recordingActive(exId, startAckMs); }
        @Override public void recFetching(String exId, long rrCount, long durationMs, boolean truncated) {
            db.recordingSetFetched(exId, rrCount, durationMs, truncated ? 1 : 0);
        }
        @Override public boolean recPersistGap(double[] rrMs, long anchorStartMs, String exId, boolean truncated) {
            boolean ok = replayAndPersistGap(rrMs, anchorStartMs, exId, truncated);
            if (ok) db.recordingSetState(exId, "persisted");
            return ok;
        }
        @Override public void recRemoved(String exId) { db.recordingMarkRemoved(exId); }
    };

    /** Replay RR fetched from the H10's gap recording into 1 Hz points at their
     *  start-anchored timestamps and persist them durably + atomically with a ledger row
     *  (so a service-only restore the WebView never saw is still merged on next load).
     *  INSERT OR IGNORE so a live boundary second is never overwritten by a null-posture
     *  backfill point. Returns true once durable so {@link PolarBle} may remove the
     *  device-side exercise. Idempotent on a re-import (same anchor → same seconds). Runs
     *  on PolarBle's worker thread. */
    private boolean replayAndPersistGap(double[] rrMs, long anchorStartMs, String exId, boolean truncated) {
        try {
            long now = System.currentTimeMillis();
            // Clock sanity: a future / absurd anchor (e.g. an NTP jump) would misplace the
            // whole gap. Treat as nothing-to-do (return true so the device exercise is still
            // removed) rather than writing wrong-timestamped points.
            if (anchorStartMs <= 0 || anchorStartMs > now + 60_000L) {
                Log.w(TAG, "backfill: implausible anchor " + anchorStartMs + " (now=" + now + ") — skipping");
                lastBackfillRestored = 0;
                return true;
            }
            double baseR = 0, baseH = 0;
            int blv;
            synchronized (gate) {
                Analysis.Base b = baseline.get();
                if (b != null) { baseR = b.rmssd; baseH = b.hr; }
                blv = baselineVersion;
            }
            List<Backfill.Pt> pts = Backfill.replay(rrMs, anchorStartMs, baseR, baseH);
            if (pts.isEmpty()) { lastBackfillRestored = 0; return true; }
            long from = pts.get(0).tMs, to = pts.get(pts.size() - 1).tMs;
            Set<Long> existing = db.pointTimesIn(from, to);
            List<Object[]> rows = new ArrayList<>();
            for (Backfill.Pt pt : pts) {
                if (pt.tMs > now + 60_000L) continue;  // clock-skew guard: never write future points
                if (existing.contains(pt.tMs)) continue;
                String json = buildPointJson(localIso(pt.tMs), pt.rmssd,
                        pt.hr != null ? (double) pt.hr : null, pt.resp, pt.tone,
                        null, null, null, null, 0, null, null);
                rows.add(new Object[]{ pt.tMs, json });
            }
            int inserted = rows.size();
            // One transaction: INSERT OR IGNORE points + ledger row — crash-atomic and
            // idempotent so a removeExercise failure can be retried without double-counting.
            db.backfillCommit(rows, from, to, inserted, anchorStartMs, exId, truncated ? 1 : 0, blv);
            lastBackfillRestored = inserted;
            Log.i(TAG, "[backfill] restored " + inserted + " pts over " + ((to - from) / 1000) + "s"
                    + (truncated ? " (truncated)" : ""));
            Emitter e = emitter;
            if (e != null) {
                try {
                    e.backfill(new JSONObject().put("restored", inserted)
                            .put("fromMs", from).put("toMs", to).put("truncated", truncated).toString());
                } catch (Exception ignored) {}
            }
            return true;
        } catch (Throwable t) {
            Log.e(TAG, "backfill failed", t);
            return false;
        }
    }

    private void tickSafe() { try { tick(); } catch (Throwable t) { Log.e(TAG, "tick error", t); } }

    private void tick() {
        long now = System.currentTimeMillis();
        if (lastTickAt != 0) { long gap = now - lastTickAt; if (gap > battMaxGap) battMaxGap = gap; }
        lastTickAt = now;
        tickCount++;
        battTicks++;

        // Stream watchdog: GATT 'connected' but NO RR since this connection began (≥35 s) is a
        // stalled link (a stream subscription errored). Re-subscribe the streams (throttled
        // 60 s). Gentle — no disconnect; a true orphan (after a force-stop) needs a BT toggle.
        long cs = connectedSince;
        if (connected && cs > 0 && now - cs > STALE_STREAM_MS && lastRrAt < cs
                && now - lastReconnectNudge > 60000) {
            lastReconnectNudge = now;
            Log.w(TAG, "[ble] stream watchdog: no RR " + ((now - cs) / 1000) + "s after connect — re-subscribing");
            PolarBle b = ble;
            if (b != null) b.nudgeStreams();
        }

        Rmssd.Result r, r5;
        Posture.Result p;
        Double respOut = null, respConf = null;
        boolean previewOut;
        int stepNow;
        Analysis.State state;
        BodyState.Result body;
        Analysis.Base base;
        synchronized (gate) {
            r = win.compute(lastPeakMs);
            r5 = win5.compute(lastPeakMs);
            p = posture.compute(now);
            stepNow = steps.steps;

            Double rmssdSmInner = r.rmssdEma != null ? round1(r.rmssdEma) : null;
            Integer effHrInner = deviceHr != null ? deviceHr : (r.hr != null ? (int) Math.round(r.hr) : null);
            Double hrInner = effHrInner != null ? (double) effHrInner : null;

            if (connected) baseline.add(rmssdSmInner, hrInner);
            base = baseline.get();
            state = classifier.update(rmssdSmInner, hrInner, base, now);

            // Respiration: recompute the heavy Welch PSD only every few ticks.
            if (tickCount % respEvery == 0) {
                int m = respBuffer.size();
                double[] xs = new double[m], ys = new double[m];
                for (int i = 0; i < m; i++) { xs[i] = respBuffer.get(i)[0]; ys[i] = respBuffer.get(i)[1]; }
                Respiration.Result rr = Respiration.estimate(xs, ys);
                if (rr.breathsPerMin != null && (rr.valid || rr.preview)) {
                    respHistory.add(new double[]{ rr.breathsPerMin, rr.confidence });
                    if (respHistory.size() > 5) respHistory.remove(0);
                    respPreview = rr.preview;
                    respLastGoodMs = now;
                } else if (now - respLastLogMs > 30000) {
                    // Throttled dropout diagnostics (P4): see why estimates are missing.
                    Log.i(TAG, "[resp] miss reason=" + rr.reason + " snr=" + rr.snr
                            + " f=" + rr.freqHz + " buf=" + m);
                    respLastLogMs = now;
                }
                // NOTE: a failed recompute does NOT clear history — see hold below.
            }
            // Last-good hold: keep the smoothed value alive and fade its confidence
            // with staleness; only drop to null once the estimate is too old.
            long staleAge = respLastGoodMs > 0 ? (now - respLastGoodMs) : Long.MAX_VALUE;
            if (staleAge > RESP_HOLD_MS) { respHistory.clear(); respPreview = false; }
            previewOut = respPreview;
            if (!respHistory.isEmpty()) {
                List<Double> brs = new ArrayList<>(), cfs = new ArrayList<>();
                for (double[] e : respHistory) { brs.add(e[0]); cfs.add(e[1]); }
                double decay = Math.max(0, 1 - (double) staleAge / RESP_HOLD_MS);
                respOut = round1(medianOf(brs));
                respConf = round2(medianOf(cfs) * decay);
            } else { previewOut = false; }

            double lnDelta0 = (base != null && rmssdSmInner != null && base.rmssd > 0)
                ? Math.log(rmssdSmInner / base.rmssd) : Double.NaN;
            Double lnDelta = Double.isNaN(lnDelta0) ? null : lnDelta0;
            body = bodyState.update(steps.walking(), p.activity, p.leanDeg,
                hrInner, base != null ? base.hr : null, lnDelta, respOut, respConf, now);
        }

        // Persist an auto-calibrated posture reference (like monitor.js).
        if (posture.calibratedAt != null && !posture.calibratedAt.equals(lastPostureSavedAt)) {
            lastPostureSavedAt = posture.calibratedAt;
            if (posture.ref != null) db.kvPut("postureRef", vecJson(posture.ref));
        }

        Integer effHr = deviceHr != null ? deviceHr : (r.hr != null ? (int) Math.round(r.hr) : null);
        Double rmssd = r.rmssd != null ? round1(r.rmssd) : null;
        Double rmssdSm = r.rmssdEma != null ? round1(r.rmssdEma) : null;
        Double sdnn = r.sdnn != null ? round1(r.sdnn) : null;
        Double rmssd5 = r5.rmssd != null ? round1(r5.rmssd) : null;
        Double hrVal = effHr != null ? (double) effHr : null;
        String wall = localIso(now);

        // daily steps rollover + persist
        int stepDelta = Math.max(0, stepNow - lastStepCount);
        lastStepCount = stepNow;
        long today = jstMidnight(now);
        if (stepDay != today) { stepDay = today; stepTotal = 0; }
        if (stepDelta > 0) {
            stepTotal += stepDelta;
            if (now - lastStepsSavedAt > 5000) { lastStepsSavedAt = now; saveStepsDay(); }
        }

        try {
            JSONObject status = new JSONObject();
            status.put("connected", connected);
            status.put("user", user);
            status.put("mode", "hr-rr");
            status.put("hr", jn(hrVal));
            status.put("rmssd", jn(rmssd));
            status.put("rmssd5", jn(rmssd5));
            status.put("rmssdSmoothed", jn(rmssdSm));
            status.put("sdnn", jn(sdnn));
            status.put("rrCount", r.count);
            status.put("beatsTotal", beats);
            status.put("rejected", r.corrected);
            status.put("corrected", r.corrected);
            status.put("baseline", base != null ? new JSONObject().put("rmssd", round1(base.rmssd)).put("hr", round1(base.hr)) : JSONObject.NULL);
            status.put("calibration", round2(baseline.progress()));
            status.put("state", stateJson(state));
            status.put("respiration", jn(respOut));
            status.put("respirationConfidence", jn(respConf));
            status.put("respirationPreview", previewOut);
            status.put("posture", postureJson(p));
            status.put("steps", new JSONObject().put("today", stepTotal).put("cadence", steps.cadence()).put("walking", steps.walking()));
            status.put("body", body.state);
            status.put("engine", "native");
            status.put("recording", ble != null && ble.isRecording());
            status.put("externalRecording", ble != null && ble.isExternalBlocking());
            status.put("restored", lastBackfillRestored);
            status.put("updatedAt", wall);

            String statusStr = status.toString();
            db.setStatus(statusStr, now);
            Emitter e = emitter;
            if (e != null) e.status(statusStr);

            // Persist a chart point only with a FRESH live reading (connected + an RR
            // arrived within POINT_FRESH_MS). t is floored to the whole second so a
            // backfilled point for the same second shares the primary key (dedup).
            boolean fresh = connected && lastRrAt > 0 && (now - lastRrAt) < POINT_FRESH_MS;
            if (fresh && (hrVal != null || rmssd != null)) {
                long tSec = (now / 1000L) * 1000L;
                Integer leanOut = (p.calibrated && p.receiving && p.leanDeg != null) ? p.leanDeg : null;
                String pointStr = buildPointJson(localIso(tSec), rmssd, hrVal, respOut,
                        state != null ? state.tone : null,
                        leanOut, p.state, p.leanDir, p.activity, stepDelta, body.state, p.sleepPos);
                db.addPoint(tSec, pointStr);
                if (e != null) e.point(pointStr);
            }
        } catch (Exception ex) { Log.e(TAG, "json", ex); }

        if (++sinceFlush >= 5) { sinceFlush = 0; db.flush(); }
        if (++sincePrune >= 3600) { sincePrune = 0; db.prune(now - 14L * 24 * 3600 * 1000); }

        if (now - battWindowStart >= 60000) {
            long rr = beats - battRr0;
            long acc = accSamples - battAcc0;
            int secs = (int) Math.max(1, (now - battWindowStart) / 1000);
            Log.i(TAG, "[batt-native] " + secs + "s ticks=" + battTicks + " maxGap=" + battMaxGap
                + "ms rr=" + rr + " accSamples=" + acc + " (" + (acc / secs) + "/s) connected=" + connected);
            battWindowStart = now; battTicks = 0; battMaxGap = 0; battRr0 = beats; battAcc0 = accSamples;
        }
    }

    // --- helpers -----------------------------------------------------------
    /** Build the point JSON shared by the live tick and the offline backfill, so the
     *  key set never drifts between the two. Backfilled points pass null posture/steps. */
    static String buildPointJson(String wall, Double rmssd, Double hr, Double resp, String tone,
            Integer lean, String posture, String leanDir, Integer activity, int step,
            String body, String sleepPos) throws Exception {
        JSONObject point = new JSONObject();
        point.put("t", wall);
        point.put("rmssd", jn(rmssd));
        point.put("hr", jn(hr));
        point.put("resp", jn(resp));
        point.put("tone", tone != null ? tone : JSONObject.NULL);
        point.put("lean", lean != null ? lean : JSONObject.NULL);
        point.put("posture", posture != null ? posture : JSONObject.NULL);
        point.put("leanDir", leanDir != null ? leanDir : JSONObject.NULL);
        point.put("activity", activity != null ? activity : JSONObject.NULL);
        point.put("step", step);
        point.put("body", body != null ? body : JSONObject.NULL);
        point.put("sleepPos", sleepPos != null ? sleepPos : JSONObject.NULL);
        return point.toString();
    }

    private static JSONObject stateJson(Analysis.State s) throws Exception {
        if (s == null) return null;
        JSONObject o = new JSONObject();
        o.put("label", s.label);
        o.put("tone", s.tone);
        o.put("detail", s.detail);
        o.put("arousal", s.arousal != null ? s.arousal : JSONObject.NULL);
        o.put("recovery", s.recovery != null ? s.recovery : JSONObject.NULL);
        o.put("load", s.load != null ? s.load : JSONObject.NULL);
        return o;
    }

    private static JSONObject postureJson(Posture.Result p) throws Exception {
        JSONObject o = new JSONObject();
        o.put("receiving", p.receiving);
        o.put("calibrated", p.calibrated);
        o.put("state", p.state);
        o.put("leanDeg", p.leanDeg != null ? p.leanDeg : JSONObject.NULL);
        o.put("activity", p.activity != null ? p.activity : JSONObject.NULL);
        o.put("moving", p.moving);
        o.put("sleepPos", p.sleepPos != null ? p.sleepPos : JSONObject.NULL);
        o.put("leanDir", p.leanDir != null ? p.leanDir : JSONObject.NULL);
        return o;
    }

    private Posture.Vec vecFromKv(String key) { return vecFromJson(db.kvGet(key)); }
    private static Posture.Vec vecFromJson(String json) {
        if (json == null) return null;
        try { return vecFromObj(new JSONObject(json)); } catch (Exception e) { return null; }
    }
    private static Posture.Vec vecFromObj(JSONObject o) {
        if (o == null || !o.has("x")) return null;
        return new Posture.Vec(o.optDouble("x"), o.optDouble("y"), o.optDouble("z"));
    }
    private static String vecJson(Posture.Vec v) {
        try { return new JSONObject().put("x", v.x).put("y", v.y).put("z", v.z).toString(); }
        catch (Exception e) { return "{}"; }
    }

    private void loadStepsDay() {
        try {
            String s = db.kvGet("stepsDay");
            if (s != null) { JSONObject o = new JSONObject(s); stepDay = o.optLong("day"); stepTotal = o.optInt("total"); }
        } catch (Exception ignored) {}
    }
    private void saveStepsDay() {
        try { db.kvPut("stepsDay", new JSONObject().put("day", stepDay).put("total", stepTotal).toString()); }
        catch (Exception ignored) {}
    }
    private void loadBaselineKv() {
        try {
            String s = db.kvGet("baseline");
            if (s != null) { JSONObject o = new JSONObject(s); if (o.has("rmssd") && o.has("hr")) baseline.loadFrozen(o.optDouble("rmssd"), o.optDouble("hr")); }
        } catch (Exception ignored) {}
    }

    private static long jstMidnight(long now) {
        long jst = now + 9L * 3600 * 1000;
        long dayIdx = Math.floorDiv(jst, 86400000L);
        return dayIdx * 86400000L - 9L * 3600 * 1000;
    }

    private static double medianOf(List<Double> a) {
        if (a.isEmpty()) return 0;
        List<Double> s = new ArrayList<>(a);
        Collections.sort(s);
        int m = s.size() >> 1;
        return (s.size() % 2 == 1) ? s.get(m) : (s.get(m - 1) + s.get(m)) / 2.0;
    }
    private static Object jn(Double v) { return v == null ? JSONObject.NULL : v; }
    private static double round1(double v) { return Math.round(v * 10.0) / 10.0; }
    private static double round2(double v) { return Math.round(v * 100.0) / 100.0; }
}
