package dev.otake.rmssdh10n;

import android.content.Context;
import android.util.Log;

import androidx.core.util.Pair;

import com.polar.sdk.api.PolarBleApi;
import com.polar.sdk.api.PolarBleApiCallback;
import com.polar.sdk.api.PolarBleApiDefaultImpl;
import com.polar.sdk.api.PolarH10OfflineExerciseApi;
import com.polar.sdk.api.model.PolarAccelerometerData;
import com.polar.sdk.api.model.PolarDeviceInfo;
import com.polar.sdk.api.model.PolarExerciseData;
import com.polar.sdk.api.model.PolarExerciseEntry;
import com.polar.sdk.api.model.PolarHrData;
import com.polar.sdk.api.model.PolarSensorSetting;

import java.time.LocalDateTime;
import java.util.EnumSet;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

import io.reactivex.rxjava3.disposables.Disposable;

/**
 * Polar BLE SDK driver — drop-in replacement for {@link BleNative} that keeps the
 * exact same {@link Sink} contract so {@link HrvEngine} is unchanged. It adds what
 * raw GATT could not: the H10's onboard <b>RR exercise recording</b>, used to
 * backfill the measurement gap created while the phone is away / out of range.
 *
 * Live: HR+RR via {@code startHrStreaming} (PolarHrData.rrsMs, ms), ACC 25 Hz via
 * {@code startAccStreaming} (samples are milli-g, matching {@link
 * dev.otake.rmssdh10n.hrv.Posture}'s gravity≈1000 expectation — no scaling).
 *
 * Rolling recording (single H10 memory slot):
 *  - first connect of a session: discard any stale recording, then startRecording(RR);
 *  - every reconnect: stop → list → fetch → hand the RR samples to the {@link
 *    RecordingHandler} (which replays + persists them) → remove only after the
 *    handler confirms durable persistence → startRecording fresh;
 *  - clean stop: stop + remove (connected the whole time = live covered it), then
 *    disconnect → shutDown (never orphan the single-connection H10).
 *
 * Reconnection is left to the SDK ({@code setAutomaticReconnection(true)} +
 * {@link #foregroundEntered()} on resume). BLE scan can't restart with the screen
 * off, but that is exactly the case the backfill recovers — a delayed reconnect
 * (e.g. on wake) still gets its gap filled from device memory.
 */
public final class PolarBle {
    private static final String TAG = "PolarBle";
    private static final int OP_TIMEOUT_S = 10;
    private static final int FETCH_TIMEOUT_S = 60;
    private static final int LIST_TIMEOUT_S = 20;

    /** Same shape as {@link BleNative.Sink} so {@link HrvEngine} swaps drivers freely. */
    public interface Sink {
        void onHr(int hr);
        void onRr(double rrMs);
        void onAcc(int x, int y, int z);
        void onConnected(boolean connected);
        void log(String msg);
    }

    /** Called on the worker thread with the RR (ms) fetched from a gap recording and
     *  the wall-clock to end-anchor them to. Must persist durably and return true; the
     *  exercise is removed from the device only on true (so failures can be retried). */
    public interface RecordingHandler {
        boolean onGapRecording(double[] rrMs, long anchorEndMs);
    }

    private final Context ctx;
    private final String id;          // MAC works as a Polar identifier on Android
    private final boolean withAcc;
    private final Sink sink;
    private volatile RecordingHandler recordingHandler;

    private final ScheduledExecutorService exec = Executors.newSingleThreadScheduledExecutor(r -> {
        Thread t = new Thread(r, "polar-ble");
        t.setDaemon(true);
        return t;
    });

    private PolarBleApi api;
    private volatile Disposable hrDis;
    private volatile Disposable accDis;
    private volatile boolean stopping = false;
    private volatile boolean recordingActive = false;
    private boolean weStartedRecording = false;       // exec-thread only
    private volatile long recordingStartedAtMs = 0;   // our-clock start of the active recording (start-anchor)
    // CAS-guarded so duplicate feature-ready callbacks can't queue two recording jobs.
    private final AtomicBoolean recordingHandledThisConn = new AtomicBoolean(false);

