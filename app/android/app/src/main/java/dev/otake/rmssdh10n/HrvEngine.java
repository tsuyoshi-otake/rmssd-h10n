package dev.otake.rmssdh10n;

import android.content.Context;
import android.util.Log;

import org.json.JSONObject;

import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;
import java.util.TimeZone;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

import dev.otake.rmssdh10n.hrv.Posture;
import dev.otake.rmssdh10n.hrv.Rmssd;
import dev.otake.rmssdh10n.hrv.Steps;

/**
 * The native 1 Hz HRV reporting loop — the service-side counterpart of
 * app/src/monitor.js's _tick(). Runs on a {@link ScheduledExecutorService} that
 * does NOT depend on a WebView/JS timer, so it keeps ticking with the screen off
 * (the whole point of the native port). Receives RR/HR/ACC from {@link BleNative},
 * computes RMSSD/SDNN/HR (+ posture/steps when ACC is on), writes every frame to
 * {@link HrvDb} (the source of truth), and pushes live frames to the WebView via
 * the {@link Emitter} when one is attached.
 *
 * Stage 1a: RMSSD/HR + idle posture/steps. State/baseline/body/respiration are
 * filled by the WebView (merged) until they are ported in a later stage.
 */
public final class HrvEngine {
    private static final String TAG = "HrvEngine";

    public interface Emitter {
        void status(String json);
        void point(String json);
    }

    private static final SimpleDateFormat ISO;
    static {
        ISO = new SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss.SSS'+09:00'", Locale.US);
        ISO.setTimeZone(TimeZone.getTimeZone("GMT+09:00")); // JST, matching src/time.js localIso
    }
    private static synchronized String localIso(long epochMs) { return ISO.format(new Date(epochMs)); }

    private final Context ctx;
    private final HrvDb db;
    private final Object gate = new Object();

    private final Rmssd win = new Rmssd(30000);
    private final Rmssd win5 = new Rmssd(300000);
    private final Posture posture;
    private final Steps steps = new Steps();

    private int user = 1;
    private final boolean withAcc;
    private volatile boolean connected = false;
    private volatile Integer deviceHr = null;
    private double lastPeakMs = 0;
    private long beats = 0;
    private int lastStepCount = 0;

