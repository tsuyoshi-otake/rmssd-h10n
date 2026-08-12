package dev.otake.rmssdh10n;

import android.annotation.SuppressLint;
import android.bluetooth.BluetoothAdapter;
import android.bluetooth.BluetoothDevice;
import android.bluetooth.BluetoothManager;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;

import androidx.core.content.ContextCompat;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;

/**
 * OS-level BLE bonding for the H10, performed BEFORE the SDK connects. The H10's PFTP
 * (recording / time) characteristics need an encrypted link, so the device requests
 * pairing on connect — but the SDK's 10 s feature-check disconnects mid-SMP
 * (SMP_CONN_TOUT), so the in-connect pairing never completes. Bonding here first (no SDK
 * connection, no 10 s clock) lets SMP finish. No-op when already bonded, so it can't
 * regress the working path; best-effort otherwise (we still try to connect if it fails).
 */
final class PolarBonding {
    private PolarBonding() {}

    @SuppressLint("MissingPermission") // BLUETOOTH_CONNECT is requested at runtime in MainActivity
    static void ensureBonded(Context ctx, String mac, Consumer<String> log) {
        try {
            BluetoothDevice dev = remoteDevice(ctx, mac);
            if (dev == null) return;
            if (dev.getBondState() == BluetoothDevice.BOND_BONDED) return; // already paired — nothing to do
            final CountDownLatch latch = new CountDownLatch(1);
            BroadcastReceiver rx = settleReceiver(mac, latch);
            ContextCompat.registerReceiver(ctx, rx,
                    new IntentFilter(BluetoothDevice.ACTION_BOND_STATE_CHANGED),
                    ContextCompat.RECEIVER_NOT_EXPORTED);
            try {
                if (dev.getBondState() == BluetoothDevice.BOND_BONDING) {
                    // An SMP exchange from a previous attempt is still in flight — createBond()
                    // would just return false, so wait for it to settle instead.
                    log.accept("bond already in progress — waiting…");
                } else {
                    log.accept("bonding H10 (createBond)…");
                    if (!dev.createBond()) log.accept("createBond() returned false");
                }
                latch.await(30, TimeUnit.SECONDS);
            } finally {
                try { ctx.unregisterReceiver(rx); } catch (Exception ignored) {}
            }
            log.accept("bond state after attempt: " + dev.getBondState());
        } catch (Throwable t) {
            log.accept("ensureBonded failed: " + t.getMessage());
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
