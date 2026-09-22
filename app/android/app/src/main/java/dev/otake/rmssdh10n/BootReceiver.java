package dev.otake.rmssdh10n;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;

/**
 * Restart monitoring after a device reboot / app update so the H10 gap recorded while
 * the phone was off is recovered on the next connect (the recording's start-anchor +
 * state are persisted in {@link HrvDb}, so a brand-new process recovers it). Acts only
 * if the native engine was the active one (kv engine==native); the service's null-intent
 * branch restores the engine from kv. A clean (user) stop sets engine=js, so a reboot
 * after a deliberate stop does NOT silently resume.
 *
 * The connectedDevice service is eligible for the boot/update launch exemption. The
 * credential-encrypted database is read only after unlock; no direct-boot access.
 */
public class BootReceiver extends BroadcastReceiver {
    @Override public void onReceive(Context ctx, Intent intent) {
        String action = intent != null ? intent.getAction() : null;
        if (action == null) return;
        if (!Intent.ACTION_BOOT_COMPLETED.equals(action)
                && !Intent.ACTION_MY_PACKAGE_REPLACED.equals(action)) return;
        MonitorRecoveryJob.restore(ctx, true, action);
    }
}