    public PolarBle(Context ctx, String mac, boolean withAcc, Sink sink) {
        this.ctx = ctx.getApplicationContext();
        this.id = mac;
        this.withAcc = withAcc;
        this.sink = sink;
    }

    public void setRecordingHandler(RecordingHandler h) { this.recordingHandler = h; }

    /** Whether an RR recording is currently running on the H10 (for the status badge). */
    public boolean isRecording() { return recordingActive; }

    public void start() {
        exec.execute(() -> {
            try {
                Set<PolarBleApi.PolarBleSdkFeature> features = EnumSet.of(
                        PolarBleApi.PolarBleSdkFeature.FEATURE_HR,
                        PolarBleApi.PolarBleSdkFeature.FEATURE_POLAR_ONLINE_STREAMING,
                        PolarBleApi.PolarBleSdkFeature.FEATURE_POLAR_H10_EXERCISE_RECORDING,
                        PolarBleApi.PolarBleSdkFeature.FEATURE_POLAR_DEVICE_TIME_SETUP,
                        PolarBleApi.PolarBleSdkFeature.FEATURE_DEVICE_INFO);
                api = PolarBleApiDefaultImpl.defaultImplementation(ctx, features);
                api.setApiLogger(s -> Log.d(TAG, "[sdk] " + s));
                api.setAutomaticReconnection(true);
                api.setApiCallback(callback);
                sink.log("connecting " + id);
                api.connectToDevice(id);
            } catch (Throwable t) {
                sink.log("start failed: " + t.getMessage());
            }
        });
    }

    /** App returned to foreground — nudge the SDK to restart its BLE scan (it can't
     *  start a scan while the display is off), so a lost connection re-establishes. */
    public void foregroundEntered() {
        PolarBleApi a = api;
        if (a != null) { try { a.foregroundEntered(); } catch (Throwable ignored) {} }
    }

    public void stop() {
        stopping = true;
        recordingActive = false;
        disposeStreams();
        final PolarBleApi a = api;
        api = null;
        // Interrupt any in-flight blocking recording op so teardown is NOT queued
        // behind a multi-second fetch (which would skip disconnect/shutDown).
        exec.shutdownNow();
        if (a == null) return;
        // Tear down on a throwaway thread (never the possibly-busy worker) and ALWAYS
        // disconnect + shutDown, so the single-connection H10 is never orphaned. A
        // leftover recording — we stayed connected, so live covered everything — is
        // cleared by the next session's first-connect discard.
        Thread t = new Thread(() -> {
            try { a.disconnectFromDevice(id); } catch (Throwable ignored) {}
            try { Thread.sleep(400); } catch (InterruptedException ignored) {}
            try { a.shutDown(); } catch (Throwable ignored) {}
        }, "polar-stop");
        t.setDaemon(true);
        t.start();
        try { t.join(2500); } catch (InterruptedException ignored) {}
    }

    // --- SDK callback -------------------------------------------------------
    private final PolarBleApiCallback callback = new PolarBleApiCallback() {
        @Override public void deviceConnected(PolarDeviceInfo info) {
            recordingHandledThisConn.set(false);
            sink.onConnected(true);
            sink.log("connected " + info.getDeviceId());
        }
        @Override public void deviceConnecting(PolarDeviceInfo info) { sink.log("connecting…"); }
        @Override public void deviceDisconnected(PolarDeviceInfo info) {
            sink.onConnected(false);
            recordingHandledThisConn.set(false);
            disposeStreams();
            sink.log("disconnected");
        }
        @Override public void bleSdkFeatureReady(String identifier, PolarBleApi.PolarBleSdkFeature feature) {
            if (stopping) return;
            switch (feature) {
                case FEATURE_HR:
                    startHr();
                    break;
                case FEATURE_POLAR_ONLINE_STREAMING:
                    if (withAcc) startAcc();
                    break;
                case FEATURE_POLAR_H10_EXERCISE_RECORDING:
                    if (recordingHandledThisConn.compareAndSet(false, true)) {
                        exec.execute(PolarBle.this::runRecordingOnConnect);
                    }
                    break;
                default:
                    break;
            }
        }
        // Required abstract overrides for features we don't use (HTS thermometer +
        // the structured DIS-info callback); PolarBleApiCallback leaves these two open.
        @Override public void htsNotificationReceived(String identifier,
                com.polar.sdk.api.model.PolarHealthThermometerData data) { }
        @Override public void disInformationReceived(String identifier,
                com.polar.androidcommunications.api.ble.model.DisInfo disInfo) { }
    };