    private BleNative ble;
    private ScheduledExecutorService ticker;
    private volatile Emitter emitter;
    private String deviceMac;

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
        Posture.Vec ref = null, sup = null;
        int latSign = 1;
        // (refs are seeded from kv in a later stage; idle until then)
        this.posture = new Posture(ref, sup, latSign, 25);
    }

    public void setEmitter(Emitter e) { this.emitter = e; }
    public void setUser(int u) { this.user = u; }

    public void start(String mac) {
        this.deviceMac = mac;
        battWindowStart = System.currentTimeMillis();
        ble = new BleNative(ctx, mac, withAcc, new BleNative.Sink() {
            @Override public void onHr(int hr) { deviceHr = hr; }
            @Override public void onRr(double rrMs) {
                synchronized (gate) {
                    lastPeakMs += rrMs;
                    beats++;
                    win.add(lastPeakMs, rrMs);
                    win5.add(lastPeakMs, rrMs);
                }
            }
            @Override public void onAcc(int x, int y, int z) {
                synchronized (gate) { accSamples++; posture.add(x, y, z); steps.add(x, y, z); }
            }
            @Override public void onConnected(boolean c) { connected = c; if (!c) deviceHr = null; }
            @Override public void log(String m) { Log.i(TAG, "[ble] " + m); }
        });
        ble.start();
        ticker = Executors.newSingleThreadScheduledExecutor();
        ticker.scheduleAtFixedRate(this::tickSafe, 1000, 1000, TimeUnit.MILLISECONDS);
        Log.i(TAG, "engine started (mac=" + mac + ", acc=" + withAcc + ")");
    }

    public void stop() {
        if (ticker != null) { ticker.shutdownNow(); ticker = null; }
        if (ble != null) { ble.stop(); ble = null; }
        db.flush();
        Log.i(TAG, "engine stopped");
    }

    private void tickSafe() {
        try { tick(); } catch (Throwable t) { Log.e(TAG, "tick error", t); }
    }

    private void tick() {
        long now = System.currentTimeMillis();
        if (lastTickAt != 0) { long gap = now - lastTickAt; if (gap > battMaxGap) battMaxGap = gap; }
        lastTickAt = now;
        tickCount++;
        battTicks++;

        Rmssd.Result r, r5;
        Posture.Result p;
        synchronized (gate) {
            r = win.compute(lastPeakMs);
            r5 = win5.compute(lastPeakMs);
            p = posture.compute(now);
        }

        Integer effHr = deviceHr != null ? deviceHr : (r.hr != null ? (int) Math.round(r.hr) : null);
        Double rmssd = r.rmssd != null ? round1(r.rmssd) : null;
        Double rmssdSm = r.rmssdEma != null ? round1(r.rmssdEma) : null;
        Double sdnn = r.sdnn != null ? round1(r.sdnn) : null;
        Double rmssd5 = r5.rmssd != null ? round1(r5.rmssd) : null;
        Double hrVal = effHr != null ? (double) effHr : null;
        String wall = localIso(now);

        int stepNow = steps.steps;
        int stepDelta = Math.max(0, stepNow - lastStepCount);
        lastStepCount = stepNow;

        try {
            JSONObject pj = postureJson(p);
            JSONObject stepsJson = new JSONObject()
                .put("today", stepNow).put("cadence", steps.cadence()).put("walking", steps.walking());

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
            status.put("baseline", JSONObject.NULL);     // WebView-owned (stage 1a)
            status.put("calibration", 0);
            status.put("state", JSONObject.NULL);         // WebView-merged
            status.put("respiration", JSONObject.NULL);
            status.put("respirationConfidence", JSONObject.NULL);
            status.put("respirationPreview", false);
            status.put("posture", pj);
            status.put("steps", stepsJson);
            status.put("body", JSONObject.NULL);
            status.put("engine", "native");
            status.put("updatedAt", wall);

            String statusStr = status.toString();
            db.setStatus(statusStr, now);
            Emitter e = emitter;
            if (e != null) e.status(statusStr);

            if (hrVal != null || rmssd != null) {
                JSONObject point = new JSONObject();
                point.put("t", wall);
                point.put("rmssd", jn(rmssd));
                point.put("hr", jn(hrVal));
                point.put("resp", JSONObject.NULL);
                point.put("tone", JSONObject.NULL);
                point.put("lean", (p.calibrated && p.receiving && p.leanDeg != null) ? p.leanDeg : JSONObject.NULL);
                point.put("posture", p.state);
                point.put("activity", p.activity != null ? p.activity : JSONObject.NULL);
                point.put("step", stepDelta);
                point.put("body", JSONObject.NULL);
                point.put("sleepPos", p.sleepPos != null ? p.sleepPos : JSONObject.NULL);
                String pointStr = point.toString();
                db.addPoint(now, pointStr);
                if (e != null) e.point(pointStr);
            }
        } catch (Exception ex) {
            Log.e(TAG, "json", ex);
        }

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

    private static JSONObject postureJson(Posture.Result p) throws Exception {
        JSONObject o = new JSONObject();
        o.put("receiving", p.receiving);
        o.put("calibrated", p.calibrated);
        o.put("state", p.state);
        o.put("leanDeg", p.leanDeg != null ? p.leanDeg : JSONObject.NULL);
        o.put("activity", p.activity != null ? p.activity : JSONObject.NULL);
        o.put("moving", p.moving);
        o.put("sleepPos", p.sleepPos != null ? p.sleepPos : JSONObject.NULL);
        return o;
    }

    private static Object jn(Double v) { return v == null ? JSONObject.NULL : v; }
    private static double round1(double v) { return Math.round(v * 10.0) / 10.0; }
}
