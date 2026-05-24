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
        ret.put("value", db().getStatus());
        call.resolve(ret);
    }

    /** Points with t_ms > since (ms epoch, passed as string), ascending, paged. */
    @PluginMethod
    public void getPointsSince(PluginCall call) {
        long since = 0;
        try { since = Long.parseLong(call.getString("since", "0")); } catch (Exception ignored) {}
        int limit = call.getInt("limit", 5000);
        HrvDb.PointsPage page = db().getPointsSince(since, limit);
        JSObject ret = new JSObject();
        ret.put("points", page.jsonArray); // JSON array string
        ret.put("count", page.count);
        ret.put("hasMore", page.hasMore);
        ret.put("lastT", String.valueOf(page.lastT));
        call.resolve(ret);
    }
}
