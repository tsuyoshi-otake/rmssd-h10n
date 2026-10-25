package dev.otake.rmssdh10n;

import android.annotation.SuppressLint;
import android.app.ActivityManager;
import android.bluetooth.BluetoothAdapter;
import android.bluetooth.BluetoothDevice;
import android.bluetooth.BluetoothManager;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.os.PowerManager;
import android.os.SystemClock;

import androidx.core.content.ContextCompat;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.function.BooleanSupplier;
import java.util.function.Consumer;

/**
 * OS-level BLE bonding for the H10, performed while the SDK link is held down. The H10's PFTP
 * (recording) characteristics need an encrypted link, and an H10 that still holds a bond the
 * phone has lost demands one on every connect: it sends its own pairing request, SMP times
 * out after 30 s unless the user consents, and the HR notifications queue behind it so no RR
 * ever flows (#30). Android shows that consent as a notification unless the app that called
 * {@code createBond} for the device is in front with the screen on. On the moto g05 our own
 * createBond timed out without a prompt, but every H10 request after it came up as a dialog
 * over the app. No-op when already bonded, so it can't regress the working path; best-effort
 * otherwise (we still try to connect if it fails).
 */
final class PolarBonding {
    private PolarBonding() {}

    /** Android shows a pairing consent as a dialog only when the app that called createBond
     *  is the foreground app and the screen is on; otherwise it becomes a notification. */
    static boolean userCanAnswerPrompt(Context ctx) {
        try {
            PowerManager pm = ctx.getSystemService(PowerManager.class);
            if (pm != null && !pm.isInteractive()) return false;
            ActivityManager.RunningAppProcessInfo me = new ActivityManager.RunningAppProcessInfo();
            ActivityManager.getMyMemoryState(me);
            return me.importance == ActivityManager.RunningAppProcessInfo.IMPORTANCE_FOREGROUND;
        } catch (Throwable t) {
            return false;
        }
    }

    /** Whether the phone currently holds an OS bond for {@code mac} (needed for PFTP). */
    @SuppressLint("MissingPermission")
    static boolean isBonded(Context ctx, String mac) {
        try {
            BluetoothDevice dev = remoteDevice(ctx, mac);
            return dev != null && dev.getBondState() == BluetoothDevice.BOND_BONDED;
        } catch (Throwable t) {
            return false;
        }
    }

    /** Pairs {@code mac} through an app-initiated createBond. Blocking, at most two bounded
     *  waits of 30 s; call from a worker thread with no SDK connection being opened. */
    @SuppressLint("MissingPermission") // BLUETOOTH_CONNECT is requested at runtime in MainActivity
    static void ensureBonded(Context ctx, String mac, Consumer<String> log) {
        try {
            BluetoothDevice dev = remoteDevice(ctx, mac);
            if (dev == null) return;
            if (dev.getBondState() == BluetoothDevice.BOND_BONDED) return; // already paired — nothing to do
            if (dev.getBondState() == BluetoothDevice.BOND_BONDING) {
                // A pairing is already in flight — typically the H10's own request, which Android
                // only posts as a notification. createBond() fails while it runs, so let it settle
                // (accepted from that notification, or timed out) and then ask ourselves (#30).
                log.accept("bond already in progress — waiting…");
                awaitSettled(ctx, dev, mac, true, () -> true);
                if (dev.getBondState() != BluetoothDevice.BOND_NONE) {
                    log.accept("bond state after attempt: " + dev.getBondState());
                    return;
                }
            }
            log.accept("bonding H10 (createBond)…");
            awaitSettled(ctx, dev, mac, false, () -> {
                if (dev.createBond()) return true;
                log.accept("createBond() returned false");
                return false;
            });
            log.accept("bond state after attempt: " + dev.getBondState());
        } catch (Throwable t) {
            log.accept("ensureBonded failed: " + t.getMessage());
        }
    }