    // --- live streams (non-blocking; emit on the SDK thread into the Sink) --
    private void startHr() {
        if (hrDis != null && !hrDis.isDisposed()) return;
        hrDis = api.startHrStreaming(id).subscribe(
                data -> {
                    for (PolarHrData.PolarHrSample s : data.getSamples()) {
                        sink.onHr(s.getHr());
                        for (Integer rr : s.getRrsMs()) sink.onRr(rr.doubleValue());
                    }
                },
                err -> { sink.log("hr stream err: " + err.getMessage()); hrDis = null; });
    }

    private void startAcc() {
        if (accDis != null && !accDis.isDisposed()) return;
        try {
            Map<PolarSensorSetting.SettingType, Integer> m = new HashMap<>();
            m.put(PolarSensorSetting.SettingType.SAMPLE_RATE, 25);
            m.put(PolarSensorSetting.SettingType.RESOLUTION, 16);
            m.put(PolarSensorSetting.SettingType.RANGE, 2);
            m.put(PolarSensorSetting.SettingType.CHANNELS, 3);
            PolarSensorSetting setting = new PolarSensorSetting(m);
            accDis = api.startAccStreaming(id, setting).subscribe(
                    data -> {
                        for (PolarAccelerometerData.PolarAccelerometerDataSample s : data.getSamples()) {
                            sink.onAcc(s.getX(), s.getY(), s.getZ());
                        }
                    },
                    err -> { sink.log("acc stream err: " + err.getMessage()); accDis = null; });
            sink.log("ACC 25Hz started");
        } catch (Throwable t) {
            sink.log("acc start failed: " + t.getMessage());
        }
    }

    private void disposeStreams() {
        Disposable h = hrDis, a = accDis;
        hrDis = null; accDis = null;
        if (h != null) { try { h.dispose(); } catch (Throwable ignored) {} }
        if (a != null) { try { a.dispose(); } catch (Throwable ignored) {} }
    }

    // --- recording lifecycle (exec thread; blocking RxJava is fine here) ----
    private void runRecordingOnConnect() {
        if (stopping || api == null) return;
        try {
            if (!weStartedRecording) {
                // First connect this session: a recording may linger from a crash —
                // its timing can't be trusted relative to now, so discard it.
                try {
                    Pair<Boolean, String> st = api.requestRecordingStatus(id)
                            .timeout(OP_TIMEOUT_S, TimeUnit.SECONDS).blockingGet();
                    if (st != null && Boolean.TRUE.equals(st.first)) {
                        try { api.stopRecording(id).timeout(OP_TIMEOUT_S, TimeUnit.SECONDS).blockingAwait(); }
                        catch (Throwable ignored) {}
                    }
                } catch (Throwable t) { sink.log("recording status failed: " + t.getMessage()); }
                removeAllExercises();
                startFreshRecording();
            } else {
                // Reconnect: our recording captured the gap. Stop, fetch, backfill, restart.
                // Anchor the replay to OUR clock time when this recording started (set in
                // startFreshRecording) — accurate even if the H10 auto-stopped on full
                // memory, and independent of the device's unreliable RTC.
                long gapStart = recordingStartedAtMs;
                try { api.stopRecording(id).timeout(OP_TIMEOUT_S, TimeUnit.SECONDS).blockingAwait(); }
                catch (Throwable ignored) {}   // may already be stopped (memory full) — fetch anyway
                recordingActive = false;
                fetchAndBackfill(gapStart);
                startFreshRecording();
            }
        } catch (Throwable t) {
            sink.log("recording-on-connect failed: " + t.getMessage());
        }
    }

