package dev.otake.rmssdh10n;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Intent;
import android.content.pm.ServiceInfo;
import android.os.Build;
import android.os.IBinder;
import android.os.PowerManager;
import android.util.Log;

import androidx.core.app.NotificationCompat;

/**
 * Foreground service that owns the native HRV engine (BLE + 1 Hz compute) so
 * monitoring keeps running with the screen off / app backgrounded — the WebView
 * JS timer is throttled/stopped by the OS, the native loop is not. The engine
 * writes every frame to {@link HrvDb} (source of truth); the {@link
 * HrvNativePlugin} pushes live frames to the WebView when one is attached and
 * serves catch-up reads on resume.
 *
 * The service also still backs the legacy "keepAlive" use (foreground + wake
 * lock) for the JS engine, so switching engines is non-destructive.
 */
public class MonitorService extends Service {
    private static final String TAG = "MonitorService";
    private static final String CHANNEL = "rmssd_monitor";
    private static final int NOTIF_ID = 1;
    public static final String DEFAULT_MAC = "24:AC:AC:1B:54:C8"; // the user's H10

    public static final String ACTION_START_ENGINE = "dev.otake.rmssdh10n.START_ENGINE";
    public static final String ACTION_STOP_ENGINE  = "dev.otake.rmssdh10n.STOP_ENGINE";
    public static final String EXTRA_MAC  = "mac";
    public static final String EXTRA_ACC  = "acc";
    public static final String EXTRA_USER = "user";
    public static final String EXTRA_SEED = "seed"; // JSON: posture/supine refs + baseline

    public static volatile MonitorService INSTANCE;
    private static HrvEngine.Emitter sEmitter; // set by the plugin (WebView lifecycle)

    private PowerManager.WakeLock wakeLock;
    private HrvDb db;
    private HrvEngine engine;

    /** Register the WebView-side emitter; applied to a running engine immediately. */
    public static void registerEmitter(HrvEngine.Emitter e) {
        sEmitter = e;
        MonitorService i = INSTANCE;
        if (i != null && i.engine != null) i.engine.setEmitter(e);
    }

    public HrvDb db() {
        if (db == null) db = new HrvDb(this);
        return db;
    }

    public boolean engineRunning() { return engine != null; }

    @Override
    public void onCreate() {
        super.onCreate();
        INSTANCE = this;
        db = new HrvDb(this);
        NotificationManager nm = getSystemService(NotificationManager.class);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O && nm != null) {
            NotificationChannel ch = new NotificationChannel(
                    CHANNEL, "RMSSD モニタリング", NotificationManager.IMPORTANCE_LOW);
            ch.setShowBadge(false);
            ch.setDescription("バックグラウンドで心拍変動を計測します");
            nm.createNotificationChannel(ch);
        }
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        Intent open = new Intent(this, MainActivity.class);
        int piFlags = PendingIntent.FLAG_UPDATE_CURRENT
                | (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M ? PendingIntent.FLAG_IMMUTABLE : 0);
        PendingIntent pi = PendingIntent.getActivity(this, 0, open, piFlags);

