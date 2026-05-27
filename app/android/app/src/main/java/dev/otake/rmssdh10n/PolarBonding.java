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
            BluetoothManager bm = (BluetoothManager) ctx.getSystemService(Context.BLUETOOTH_SERVICE);
            BluetoothAdapter ad = bm != null ? bm.getAdapter() : null;
            if (ad == null) return;
            BluetoothDevice dev = ad.getRemoteDevice(mac);
            if (dev.getBondState() == BluetoothDevice.BOND_BONDED) return; // already paired — nothing to do
            final CountDownLatch latch = new CountDownLatch(1);
            BroadcastReceiver rx = new BroadcastReceiver() {
                @Override public void onReceive(Context c, Intent i) {
                    BluetoothDevice d = i.getParcelableExtra(BluetoothDevice.EXTRA_DEVICE);
                    if (d != null && mac.equalsIgnoreCase(d.getAddress())) {
                        int st = i.getIntExtra(BluetoothDevice.EXTRA_BOND_STATE, BluetoothDevice.BOND_NONE);
                        if (st == BluetoothDevice.BOND_BONDED || st == BluetoothDevice.BOND_NONE) latch.countDown();
                    }
                }
            };
            ContextCompat.registerReceiver(ctx, rx,
                    new IntentFilter(BluetoothDevice.ACTION_BOND_STATE_CHANGED),
                    ContextCompat.RECEIVER_NOT_EXPORTED);
            try {
                log.accept("bonding H10 (createBond)…");
                if (!dev.createBond()) log.accept("createBond() returned false");
                latch.await(30, TimeUnit.SECONDS);
            } finally {
                try { ctx.unregisterReceiver(rx); } catch (Exception ignored) {}
            }
            log.accept("bond state after attempt: " + dev.getBondState());
        } catch (Throwable t) {
            log.accept("ensureBonded failed: " + t.getMessage());
        }
    }
}