    private void startFreshRecording() {
        if (stopping || api == null) return;
        try { api.setLocalTime(id, LocalDateTime.now()).timeout(OP_TIMEOUT_S, TimeUnit.SECONDS).blockingAwait(); }
        catch (Throwable t) { sink.log("setLocalTime failed (continuing): " + t.getMessage()); }
        try {
            String exId = "rmssd-" + System.currentTimeMillis();
            api.startRecording(id, exId,
                    PolarH10OfflineExerciseApi.RecordingInterval.INTERVAL_1S,
                    PolarH10OfflineExerciseApi.SampleType.RR)
                    .timeout(OP_TIMEOUT_S, TimeUnit.SECONDS).blockingAwait();
            weStartedRecording = true;
            recordingActive = true;
            recordingStartedAtMs = System.currentTimeMillis(); // start-anchor for this recording's backfill
            sink.log("RR recording started");
        } catch (Throwable t) {
            recordingActive = false;
            sink.log("startRecording failed: " + t.getMessage());
        }
    }

    private void fetchAndBackfill(long anchorStartMs) {
        if (anchorStartMs <= 0) { sink.log("backfill: no valid start anchor; skipping"); return; }
        try {
            List<PolarExerciseEntry> entries = api.listExercises(id)
                    .toList().timeout(LIST_TIMEOUT_S, TimeUnit.SECONDS).blockingGet();
            if (entries == null || entries.isEmpty()) { sink.log("backfill: no exercise stored"); return; }
            PolarExerciseEntry entry = entries.get(0);   // H10 holds a single recording
            PolarExerciseData data = api.fetchExercise(id, entry)
                    .timeout(FETCH_TIMEOUT_S, TimeUnit.SECONDS).blockingGet();
            List<Integer> samples = data != null ? data.getHrSamples() : null; // RR ms (recorded as RR)
            int n = samples != null ? samples.size() : 0;
            boolean persisted = true;
            RecordingHandler h = recordingHandler;
            if (h != null && n >= 2) {
                double[] rr = new double[n];
                for (int i = 0; i < n; i++) rr[i] = samples.get(i);
                persisted = h.onGapRecording(rr, anchorStartMs);
            } else {
                sink.log("backfill: " + n + " samples (nothing to replay)");
            }
            // Remove only after the handler confirmed durable persistence.
            if (persisted) {
                try { api.removeExercise(id, entry).timeout(OP_TIMEOUT_S, TimeUnit.SECONDS).blockingAwait(); }
                catch (Throwable t) { sink.log("removeExercise failed: " + t.getMessage()); }
            } else {
                sink.log("backfill not persisted; keeping exercise for retry");
            }
        } catch (Throwable t) {
            sink.log("fetchAndBackfill failed: " + t.getMessage());
        }
    }

    private void removeAllExercises() {
        try {
            List<PolarExerciseEntry> entries = api.listExercises(id)
                    .toList().timeout(LIST_TIMEOUT_S, TimeUnit.SECONDS).blockingGet();
            if (entries == null || entries.isEmpty()) return;
            for (PolarExerciseEntry e : entries) {
                try { api.removeExercise(id, e).timeout(OP_TIMEOUT_S, TimeUnit.SECONDS).blockingAwait(); }
                catch (Throwable t) { sink.log("remove stale failed: " + t.getMessage()); }
            }
            sink.log("cleared " + entries.size() + " stale exercise(s)");
        } catch (Throwable t) {
            sink.log("removeAllExercises failed: " + t.getMessage());
        }
    }
}
