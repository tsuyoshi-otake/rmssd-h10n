package dev.otake.rmssdh10n;

import android.annotation.SuppressLint;
import android.bluetooth.BluetoothAdapter;
import android.bluetooth.BluetoothDevice;
import android.bluetooth.BluetoothGatt;
import android.bluetooth.BluetoothGattCallback;
import android.bluetooth.BluetoothGattCharacteristic;
import android.bluetooth.BluetoothGattDescriptor;
import android.bluetooth.BluetoothGattService;
import android.bluetooth.BluetoothManager;
import android.bluetooth.BluetoothProfile;
import android.content.Context;
import android.os.Handler;
import android.os.HandlerThread;

import java.util.ArrayDeque;
import java.util.UUID;

import dev.otake.rmssdh10n.hrv.Acc;
import dev.otake.rmssdh10n.hrv.Hrm;

/**
 * Native BLE engine for the Polar H10 — the service-side counterpart of
 * app/src/ble-hr.js, so the HR/RR (and optional ACC) stream keeps flowing while
 * the screen is off (no WebView, no Capacitor plugin, no JS timers involved).
 *
 * Connects directly by MAC (no scan), subscribes to HR Measurement (0x2A37),
 * and — when {@code withAcc} — starts the PMD ACC stream (data notify + control
 * indicate + START write) through a strict serial GATT operation queue. A
 * generation counter drops late callbacks from a previous connection so a
 * reconnect or stop can never be corrupted by a stale event. Reconnect uses an
 * exponential backoff (5→10→20→30 s) and keeps the saved address (the picker is
 * useless with the screen off).
 */
public final class BleNative {
    public interface Sink {
        void onHr(int hr);
        void onRr(double rrMs);
        void onAcc(int x, int y, int z);
        void onConnected(boolean connected);
        void log(String msg);
    }

    private static final UUID HR_SERVICE = UUID.fromString("0000180d-0000-1000-8000-00805f9b34fb");
    private static final UUID HR_MEAS    = UUID.fromString("00002a37-0000-1000-8000-00805f9b34fb");
    private static final UUID CCCD       = UUID.fromString("00002902-0000-1000-8000-00805f9b34fb");
    private static final UUID PMD_SERVICE = UUID.fromString(Acc.PMD_SERVICE);
    private static final UUID PMD_CONTROL = UUID.fromString(Acc.PMD_CONTROL);
    private static final UUID PMD_DATA    = UUID.fromString(Acc.PMD_DATA);

    private final Context ctx;
    private final String mac;
    private final boolean withAcc;
    private final Sink sink;

    private final HandlerThread thread;
    private final Handler handler;
    private BluetoothGatt gatt;
    private int generation = 0;          // bumped per connect attempt and on stop
    private boolean stopping = false;
    private int failures = 0;

    // Serial GATT op queue (only one outstanding write at a time).
    private final ArrayDeque<Runnable> ops = new ArrayDeque<>();
    private boolean opInFlight = false;

    public BleNative(Context ctx, String mac, boolean withAcc, Sink sink) {
        this.ctx = ctx.getApplicationContext();
        this.mac = mac;
        this.withAcc = withAcc;
        this.sink = sink;
        this.thread = new HandlerThread("ble-native");
        this.thread.start();
        this.handler = new Handler(thread.getLooper());
    }

    public void start() {
        handler.post(() -> { stopping = false; failures = 0; connect(); });
    }

    public void stop() {
        handler.post(() -> {
            stopping = true;
            generation++;            // invalidate any in-flight callbacks
            teardownGatt();
        });
        // Give the disconnect a moment, then end the worker thread.
        handler.postDelayed(thread::quitSafely, 400);
    }

    private void teardownGatt() {
        ops.clear();
        opInFlight = false;
        if (gatt != null) {
            try { gatt.disconnect(); } catch (Exception ignored) {}
            try { gatt.close(); } catch (Exception ignored) {}
            gatt = null;
        }
    }

    @SuppressLint("MissingPermission")
    private void connect() {
        if (stopping) return;
        BluetoothManager bm = (BluetoothManager) ctx.getSystemService(Context.BLUETOOTH_SERVICE);
        BluetoothAdapter adapter = bm != null ? bm.getAdapter() : null;
        if (adapter == null || !adapter.isEnabled()) {
            sink.log("BT adapter off; retry in 5s");
            scheduleReconnect();
            return;
        }
        final int gen = ++generation;
        try {
            BluetoothDevice dev = adapter.getRemoteDevice(mac);
            sink.log("connecting " + mac + " (gen " + gen + ")");
            gatt = dev.connectGatt(ctx, false, callbackFor(gen), BluetoothDevice.TRANSPORT_LE);
        } catch (Exception e) {
            sink.log("connectGatt failed: " + e.getMessage());
            scheduleReconnect();
        }
    }