        Notification n = new NotificationCompat.Builder(this, CHANNEL)
                .setContentTitle("RMSSD モニタリング")
                .setContentText("心拍変動を計測中（バックグラウンド）")
                .setSmallIcon(R.mipmap.ic_launcher)
                .setOngoing(true)
                .setContentIntent(pi)
                .setPriority(NotificationCompat.PRIORITY_LOW)
                .build();

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            startForeground(NOTIF_ID, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE);
        } else {
            startForeground(NOTIF_ID, n);
        }

        if (wakeLock == null) {
            PowerManager pm = getSystemService(PowerManager.class);
            if (pm != null) {
                wakeLock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "rmssd:monitor");
                wakeLock.setReferenceCounted(false);
                wakeLock.acquire();
            }
        }

        handleIntent(intent);
        return START_STICKY; // restart if the OS kills us
    }

    private void handleIntent(Intent intent) {
        String action = intent != null ? intent.getAction() : null;
        if (ACTION_STOP_ENGINE.equals(action)) {
            stopEngine();
            return;
        }
        if (ACTION_START_ENGINE.equals(action)) {
            String mac = intent.getStringExtra(EXTRA_MAC);
            boolean acc = intent.getBooleanExtra(EXTRA_ACC, false);
            int user = intent.getIntExtra(EXTRA_USER, 1);
            startEngine(mac != null ? mac : DEFAULT_MAC, acc, user, intent.getStringExtra(EXTRA_SEED));
            return;
        }
        // Null/empty intent = START_STICKY restart (or keepAlive's service start).
        // Restore the native engine if it was the active one and none is running yet.
        if (engine == null && "native".equals(db().kvGet("engine"))) {
            String mac = db().kvGet("deviceMac");
            boolean acc = "1".equals(db().kvGet("acc"));
            int user = parseInt(db().kvGet("user"), 1);
            startEngine(mac != null ? mac : DEFAULT_MAC, acc, user, null); // refs/baseline restored from kv
        }
    }

    private void startEngine(String mac, boolean acc, int user, String seed) {
        // Idempotent: a normal launch fires BOTH an explicit START_ENGINE and a
        // kv-restore start (keepAlive's service start hits the null-intent branch
        // while kv still says "native" from a prior session). Starting twice spins
        // up two H10 connections whose scans collide (InterruptedException), so a
        // running engine wins — switchUser/stop set engine=null first via stopEngine.
        if (engine != null) {
            Log.i(TAG, "startEngine ignored — engine already running");
            return;
        }
        engine = new HrvEngine(this, db(), acc);
        engine.setUser(user);
        if (seed != null) engine.seed(seed);
        engine.setEmitter(sEmitter);
        engine.start(mac);
        db().kvPut("engine", "native");
        db().kvPut("deviceMac", mac);
        db().kvPut("acc", acc ? "1" : "0");
        db().kvPut("user", String.valueOf(user));
        Log.i(TAG, "native engine started mac=" + mac + " acc=" + acc + " user=" + user);
    }

    // Posture-reference controls routed from the plugin (no-op if engine off).
    public boolean nativeSetPostureRef() { return engine != null && engine.setPostureRef(); }
    public boolean nativeSetSupineRef() { return engine != null && engine.setSupineRef(); }
    public Boolean nativeToggleSleepLR() { return engine != null ? engine.toggleSleepLR() : null; }
    // Baseline + RR-log controls routed from the plugin (no-op if engine off).
    public boolean nativeResetBaseline() { if (engine == null) return false; engine.resetBaseline(); return true; }
    public boolean nativeSetBaseline(double r, double h) { return engine != null && engine.setBaseline(r, h); }
    public String nativeRrLog() { return engine != null ? engine.rrLogJson() : "[]"; }
    public void nativeForegroundEntered() { if (engine != null) engine.foregroundEntered(); }

    private void stopEngine() {
        // Explicit (user) stop: mark the recording discarded BEFORE teardown so the next
        // launch does NOT auto-recover it. An OS kill goes through onDestroy without this,
        // leaving the recording 'active' so its gap IS recovered on restart.
        if (engine != null) { engine.markUserStopped(); engine.stop(); engine = null; }
        db().kvPut("engine", "js");
        Log.i(TAG, "native engine stopped (engine=js)");
    }

    private static int parseInt(String s, int def) {
        try { return s == null ? def : Integer.parseInt(s); } catch (Exception e) { return def; }
    }

    @Override
    public void onDestroy() {
        if (engine != null) { engine.stop(); engine = null; }
        if (wakeLock != null && wakeLock.isHeld()) wakeLock.release();
        wakeLock = null;
        if (INSTANCE == this) INSTANCE = null;
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
            stopForeground(Service.STOP_FOREGROUND_REMOVE);
        } else {
            stopForeground(true);
        }
        super.onDestroy();
    }

    @Override
    public IBinder onBind(Intent intent) { return null; }
}
