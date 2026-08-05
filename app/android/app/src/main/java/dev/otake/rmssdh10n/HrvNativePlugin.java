package dev.otake.rmssdh10n;

import android.content.Intent;
import android.os.Build;
import android.util.Log;

import com.getcapacitor.JSObject;
import com.getcapacitor.Plugin;
import com.getcapacitor.PluginCall;
import com.getcapacitor.PluginMethod;
import com.getcapacitor.annotation.CapacitorPlugin;

/**
 * Bridge between the WebView dashboard and the native HRV engine in
 * {@link MonitorService}. The engine is the source of truth (writes every frame
 * to {@link HrvDb}); this plugin:
 *   - start/stop the native engine (via the foreground service),
 *   - pushes live status/point frames to the WebView while it is attached
 *     (notifyListeners — best-effort, low latency),
 *   - serves getStatus / getPointsSince reads from the DB on resume (the
 *     reliable catch-up path for data accumulated while hidden).
 */
@CapacitorPlugin(name = "HrvNative")
public class HrvNativePlugin extends Plugin {
    private static final String TAG = "HrvNativePlugin";
    private HrvDb db;

    private HrvDb db() {
        if (db == null) db = new HrvDb(getContext());
        return db;
    }

    @Override
    public void load() {
        // Receive live frames from the engine and relay them to the WebView.
        MonitorService.registerEmitter(new HrvEngine.Emitter() {
            @Override public void status(String json) { emit("hrvStatus", json); }
            @Override public void point(String json) { emit("hrvPoint", json); }
            @Override public void backfill(String json) { emit("hrvBackfill", json); }
        });
    }

    private void emit(String event, String json) {
        try { notifyListeners(event, new JSObject(json), true); }
        catch (Exception e) { Log.w(TAG, "emit " + event + " failed", e); }
    }