    private void scheduleReconnect() {
        if (stopping) return;
        failures++;
        long wait = Math.min(30000, 5000L * (1L << Math.min(failures - 1, 3))); // 5,10,20,30s
        sink.onConnected(false);
        teardownGatt();
        handler.postDelayed(this::connect, wait);
    }

    // --- GATT serial queue --------------------------------------------------
    private void enqueue(Runnable op) { handler.post(() -> { ops.add(op); drain(); }); }

    private void drain() {
        if (opInFlight || ops.isEmpty()) return;
        opInFlight = true;
        Runnable op = ops.poll();
        try { op.run(); } catch (Exception e) { sink.log("op error: " + e.getMessage()); opInFlight = false; drain(); }
    }

    private void opDone() { handler.post(() -> { opInFlight = false; drain(); }); }

    @SuppressLint("MissingPermission")
    private void enableNotify(BluetoothGattCharacteristic ch, boolean indicate) {
        enqueue(() -> {
            if (gatt == null || ch == null) { opDone(); return; }
            gatt.setCharacteristicNotification(ch, true);
            BluetoothGattDescriptor cccd = ch.getDescriptor(CCCD);
            if (cccd == null) { opDone(); return; }
            cccd.setValue(indicate
                    ? BluetoothGattDescriptor.ENABLE_INDICATION_VALUE
                    : BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE);
            if (!gatt.writeDescriptor(cccd)) opDone(); // completion → onDescriptorWrite
        });
    }

    @SuppressLint("MissingPermission")
    private void writeControl(byte[] value) {
        enqueue(() -> {
            if (gatt == null) { opDone(); return; }
            BluetoothGattService svc = gatt.getService(PMD_SERVICE);
            BluetoothGattCharacteristic ctl = svc != null ? svc.getCharacteristic(PMD_CONTROL) : null;
            if (ctl == null) { opDone(); return; }
            ctl.setValue(value);
            ctl.setWriteType(BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT);
            if (!gatt.writeCharacteristic(ctl)) opDone(); // completion → onCharacteristicWrite
        });
    }

    @SuppressLint("MissingPermission")
    private BluetoothGattCallback callbackFor(final int gen) {
        return new BluetoothGattCallback() {
            @Override public void onConnectionStateChange(BluetoothGatt g, int status, int newState) {
                if (gen != generation) return; // stale
                if (newState == BluetoothProfile.STATE_CONNECTED) {
                    sink.log("connected; discovering services");
                    failures = 0;
                    g.discoverServices();
                } else if (newState == BluetoothProfile.STATE_DISCONNECTED) {
                    sink.log("disconnected (status " + status + ")");
                    scheduleReconnect();
                }
            }

            @Override public void onServicesDiscovered(BluetoothGatt g, int status) {
                if (gen != generation) return;
                if (status != BluetoothGatt.GATT_SUCCESS) { sink.log("discover failed " + status); scheduleReconnect(); return; }
                BluetoothGattService hr = g.getService(HR_SERVICE);
                BluetoothGattCharacteristic meas = hr != null ? hr.getCharacteristic(HR_MEAS) : null;
                if (meas == null) { sink.log("HR char missing"); scheduleReconnect(); return; }
                enableNotify(meas, false);
                sink.onConnected(true);
                if (withAcc) {
                    // data notify → control indicate → START write (order matters).
                    BluetoothGattService pmd = g.getService(PMD_SERVICE);
                    if (pmd != null) {
                        BluetoothGattCharacteristic data = pmd.getCharacteristic(PMD_DATA);
                        BluetoothGattCharacteristic ctl = pmd.getCharacteristic(PMD_CONTROL);
                        if (data != null) enableNotify(data, false);
                        if (ctl != null) enableNotify(ctl, true);
                        writeControl(Acc.ACC_START_COMMAND);
                        sink.log("PMD ACC start queued");
                    } else {
                        sink.log("PMD service missing; HR/RR only");
                    }
                }
            }

            @Override public void onDescriptorWrite(BluetoothGatt g, BluetoothGattDescriptor d, int status) {
                if (gen != generation) return;
                opDone();
            }

            @Override public void onCharacteristicWrite(BluetoothGatt g, BluetoothGattCharacteristic c, int status) {
                if (gen != generation) return;
                opDone();
            }

            @Override public void onCharacteristicChanged(BluetoothGatt g, BluetoothGattCharacteristic c) {
                if (gen != generation) return;
                byte[] v = c.getValue();
                if (v == null) return;
                UUID u = c.getUuid();
                if (HR_MEAS.equals(u)) {
                    Hrm.Result r = Hrm.parse(v);
                    if (r.hr >= 0) sink.onHr(r.hr);
                    for (double rr : r.rr) sink.onRr(rr);
                } else if (PMD_DATA.equals(u)) {
                    Acc.Frame f = Acc.parse(v);
                    if (f != null) for (Acc.Sample s : f.samples) sink.onAcc(s.x, s.y, s.z);
                }
            }
        };
    }
}
