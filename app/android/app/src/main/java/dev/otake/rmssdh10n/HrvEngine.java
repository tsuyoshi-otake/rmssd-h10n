package dev.otake.rmssdh10n;

import android.content.Context;
import android.util.Log;

import org.json.JSONObject;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

import dev.otake.rmssdh10n.hrv.Analysis;
import dev.otake.rmssdh10n.hrv.BodyState;
import dev.otake.rmssdh10n.hrv.Posture;
import dev.otake.rmssdh10n.hrv.Rmssd;
import dev.otake.rmssdh10n.hrv.Steps;

/**
 * The native 1 Hz HRV reporting loop — the service-side counterpart of
 * app/src/monitor.js's _tick(). Runs on a {@link ScheduledExecutorService} (no
 * WebView/JS timer), so it keeps ticking with the screen off. Receives RR/HR/ACC
 * from {@link PolarBle}; computes RMSSD/SDNN/HR, posture+sleep-position, steps,
 * respiration (RSA/Welch), the resting baseline and the autonomic/body state;
 * writes every frame to {@link HrvDb} (source of truth) and pushes live frames to
 * the WebView via the {@link Emitter}. Produces the same status/point keys the
 * WebView pipeline does, so the dashboard renders native data unchanged.
 */
public final class HrvEngine {
    private static final String TAG = "HrvEngine";
    // Only persist a chart point when a live RR arrived this recently. While
    // disconnected the RMSSD window keeps stale values (it evicts by beat-time, not
    // wall-time), so writing then would fill the gap with a flat fake line AND block
    // the offline backfill (its "skip seconds already present" would skip the gap).
    private static final long POINT_FRESH_MS = 5000;
    // GATT can report 'connected' while the streams never deliver (an abrupt OS kill can
    // orphan the H10's side, leaving a half-open link). The stream watchdog first re-subscribes
    // (gentle); if RR still never arrives it escalates to a forced clean reconnect — a
    // re-subscribe cannot heal a half-open link, only a fresh GATT can.
    private static final long STALE_STREAM_MS = 35000;        // no RR this long after connect ⇒ first (gentle) nudge
    private static final long STALE_ACTION_MS = 45000;        // min spacing between watchdog actions
    private static final long STALE_FORCE_MS  = 70000;        // still no RR this long ⇒ escalate to a forced reconnect
    private static final int  MAX_STALE_FORCE_RECONNECTS = 3; // bound forced reconnects per stale episode (churn guard)
    private static final long NOTIF_STALE_MS  = 20000;        // connected but no fresh RR this long ⇒ notify "re-attach H10"

    public interface Emitter {
        void status(String json);
        void point(String json);
        void backfill(String json);
    }

    /** Relax-mode voice readout sink (Android TextToSpeech in the service). */
    public interface Speaker { void speak(String text); }

    /** Surfaces a stalled link to the foreground notification (connected but no RR —
     *  e.g. a post-force-stop orphan the watchdog can't clear from the phone side). */
    public interface LinkStateSink { void onLinkStale(boolean stale); }

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
    private volatile int deviceBattery = -1;     // H10 battery % (0-100); -1 = unknown. Last-known kept across a drop.
    private volatile long lastRrAt = 0;          // wall-clock of the last accepted RR (data-freshness gate)
    private volatile long lastRrReceivedAt = 0;  // last physiologically plausible RR (link watchdog)
    private volatile long connectedSince = 0;    // wall-clock the current BLE connection began (0 = disconnected)
    private long lastReconnectNudge = 0;         // stream-watchdog throttle (tick thread only)
    private int  staleForceReconnects = 0;       // forced reconnects this stale episode (tick thread only)
    private volatile int lastBackfillRestored = 0; // points restored by the most recent gap backfill
    private volatile Speaker speaker;            // relax-mode TTS sink (null = silent)
    private volatile int relaxIntervalSec = 0;   // 0 = off; else read out every N s
    private long lastSpokenAt = 0;               // tick-thread throttle for the readout
    private final BreathingAlert breathingAlert = new BreathingAlert();
    private volatile LinkStateSink linkStateSink; // foreground-notification link hint (null = none)
    private boolean linkStaleShown = false;       // last reported link-stale state (tick thread only)
    private double lastPeakMs = 0;
    private long beats = 0;
    private int lastStepCount = 0;