    @PluginMethod
    public void start(PluginCall call) {
        String mac = call.getString("mac", MonitorService.DEFAULT_MAC);
        boolean acc = Boolean.TRUE.equals(call.getBoolean("acc", false));
        int user = call.getInt("user", 1);
        String seed = call.getString("seed", null); // posture/supine refs + baseline
        Intent svc = new Intent(getContext(), MonitorService.class);
        svc.setAction(MonitorService.ACTION_START_ENGINE);
        svc.putExtra(MonitorService.EXTRA_MAC, mac);
        svc.putExtra(MonitorService.EXTRA_ACC, acc);
        svc.putExtra(MonitorService.EXTRA_USER, user);
        if (seed != null) svc.putExtra(MonitorService.EXTRA_SEED, seed);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) getContext().startForegroundService(svc);
        else getContext().startService(svc);
        JSObject ret = new JSObject();
        ret.put("ok", true);
        ret.put("engine", "native");
        call.resolve(ret);
    }

    @PluginMethod
    public void switchUser(PluginCall call) {
        String mac = call.getString("mac", MonitorService.DEFAULT_MAC);
        boolean acc = Boolean.TRUE.equals(call.getBoolean("acc", true));
        int user = call.getInt("user", 1);
        String seed = call.getString("seed", null);
        Intent svc = new Intent(getContext(), MonitorService.class);
        svc.setAction(MonitorService.ACTION_SWITCH_USER);
        svc.putExtra(MonitorService.EXTRA_MAC, mac);
        svc.putExtra(MonitorService.EXTRA_ACC, acc);
        svc.putExtra(MonitorService.EXTRA_USER, user);
        if (seed != null) svc.putExtra(MonitorService.EXTRA_SEED, seed);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) getContext().startForegroundService(svc);
        else getContext().startService(svc);
        JSObject ret = new JSObject();
        ret.put("ok", true);
        ret.put("engine", "native");
        ret.put("user", user);
        call.resolve(ret);
    }

    @PluginMethod
    public void setPostureRef(PluginCall call) {
        MonitorService s = MonitorService.INSTANCE;
        JSObject ret = new JSObject();
        ret.put("ok", s != null && s.nativeSetPostureRef());
        call.resolve(ret);
    }

    @PluginMethod
    public void setSupineRef(PluginCall call) {
        MonitorService s = MonitorService.INSTANCE;
        JSObject ret = new JSObject();
        ret.put("ok", s != null && s.nativeSetSupineRef());
        call.resolve(ret);
    }

    @PluginMethod
    public void setPowerSave(PluginCall call) {
        boolean on = Boolean.TRUE.equals(call.getBoolean("on", true));
        MonitorService s = MonitorService.INSTANCE;
        if (s != null) s.nativeSetPowerSave(on);
        JSObject ret = new JSObject();
        ret.put("ok", s != null);
        call.resolve(ret);
    }

    @PluginMethod
    public void setPostureEnabled(PluginCall call) {
        boolean on = Boolean.TRUE.equals(call.getBoolean("on", true));
        MonitorService s = MonitorService.INSTANCE;
        if (s != null) s.nativeSetPostureEnabled(on);
        JSObject ret = new JSObject();
        ret.put("ok", s != null);
        call.resolve(ret);
    }

    @PluginMethod
    public void toggleSleepLR(PluginCall call) {
        MonitorService s = MonitorService.INSTANCE;
        Boolean swap = s != null ? s.nativeToggleSleepLR() : null;
        JSObject ret = new JSObject();
        ret.put("ok", swap != null);
        ret.put("swap", swap != null && swap);
        call.resolve(ret);
    }

    @PluginMethod
    public void resetBaseline(PluginCall call) {
        MonitorService s = MonitorService.INSTANCE;
        boolean ok = s != null && s.nativeResetBaseline();
        JSObject ret = new JSObject();
        ret.put("ok", ok);
        ret.put("applied", ok);
        call.resolve(ret);
    }

    @PluginMethod
    public void setBaseline(PluginCall call) {
        MonitorService s = MonitorService.INSTANCE;
        Double r = call.getDouble("rmssd");
        Double h = call.getDouble("hr");
        boolean ok = s != null && r != null && h != null && s.nativeSetBaseline(r, h);
        JSObject ret = new JSObject();
        ret.put("ok", ok);
        call.resolve(ret);
    }

    /** Relax-mode voice readout interval in seconds (0 = off; e.g. 60 or 30). */
    @PluginMethod
    public void setRelaxVoice(PluginCall call) {
        int sec = call.getInt("sec", 0);
        MonitorService s = MonitorService.INSTANCE;
        if (s != null) s.nativeSetRelaxVoice(sec);
        JSObject ret = new JSObject();
        ret.put("ok", s != null);
        ret.put("sec", sec);
        call.resolve(ret);
    }

    /** Voice warning for sustained low RMSSD + shallow breathing. */
    @PluginMethod
    public void setBreathingAlert(PluginCall call) {
        boolean on = Boolean.TRUE.equals(call.getBoolean("on", true));
        MonitorService s = MonitorService.INSTANCE;
        if (s != null) s.nativeSetBreathingAlertVoice(on);
        JSObject ret = new JSObject();
        ret.put("ok", s != null);
        ret.put("on", on);
        call.resolve(ret);
    }

    /** Recent raw RR beats (JSON array string) for the Kubios/Elite-HRV export. */
    @PluginMethod
    public void getRrLog(PluginCall call) {
        MonitorService s = MonitorService.INSTANCE;
        JSObject ret = new JSObject();
        ret.put("log", s != null ? s.nativeRrLog() : "[]");
        call.resolve(ret);
    }

    @PluginMethod
    public void stop(PluginCall call) {
        Intent svc = new Intent(getContext(), MonitorService.class);
        svc.setAction(MonitorService.ACTION_STOP_ENGINE);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) getContext().startForegroundService(svc);
        else getContext().startService(svc);
        JSObject ret = new JSObject();
        ret.put("ok", true);
        ret.put("engine", "js");
        call.resolve(ret);
    }

    /** Latest status snapshot (JSON string), or null if none yet. */
    @PluginMethod
    public void getStatus(PluginCall call) {
        JSObject ret = new JSObject();
        ret.put("value", db().getStatus(call.getInt("user", 1)));
        call.resolve(ret);
    }

    /** Points with t_ms > since (ms epoch, passed as string), ascending, paged. */
    @PluginMethod
    public void getPointsSince(PluginCall call) {
        long since = 0;
        try { since = Long.parseLong(call.getString("since", "0")); } catch (Exception ignored) {}
        int limit = boundedLimit(call.getInt("limit", 2000));
        HrvDb.PointsPage page = db().getPointsSince(call.getInt("user", 1), since, limit);
        JSObject ret = new JSObject();
        ret.put("points", page.jsonArray); // JSON array string
        ret.put("count", page.count);
        ret.put("hasMore", page.hasMore);
        ret.put("lastT", String.valueOf(page.lastT));
        call.resolve(ret);
    }

    /** Unmerged backfill import ranges (JSON array string of {id,fromMs,toMs,restored,
     *  truncated}). The WebView drains these on load/resume and re-fetches each range from
     *  the DB — so a gap restored while no WebView was attached is still surfaced. This is
     *  the reliable catch-up path; the live hrvBackfill event is just a low-latency nudge. */
    @PluginMethod
    public void getUnmergedImports(PluginCall call) {
        JSObject ret = new JSObject();
        ret.put("imports", db().unmergedImportsJson(call.getInt("user", 1)));
        call.resolve(ret);
    }

    /** Flag import ids (CSV of integers) as merged so they are not merged again. */
    @PluginMethod
    public void markImportsMerged(PluginCall call) {
        db().markImportsMerged(call.getInt("user", 1), call.getString("ids", ""));
        JSObject ret = new JSObject();
        ret.put("ok", true);
        call.resolve(ret);
    }

    /** Latest finite window, chronological. Keeps startup independent of total DB size. */
    @PluginMethod
    public void getLatestPoints(PluginCall call) {
        int user = call.getInt("user", 1);
        int limit = boundedLimit(call.getInt("limit", 900));
        resolvePoints(call, db().getLatestPoints(user, limit));
    }

    /** Fixed-upper-bound keyset page used by catch-up and past-range backfill merges. */
    @PluginMethod
    public void getPointsRange(PluginCall call) {
        int user = call.getInt("user", 1);
        long after = parseLong(call.getString("after", "0"), 0);
        long toExclusive = parseLong(call.getString("toExclusive", String.valueOf(Long.MAX_VALUE)), Long.MAX_VALUE);
        int limit = boundedLimit(call.getInt("limit", 2000));
        resolvePoints(call, db().getPointsRange(user, after, toExclusive, limit));
    }

    private static int boundedLimit(int requested) { return Math.max(1, Math.min(2000, requested)); }
    private static long parseLong(String value, long fallback) {
        try { return Long.parseLong(value); } catch (Exception ignored) { return fallback; }
    }
    private static void resolvePoints(PluginCall call, HrvDb.PointsPage page) {
        JSObject ret = new JSObject();
        ret.put("points", page.jsonArray);
        ret.put("count", page.count);
        ret.put("hasMore", page.hasMore);
        ret.put("lastT", String.valueOf(page.lastT));
        call.resolve(ret);
    }

    /** Native long-range aggregates; localStorage is only a disposable renderer cache. */
    @PluginMethod
    public void getAggregates(PluginCall call) {
        int user = call.getInt("user", 1);
        long width = parseLong(call.getString("widthMs", "300000"), 300_000L);
        long from = parseLong(call.getString("fromMs", "0"), 0);
        long to = parseLong(call.getString("toMs", String.valueOf(Long.MAX_VALUE)), Long.MAX_VALUE);
        int limit = Math.max(1, Math.min(4000, call.getInt("limit", 4000)));
        JSObject ret = new JSObject();
        ret.put("buckets", db().aggregatesJson(user, width, from, to, limit));
        call.resolve(ret);
    }

    @PluginMethod
    public void getDataDiagnostics(PluginCall call) {
        JSObject ret = new JSObject();
        ret.put("value", db().diagnosticsJson(call.getInt("user", 1)));
        call.resolve(ret);
    }

    /** Destructive reset from the dashboard's complete-clear action. */
    @PluginMethod
    public void clearAllData(PluginCall call) {
        MonitorService s = MonitorService.INSTANCE;
        if (s != null) s.nativeClearAllData();
        else db().clearAllData();
        JSObject ret = new JSObject();
        ret.put("ok", true);
        call.resolve(ret);
    }
}