    /** Runs {@code begin} and, when it started something, waits up to 30 s for the bond to
     *  settle. Listens for the bond-state broadcast and also polls: the H10's own request was
     *  seen to time out without the broadcast reaching this wait (#30). {@code bonding}: the
     *  attempt is already known to be in flight. */
    @SuppressLint("MissingPermission")
    private static void awaitSettled(Context ctx, BluetoothDevice dev, String mac, boolean bonding,
            BooleanSupplier begin) throws InterruptedException {
        final CountDownLatch latch = new CountDownLatch(1);
        BroadcastReceiver rx = settleReceiver(mac, latch);
        ContextCompat.registerReceiver(ctx, rx,
                new IntentFilter(BluetoothDevice.ACTION_BOND_STATE_CHANGED),
                ContextCompat.RECEIVER_NOT_EXPORTED);
        try {
            if (!begin.getAsBoolean()) return;
            long deadline = SystemClock.elapsedRealtime() + 30_000;
            while (SystemClock.elapsedRealtime() < deadline) {
                if (latch.await(500, TimeUnit.MILLISECONDS)) return;
                int state = dev.getBondState();
                if (state == BluetoothDevice.BOND_BONDING) bonding = true;
                else if (state == BluetoothDevice.BOND_BONDED || bonding) return;
            }
        } finally {
            try { ctx.unregisterReceiver(rx); } catch (Exception ignored) {}
        }
    }

    /**
     * Clear a (possibly one-sided) stale bond and pair again. The H10 can lose its side of
     * the bond (battery pull / factory reset) while the phone still reports BOND_BONDED —
     * then every PFTP op fails on encryption forever and no reconnect fixes it; only
     * re-pairing does. Blocking (up to ~40 s); call from a worker thread with the SDK link
     * already disconnected. Returns true when the device ends up bonded.
     */
    @SuppressLint("MissingPermission")
    static boolean rebond(Context ctx, String mac, Consumer<String> log) {
        try {
            BluetoothDevice dev = remoteDevice(ctx, mac);
            if (dev == null) return false;
            if (dev.getBondState() != BluetoothDevice.BOND_NONE) {
                final CountDownLatch latch = new CountDownLatch(1);
                BroadcastReceiver rx = settleReceiver(mac, latch);
                ContextCompat.registerReceiver(ctx, rx,
                        new IntentFilter(BluetoothDevice.ACTION_BOND_STATE_CHANGED),
                        ContextCompat.RECEIVER_NOT_EXPORTED);
                try {
                    log.accept("removing stale bond…");
                    // Hidden-but-greylisted API; there is no public SDK equivalent for
                    // programmatic unpair, and PFTP recovery is impossible without it.
                    dev.getClass().getMethod("removeBond").invoke(dev);
                    latch.await(10, TimeUnit.SECONDS);
                } finally {
                    try { ctx.unregisterReceiver(rx); } catch (Exception ignored) {}
                }
                if (dev.getBondState() != BluetoothDevice.BOND_NONE) {
                    log.accept("bond did not clear (state=" + dev.getBondState() + ")");
                    return false;
                }
            }
            ensureBonded(ctx, mac, log);
            return dev.getBondState() == BluetoothDevice.BOND_BONDED;
        } catch (Throwable t) {
            log.accept("rebond failed: " + t.getMessage());
            return false;
        }
    }

    private static BluetoothDevice remoteDevice(Context ctx, String mac) {
        BluetoothManager bm = (BluetoothManager) ctx.getSystemService(Context.BLUETOOTH_SERVICE);
        BluetoothAdapter ad = bm != null ? bm.getAdapter() : null;
        return ad != null ? ad.getRemoteDevice(mac) : null;
    }

    /** Counts down when a bond-state broadcast for {@code mac} reaches a settled state
     *  (BONDED or NONE — BONDING is transitional and keeps the wait alive). */
    private static BroadcastReceiver settleReceiver(String mac, CountDownLatch latch) {
        return new BroadcastReceiver() {
            @Override public void onReceive(Context c, Intent i) {
                BluetoothDevice d = i.getParcelableExtra(BluetoothDevice.EXTRA_DEVICE);
                if (d != null && mac.equalsIgnoreCase(d.getAddress())) {
                    int st = i.getIntExtra(BluetoothDevice.EXTRA_BOND_STATE, BluetoothDevice.BOND_NONE);
                    if (st == BluetoothDevice.BOND_BONDED || st == BluetoothDevice.BOND_NONE) latch.countDown();
                }
            }
        };
    }
}