    // raw RR log for the Kubios/Elite-HRV export: {wallMs, rr, accepted}
    private final List<double[]> rrLog = new ArrayList<>();
    // RSA respiration estimate: accepted-NN buffer + Welch recompute + last-good hold.
    private final RespirationTracker respiration = new RespirationTracker(3);

    // daily steps {day, total}
    private long stepDay = 0;
    private int stepTotal = 0;
    private boolean stepsEnabled = true; // false when ACC is duty-cycled — bursts can't count steps, so omit them (no misleading undercount)
    private volatile boolean powerSave = false; // 省電力モード: ACC間欠＋歩数オフ（dashboard toggle, default OFF=ACC連続）
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
        this.recStore = buildRecStore();
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
    public void setSpeaker(Speaker s) { this.speaker = s; }
    public void setLinkStateSink(LinkStateSink s) { this.linkStateSink = s; }

    /** Relax-mode readout interval in seconds (0 = off). Speaks the first reading promptly. */
    public void setRelaxIntervalSec(int sec) {
        relaxIntervalSec = Math.max(0, sec);
        lastSpokenAt = 0; // make the next fresh tick read out immediately
        Log.i(TAG, "[relax] interval=" + relaxIntervalSec + "s");
        Speaker sp = speaker;
        if (sp != null) sp.speak(relaxIntervalSec > 0 ? "リラックス読み上げを開始します。" : "読み上げを停止します。");
    }

