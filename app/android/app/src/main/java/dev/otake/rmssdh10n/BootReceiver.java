package dev.otake.rmssdh10n;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.os.Build;
import android.util.Log;

/**
 * Restart monitoring after a device reboot / app update so the H10 gap recorded while
 * the phone was off is recovered on the next connect (the recording's start-anchor +
 * state are persisted in {@link HrvDb}, so a brand-new process recovers it). Acts only
 * if the native engine was the active one (kv engine==native); the service's null-intent
 * branch restores the engine from kv. A clean (user) stop sets engine=js, so a reboot
 * after a deliberate stop does NOT silently resume.
 *
 * Note: on Android 14+ a connectedDevice foreground service started from BOOT_COMPLETED
 * may be restricted; the start is best-effort (failure is logged, the user can reopen the
 * app to recover via the same persisted meta).
 */
public class BootReceiver extends BroadcastReceiver {
    private static final String TAG = "BootReceiver";

    @Override public void onReceive(Context ctx, Intent intent) {
        String action = intent != null ? intent.getAction() : null;
        if (action == null) return;
        if (!Intent.ACTION_BOOT_COMPLETED.equals(action)
                && !Intent.ACTION_LOCKED_BOOT_COMPLETED.equals(action)
                && !Intent.ACTION_MY_PACKAGE_REPLACED.equals(action)) return;
        try {
            if (!"native".equals(new HrvDb(ctx).kvGet("engine"))) return; // user wasn't monitoring
            Intent svc = new Intent(ctx, MonitorService.class);            // null action → kv-restore branch
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) ctx.startForegroundService(svc);
            else ctx.startService(svc);
            Log.i(TAG, "boot/" + action + " → restarting native monitor for H10 gap recovery");
        } catch (Throwable t) {
            Log.w(TAG, "boot restart failed (open the app to recover)", t);
        }
    }
}
