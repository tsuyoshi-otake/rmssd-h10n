package dev.otake.rmssdh10n;

import android.annotation.SuppressLint;
import android.bluetooth.BluetoothAdapter;
import android.bluetooth.BluetoothManager;
import android.bluetooth.le.BluetoothLeScanner;
import android.bluetooth.le.ScanCallback;
import android.bluetooth.le.ScanFilter;
import android.bluetooth.le.ScanResult;
import android.bluetooth.le.ScanSettings;
import android.content.Context;
import android.os.PowerManager;

import java.util.Collections;
import java.util.List;
import java.util.Locale;
import java.util.function.Consumer;

/**
 * Looks for the H10 while the connection supervisor stands by in a backoff, so a sensor that
 * comes back into range is connected at once instead of after the rest of the backoff (#31).
 * The scan is filtered to the H10's address, which the Bluetooth controller matches itself:
 * it keeps running with the screen off and wakes the CPU only on a match, at the low-power
 * duty cycle (#32). The first match stops the scan and is reported once; a short wake lock
 * keeps the CPU up until the report reaches the control lane, which then holds the CPU itself.
 */
final class PresenceWatch {
    private static final long HOLD_MS = 10_000;
    private final Context ctx;
    private final String mac;
    private final Runnable onSeen;
    private final Consumer<String> log;
    private final PowerManager.WakeLock hold;
    private ScanCallback active;
    private boolean closed;

    PresenceWatch(Context ctx, String mac, Runnable onSeen, Consumer<String> log) {
        this.ctx = ctx;
        this.mac = mac == null ? "" : mac.toUpperCase(Locale.ROOT);
        this.onSeen = onSeen;
        this.log = log;
        PowerManager pm = ctx.getSystemService(PowerManager.class);
        hold = pm != null ? pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "rmssd:presence") : null;
        if (hold != null) hold.setReferenceCounted(false);
    }

    @SuppressLint("MissingPermission") // BLUETOOTH_SCAN is requested at runtime in MainActivity
    synchronized void start() {
        if (closed || active != null) return;
        BluetoothLeScanner scanner = scanner();
        if (scanner == null || !BluetoothAdapter.checkBluetoothAddress(mac)) {
            log.accept("presence watch unavailable");
            return;
        }
        ScanCallback cb = new ScanCallback() {
            @Override public void onScanResult(int callbackType, ScanResult result) { seen(this); }
            @Override public void onBatchScanResults(List<ScanResult> results) {
                if (!results.isEmpty()) seen(this);
            }
            @Override public void onScanFailed(int errorCode) {
                log.accept("presence scan failed: " + errorCode);
                synchronized (PresenceWatch.this) { if (active == this) active = null; }
            }
        };
        try {
            scanner.startScan(
                    Collections.singletonList(new ScanFilter.Builder().setDeviceAddress(mac).build()),
                    new ScanSettings.Builder().setScanMode(ScanSettings.SCAN_MODE_LOW_POWER).build(),
                    cb);
            active = cb;
            log.accept("presence watch on");
        } catch (Throwable t) {
            log.accept("presence watch not started: " + t.getMessage());
        }
    }

    synchronized void stop() { stopScan(); }

    /** Stops for good: a start racing a teardown does nothing. */
    synchronized void close() { closed = true; stopScan(); }

    private void seen(ScanCallback cb) {
        synchronized (this) {
            if (active != cb) return;   // stopped meanwhile, or a result after the first
            stopScan();
        }
        if (hold != null) hold.acquire(HOLD_MS);
        log.accept("presence: H10 seen");
        onSeen.run();
    }

    @SuppressLint("MissingPermission")
    private void stopScan() {
        ScanCallback cb = active;
        active = null;
        if (cb == null) return;
        try {
            BluetoothLeScanner scanner = scanner();
            if (scanner != null) scanner.stopScan(cb);
        } catch (Throwable ignored) {}   // the adapter went off: the scan is gone with it
    }

    private BluetoothLeScanner scanner() {
        BluetoothManager bm = ctx.getSystemService(BluetoothManager.class);
        BluetoothAdapter ad = bm != null ? bm.getAdapter() : null;
        return ad != null && ad.isEnabled() ? ad.getBluetoothLeScanner() : null;
    }
}