    /** Voice warning for sustained low RMSSD + fast/shallow breathing. */
    public void setBreathingAlertVoice(boolean enabled) {
        breathingAlert.setEnabled(enabled);
        Log.i(TAG, "[breathing-alert] enabled=" + enabled);
    }

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
                sb.append("{\"wall\":\"").append(HrvTime.localIso((long) e[0]))
                  .append("\",\"rr\":").append(round1(e[1]))
                  .append(",\"accepted\":").append((int) e[2]).append('}');
            }
            return sb.append(']').toString();
        }
    }

    public void start(String mac) {
        battWindowStart = System.currentTimeMillis();
        deviceMac = mac;
        ble = new PolarBle(ctx, mac, withAcc, createBleSink());
        ble.setAccDutyCycle(powerSave); // apply the current power-save mode to the fresh link
        stepsEnabled = !powerSave;       // power-save ⇒ omit steps (posture still refreshes each burst)
        ble.setRecordingStore(recStore);
        ble.start();
        ticker = Executors.newSingleThreadScheduledExecutor();
        ticker.scheduleAtFixedRate(this::tickSafe, 1000, 1000, TimeUnit.MILLISECONDS);
        Log.i(TAG, "engine started (mac=" + mac + ", acc=" + withAcc + ")");
    }

    /** BLE-layer callback that funnels live HR/RR/ACC + connection state into the engine's
     *  windows, posture/step accumulators, RR log and the point-freshness gate (lastRrAt). */
    private PolarBle.Sink createBleSink() {
        return new PolarBle.Sink() {
            @Override public void onHr(int hr) { deviceHr = hr; }
            @Override public void onRr(double rrMs) {
                long receivedAt = System.currentTimeMillis();
                if (!Double.isFinite(rrMs) || rrMs < 300 || rrMs > 2000) {
                    synchronized (gate) {
                        rrLog.add(new double[]{ receivedAt, rrMs, 0 });
                        if (rrLog.size() > 2500) rrLog.remove(0);
                    }
                    return; // invalid input must not advance the beat timeline or freshness
                }
                lastRrReceivedAt = receivedAt;
                synchronized (gate) {
                    lastPeakMs += rrMs;
                    beats++;
                    boolean accepted = win.add(lastPeakMs, rrMs);
                    win5.add(lastPeakMs, rrMs);
                    rrLog.add(new double[]{ receivedAt, rrMs, accepted ? 1 : 0 });
                    if (rrLog.size() > 2500) rrLog.remove(0);
                    if (accepted) {
                        lastRrAt = receivedAt;
                        respiration.addAcceptedBeat(lastPeakMs, rrMs);
                    }
                }
            }
            @Override public void onAcc(int x, int y, int z) {
                synchronized (gate) { accSamples++; posture.add(x, y, z); if (stepsEnabled) steps.add(x, y, z); }
            }
            @Override public void onConnected(boolean c) {
                connected = c;
                connectedSince = c ? System.currentTimeMillis() : 0;
                if (!c) deviceHr = null;          // battery is kept — it doesn't change while off-wrist
            }
            @Override public void onBattery(int level) { deviceBattery = level; }
            @Override public void log(String m) { Log.i(TAG, "[ble] " + m); }
        };
    }

    /** Dashboard 省電力 toggle: ON = ACC duty-cycle (low power, posture ~30s, steps omitted),
     *  OFF = continuous ACC (full posture + steps). Applies live and to the next connect. */
    public void setPowerSave(boolean on) {
        powerSave = on;
        stepsEnabled = !on;
        PolarBle b = ble;
        if (b != null) b.setAccDutyCycle(on);
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
        try { db.recordingMarkDiscardedByUser(user); } catch (Throwable t) { Log.w(TAG, "markUserStopped", t); }
    }

    /** App returned to foreground — let the BLE driver restart its scan if needed. */
    public void foregroundEntered() {
        PolarBle b = ble;
        if (b != null) b.foregroundEntered();
    }

    // --- recording store: DB-backed lifecycle so the H10 recording survives an app/OS
    //     restart, replaying a fetched gap into points. The lifecycle + replay logic lives in
    //     RecordingBackfillStore; this Host exposes the live mac/user/baseline it reads and
    //     routes the restored count back to the status frame + WebView backfill event. ------
    private final RecordingBackfillStore recStore;  // built in the ctor, once db is assigned

    /** Host wiring for {@link #recStore}: live mac/user/baseline read under the engine lock,
     *  and the restored count routed to the status frame + WebView backfill event. */
    private RecordingBackfillStore buildRecStore() {
        return new RecordingBackfillStore(db, new RecordingBackfillStore.Host() {
            @Override public String deviceMac() { return deviceMac; }
            @Override public int user() { return user; }
            @Override public RecordingBackfillStore.BaselineRef baseline() {
                synchronized (gate) {
                    Analysis.Base b = baseline.get();
                    return new RecordingBackfillStore.BaselineRef(
                            b != null ? b.rmssd : 0, b != null ? b.hr : 0, baselineVersion);
                }
            }
            @Override public void setRestored(int count) { lastBackfillRestored = count; }
            @Override public void onBackfilled(int restored, long fromMs, long toMs, boolean truncated) {
                lastBackfillRestored = restored;
                Emitter e = emitter;
                if (e != null) {
                    try {
                        e.backfill(new JSONObject().put("restored", restored)
                                .put("fromMs", fromMs).put("toMs", toMs).put("truncated", truncated).toString());
                    } catch (Exception ignored) {}
                }
            }
        });
    }

    private void tickSafe() { try { tick(); } catch (Throwable t) { Log.e(TAG, "tick error", t); } }

    private void tick() {
        long now = System.currentTimeMillis();
        if (lastTickAt != 0) { long gap = now - lastTickAt; if (gap > battMaxGap) battMaxGap = gap; }
        lastTickAt = now;
        tickCount++;
        battTicks++;

        // Stream watchdog: GATT 'connected' but NO RR since this connection began is a stalled
        // link. Re-subscribe first (gentle); if RR still never arrives, the link is a half-open
        // orphan a re-subscribe can't fix → force a clean reconnect, bounded per episode so the
        // churn can't wedge the scanner. The force budget resets as soon as RR flows again.
        long cs = connectedSince;
        if (lastRrReceivedAt > 0 && now - lastRrReceivedAt < POINT_FRESH_MS) staleForceReconnects = 0;
        if (connected && cs > 0 && now - cs > STALE_STREAM_MS && lastRrReceivedAt < cs
                && now - lastReconnectNudge > STALE_ACTION_MS) {
            PolarBle b = ble;
            if (b != null) {
                lastReconnectNudge = now;
                if (now - cs > STALE_FORCE_MS && staleForceReconnects < MAX_STALE_FORCE_RECONNECTS) {
                    staleForceReconnects++;
                    Log.w(TAG, "[ble] stream watchdog: no RR " + ((now - cs) / 1000) + "s — forcing clean reconnect "
                            + staleForceReconnects + "/" + MAX_STALE_FORCE_RECONNECTS);
                    b.forceReconnect();
                } else {
                    Log.w(TAG, "[ble] stream watchdog: no RR " + ((now - cs) / 1000) + "s after connect — re-subscribing");
                    b.nudgeStreams();
                }
            }
        }

        // Foreground-notification link hint: connected but no fresh RR for a while (a
        // post-force-stop orphan the watchdog can't always clear from the phone side, or a
        // mid-session stall). Surface "re-attach H10"; only RR actually resuming clears it, so
        // the brief blip of a forced reconnect doesn't make it flap.
        boolean delivering = lastRrReceivedAt > 0 && now - lastRrReceivedAt < POINT_FRESH_MS;
        boolean quiet = !delivering && (
                (cs > 0 && lastRrReceivedAt < cs && now - cs > NOTIF_STALE_MS)     // connected, never delivered (orphan)
             || (lastRrReceivedAt > 0 && now - lastRrReceivedAt > NOTIF_STALE_MS)); // delivered before, now silent
        if (delivering && linkStaleShown) { linkStaleShown = false; notifyLinkStale(false); }
        else if (quiet && !linkStaleShown) { linkStaleShown = true; notifyLinkStale(true); }

        Rmssd.Result r, r5;
        Posture.Result p;
        Double respOut = null, respConf = null;
        boolean previewOut;
        int stepNow;
        Analysis.State state;
        BodyState.Result body;
        Analysis.Base base;
        boolean fresh = connected && lastRrAt > 0 && (now - lastRrAt) < POINT_FRESH_MS;
        synchronized (gate) {
            r = win.compute(lastPeakMs);
            r5 = win5.compute(lastPeakMs);
            p = posture.compute(now);
            stepNow = steps.steps;

            Double rmssdSmInner = r.rmssdEma != null ? round1(r.rmssdEma) : null;
            Integer effHrInner = deviceHr != null ? deviceHr : (r.hr != null ? (int) Math.round(r.hr) : null);
            Double hrInner = effHrInner != null ? (double) effHrInner : null;

            if (fresh) baseline.add(rmssdSmInner, hrInner);
            base = baseline.get();
            state = fresh ? classifier.update(rmssdSmInner, hrInner, base, now)
                    : Analysis.classifyRaw(null, null, base);

            // Respiration (RSA): Welch recompute throttled inside the tracker + last-good hold.
            RespirationTracker.Result resp = respiration.compute(now, tickCount);
            respOut = resp.brpm; respConf = resp.confidence; previewOut = resp.preview;

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
        String wall = HrvTime.localIso(now);

        // daily steps rollover + persist
        int stepDelta = Math.max(0, stepNow - lastStepCount);
        lastStepCount = stepNow;
        long today = HrvTime.jstMidnight(now);
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
            status.put("hr", HrvJson.jn(fresh ? hrVal : null));
            status.put("rmssd", HrvJson.jn(fresh ? rmssd : null));
            status.put("rmssd5", HrvJson.jn(fresh ? rmssd5 : null));
            status.put("rmssdSmoothed", HrvJson.jn(fresh ? rmssdSm : null));
            status.put("sdnn", HrvJson.jn(fresh ? sdnn : null));
            status.put("rrCount", fresh ? r.count : 0);
            status.put("beatsTotal", beats);
            status.put("rejected", r.corrected);
            status.put("corrected", r.corrected);
            status.put("baseline", base != null ? new JSONObject().put("rmssd", round1(base.rmssd)).put("hr", round1(base.hr)) : JSONObject.NULL);
            status.put("calibration", round2(baseline.progress()));
            status.put("state", HrvJson.stateJson(state));
            status.put("respiration", HrvJson.jn(fresh ? respOut : null));
            status.put("respirationConfidence", HrvJson.jn(fresh ? respConf : null));
            status.put("respirationPreview", previewOut);
            status.put("posture", HrvJson.postureJson(p));
            status.put("steps", stepsEnabled
                    ? new JSONObject().put("today", stepTotal).put("cadence", steps.cadence()).put("walking", steps.walking())
                    : new JSONObject().put("today", JSONObject.NULL).put("disabled", true)); // ACC duty-cycle: steps omitted

            status.put("body", body.state);
            status.put("engine", "native");
            status.put("recording", ble != null && ble.isRecording());
            status.put("externalRecording", ble != null && ble.isExternalBlocking());
            status.put("restored", lastBackfillRestored);
            status.put("battery", deviceBattery >= 0 ? deviceBattery : JSONObject.NULL);
            status.put("dataFresh", fresh);
            status.put("sampleAt", lastRrAt > 0 ? HrvTime.localIso(lastRrAt) : JSONObject.NULL);
            status.put("sampleAgeMs", lastRrAt > 0 ? now - lastRrAt : JSONObject.NULL);
            status.put("updatedAt", wall);

            String statusStr = status.toString();
            db.setStatus(user, statusStr, now);
            Emitter e = emitter;
            if (e != null) e.status(statusStr);

            Speaker sp = speaker;
            boolean warningSpoken = false;
            if (sp != null) {
                String warning = breathingAlert.update(now, fresh, rmssdSm,
                        base != null ? base.rmssd : null, respOut, respConf, previewOut);
                if (warning != null) {
                    warningSpoken = true;
                    lastSpokenAt = now; // prevent the periodic readout from replacing the warning in this tick
                    Log.i(TAG, "[breathing-alert] " + warning);
                    sp.speak(warning);
                }
            }

            // Relax-mode voice readout. Runs in the service tick, so it speaks with the
            // screen off / phone in a pocket (the WebView's JS timer would be throttled).
            // Only on a fresh live reading; silent while disconnected (no nagging).
            int rint = relaxIntervalSec;
            if (!warningSpoken && rint > 0 && sp != null && hrVal != null
                    && fresh
                    && now - lastSpokenAt >= rint * 1000L) {
                lastSpokenAt = now;
                String readout = RelaxReadout.format(hrVal, respOut, respConf, rmssd, rmssdSm,
                        base != null ? base.rmssd : null, state != null ? state.label : null);
                Log.i(TAG, "[relax] " + readout);
                sp.speak(readout);
            }

            // Persist a chart point only with a FRESH live reading (connected + an RR
            // arrived within POINT_FRESH_MS). t is floored to the whole second so a
            // backfilled point for the same second shares the primary key (dedup).
            if (fresh && (hrVal != null || rmssd != null)) {
                long tSec = (now / 1000L) * 1000L;
                Integer leanOut = (p.calibrated && p.receiving && p.leanDeg != null) ? p.leanDeg : null;
                String pointStr = HrvJson.buildPointJson(HrvTime.localIso(tSec), rmssd, hrVal, respOut,
                        state != null ? state.tone : null,
                        leanOut, p.state, p.leanDir, p.activity, stepDelta, body.state, p.sleepPos);
                db.addPoint(user, tSec, pointStr);
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

    private void notifyLinkStale(boolean stale) {
        LinkStateSink ls = linkStateSink;
        if (ls != null) { try { ls.onLinkStale(stale); } catch (Throwable ignored) {} }
    }

    private static double round1(double v) { return Math.round(v * 10.0) / 10.0; }
    private static double round2(double v) { return Math.round(v * 100.0) / 100.0; }
}
