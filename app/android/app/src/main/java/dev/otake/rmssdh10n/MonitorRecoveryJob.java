package dev.otake.rmssdh10n;

import android.Manifest;
import android.app.job.JobInfo;
import android.app.job.JobParameters;
import android.app.job.JobScheduler;
import android.app.job.JobService;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.os.Build;
import android.os.PowerManager;
import android.os.SystemClock;
import android.os.UserManager;
import android.util.Log;

/** One persisted, OS-scheduled fallback. Never polls BLE or owns a second engine. */
public final class MonitorRecoveryJob extends JobService {
    static final int JOB_ID = 2401;
    private static final String TAG = "MonitorRecovery";
    private static long requestUntil;

    private static SharedPreferences prefs(Context context) {
        return context.getSharedPreferences("monitor_recovery", Context.MODE_PRIVATE);
    }

    static boolean unlocked(Context context) {
        UserManager manager = context.getSystemService(UserManager.class);
        return manager != null && manager.isUserUnlocked();
    }

    static boolean permitted(Context context) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            return context.checkSelfPermission(Manifest.permission.BLUETOOTH_CONNECT) == PackageManager.PERMISSION_GRANTED
                    && context.checkSelfPermission(Manifest.permission.BLUETOOTH_SCAN) == PackageManager.PERMISSION_GRANTED;
        }
        return context.checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED;
    }

    static boolean batteryExempt(Context context) {
        PowerManager manager = context.getSystemService(PowerManager.class);
        return manager != null && manager.isIgnoringBatteryOptimizations(context.getPackageName());
    }

    static void schedule(Context context) {
        JobScheduler scheduler = context.getSystemService(JobScheduler.class);
        if (scheduler == null || scheduler.getPendingJob(JOB_ID) != null) return;
        int result = scheduler.schedule(new JobInfo.Builder(JOB_ID,
                new ComponentName(context, MonitorRecoveryJob.class))
                .setPersisted(true).setPeriodic(15 * 60_000L).build());
        if (result != JobScheduler.RESULT_SUCCESS) Log.w(TAG, "recovery job could not be scheduled");
    }

    static void cancel(Context context) {
        JobScheduler scheduler = context.getSystemService(JobScheduler.class);
        if (scheduler != null) scheduler.cancel(JOB_ID);
        requestUntil = 0;
        if (!prefs(context).edit().clear().commit()) Log.w(TAG, "could not clear recovery cooldown");
    }

    static long nextAttempt(Context context) { return prefs(context).getLong("nextAttempt", 0); }

    /** Persist before native initialization, including a process death during startup. */
    static void starting(Context context) {
        SharedPreferences p = prefs(context);
        int failures = Math.min(6, p.getInt("failures", 0) + 1);
        long next = System.currentTimeMillis() + MonitorRecoveryPolicy.retryDelay(failures, Math.random());
        if (!p.edit().putInt("failures", failures).putLong("nextAttempt", next).commit())
            throw new IllegalStateException("cannot persist monitor recovery attempt");
    }

    static void started(Context context) {
        requestUntil = 0;
        if (!prefs(context).edit().remove("failures").remove("nextAttempt").commit())
            Log.w(TAG, "could not reset recovery cooldown");
    }

    /** Called on the main thread. Only boot/update or a visible Activity supplies an exemption. */
    static void restore(Context context, boolean launchExempt, String reason) {
        if (!unlocked(context)) return; // hrv.db is credential-encrypted; never open it before unlock.
        try (HrvDb db = new HrvDb(context)) {
            MonitorService service = MonitorService.INSTANCE;
            MonitorRecoveryPolicy.Decision decision = MonitorRecoveryPolicy.decide(
                    db.kvGet("engine"), db.kvGet("deviceMac"), true, permitted(context),
                    service != null && service.engineRunning(), System.currentTimeMillis(), nextAttempt(context));
            if (decision == MonitorRecoveryPolicy.Decision.STOPPED) { cancel(context); return; }
            schedule(context);
            if (decision != MonitorRecoveryPolicy.Decision.START) {
                Log.i(TAG, reason + ": " + decision);
                return;
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S && !launchExempt && !batteryExempt(context)) {
                Log.i(TAG, reason + ": background start requires battery exemption or opening the app");
                return;
            }
            if (SystemClock.elapsedRealtime() < requestUntil) return;
            requestUntil = SystemClock.elapsedRealtime() + 30_000L;
            try {
                context.startForegroundService(new Intent(context, MonitorService.class));
                Log.i(TAG, reason + ": service restart requested");
            } catch (RuntimeException failure) {
                Log.w(TAG, reason + ": restart deferred to the next recovery job", failure);
            }
        } catch (RuntimeException failure) {
            // A storage failure must neither erase intent nor delete an H10 recording.
            Log.w(TAG, reason + ": recovery state unavailable", failure);
        }
    }

    @Override public boolean onStartJob(JobParameters parameters) {
        restore(this, false, "periodic");
        return false; // no async work owned by this job; next attempt is OS scheduled.
    }

    @Override public boolean onStopJob(JobParameters parameters) { return false; }
}
