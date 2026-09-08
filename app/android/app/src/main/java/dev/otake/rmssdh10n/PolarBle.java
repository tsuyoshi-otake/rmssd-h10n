package dev.otake.rmssdh10n;

import android.content.Context;
import android.util.Log;

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
import java.util.Collections;
import java.util.EnumSet;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

import io.reactivex.rxjava3.disposables.Disposable;

/**
 * Polar BLE SDK driver. Exposes the {@link Sink} contract that decouples
 * {@link HrvEngine} from the BLE layer. It adds what
 * raw GATT could not: the H10's onboard <b>RR exercise recording</b>, used to
 * backfill the measurement gap created while the phone is away / out of range.
 *
 * Live: HR+RR via {@code startHrStreaming} (PolarHrData.rrsMs, ms), ACC 25 Hz via
 * {@code startAccStreaming} (samples are milli-g, matching {@link
 * dev.otake.rmssdh10n.hrv.Posture}'s gravity≈1000 expectation — no scaling).
 *
 * Rolling recording (single H10 memory slot), driven by DB-persisted state via
 * {@link RecordingStore} so it survives an app/OS restart (not just same-process
 * reconnects):
 *  - on connect, if the DB has an open recording (start-anchor + exId), RECOVER it:
 *    match the on-device exercise by identifier → stop → fetch → replay+persist →
 *    remove only after durable persistence → startRecording fresh. On a fetch
 *    failure we do NOT start fresh (that would overwrite the single slot before the
 *    gap is recovered) — we retry. PFTP ops are deferred + retried (the H10 returns
 *    OPERATION_NOT_PERMITTED right after connect while streams settle);
 *  - no open recording: an EMPTY slot or our own un-anchored leftovers → start fresh;
 *    a FOREIGN recording (e.g. Polar Beat) is never auto-deleted — surfaced instead;
 *  - clean (user) stop marks the recording discarded_by_user so it is NOT recovered;
 *    an OS kill leaves it active so the next launch recovers the gap.
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
    private static final int RECORDING_DEFER_S = 8;      // let live HR/ACC settle before PFTP
    private static final int RETRY_DELAY_S = 5;          // spacing for transient PFTP 106 retries
    private static final int MAX_RECORDING_ATTEMPTS = 6; // bound the recover/start retry loop
    private static final int MAX_RECOVERY_RECONNECTS = 2;// bounded forced reconnects when PFTP stays wedged on a link
    private static final int MAX_CONNECT_ATTEMPTS = 5;   // bounded re-issues when connectToDevice throws
    private static final long RR_CAP = 95000L;           // ~H10 RR memory limit (truncation heuristic)
    private static final long FULL_DURATION_MS = 18L * 3600 * 1000; // ~18 h
    private static final long TRUNCATE_TAIL_MS = 5L * 60 * 1000;    // unrecorded tail ⇒ memory-full auto-stop

    // ACC duty-cycle (power saving): 25 Hz is the H10's MINIMUM accelerometer rate, so we
    // lower its effective frequency by streaming in short bursts. ~17% duty ⇒ ACC on-time
    // (and its BLE traffic) cut ~83%; posture refreshes each burst, steps omitted. The mode
    // is a runtime toggle (accDutyCycle, set by the dashboard's 省電力 switch); default ON.
    private static final long ACC_DUTY_ON_MS = 5000;       // stream ACC this long...
    private static final long ACC_DUTY_PERIOD_MS = 30000;  // ...once per this period
    private static final String EX_PREFIX = "rmssd-";    // identifier prefix of recordings we own

    /** BLE-layer contract consumed by {@link HrvEngine} (decouples driver from engine). */
    public interface Sink {
        void onHr(int hr);
        void onRr(double rrMs);
        void onAcc(int x, int y, int z);
        void onConnected(boolean connected);
        void onBattery(int level);
        void log(String msg);
        /** Structured, bounded connection/recovery telemetry. Device identifiers and RR
         *  payloads must never be placed in these fields. */
        default void onStage(String stage, String reason, int attempt, long nextRetryAt) {}
    }

    /** Persistence + recovery hooks, implemented by {@link HrvEngine} over {@link HrvDb}.
     *  Keeps the single-slot H10 recording recoverable across an app/OS restart: the
     *  start-anchor and lifecycle state live in the DB, so on reconnect — even in a
     *  brand-new process — the lingering recording is RECOVERED, not discarded. All
     *  calls run on the worker thread. */
    public interface RecordingStore {
        /** Most recent recoverable recording (not removed / not user-discarded), or null. */
        OpenRec getOpenRecording();
        /** Persist 'starting' BEFORE startRecording is issued (start-anchor = startRequestMs). */
        void recStarting(String exId, long startRequestMs);
        /** Recording acknowledged by the H10. A timeout-reconciled start keeps an
         *  explicit uncertain-anchor state so fetched RR is quarantined, not mis-timed. */
        void recActive(String exId, long startAckMs, boolean uncertainAnchor);
        /** Fetched from the device → stamp counts + truncation, state 'fetching'. */
        void recFetching(String exId, long rrCount, long durationMs, boolean truncated);
        /** Replay + durably persist the gap RR (+ ledger), or quarantine the raw RR when it
         *  cannot be placed safely. The device exercise is removed only after a durable result. */
        PersistResult recPersistGap(double[] rrMs, long anchorStartMs, String exId, boolean truncated);
        /** Durably quarantine fetched RR whose exact wall-clock anchor cannot be proven. */
        PersistResult recPersistUncertainGap(double[] rrMs, long anchorStartMs, String exId);
        /** Device exercise removed → 'removed' (terminal). */
        void recRemoved(String exId);
        /** True only for an exact recording explicitly discarded by the user. */
        boolean canRemoveDiscarded(String exId);

        enum PersistResult {
            COMMITTED, ALREADY_COMMITTED, QUARANTINED, FAILED;
            public boolean isDurable() { return this != FAILED; }
        }

        /** Minimal view of a recoverable recording. */
        final class OpenRec {
            public final String exId, state;
            public final long anchorStartMs;
            public OpenRec(String exId, long anchorStartMs, String state) {
                this.exId = exId; this.anchorStartMs = anchorStartMs; this.state = state;
            }
        }
    }

    private final Context ctx;
    private final String id;          // MAC works as a Polar identifier on Android
    private final boolean withAcc;
    private final Sink sink;
    private volatile RecordingStore recordingStore;

    private final BleControlQueue work = new BleControlQueue();
    private final ScheduledExecutorService exec = work.control();
    // PFTP fetch/list/start can block for up to 60 s. Keep those waits off the live-control
    // executor so stop/reconnect/ACC mode changes retain a bounded response time. PFTP stays
    // strictly serial: this is separation of queues, not parallel GATT traffic.
    private final ScheduledExecutorService recordingExec = work.recording();

    private final LifecycleSlot<PolarBleApi> apiSlot = new LifecycleSlot<>();
    private PolarBleApi api() { return apiSlot.get(); }
    private volatile Disposable hrDis;
    private volatile Disposable accDis;
    private volatile Disposable selectionScanDis;
    private final AtomicBoolean selectionScanActive = new AtomicBoolean(false);
    private final AccStreamSchedule accSchedule = new AccStreamSchedule(exec);
    private volatile boolean accDutyCycle = false;     // power-save ACC mode (runtime-toggleable); true = burst-sample. Default OFF = continuous
    private volatile boolean accEnabled = true;        // 姿勢推定トグル: false = ACC never streams (biggest H10 battery saving)
    private volatile boolean stopping = false;
    private volatile boolean recordingActive = false;
    private volatile boolean linkConnected = false;   // BLE link up — gates PFTP ops
    private volatile boolean externalBlocking = false;// a non-owned recording occupies the slot
    private int recordingAttempt = 0;                 // recording-worker only — PFTP retry counter
    private int recoveryReconnects = 0;               // recording-worker only — bounded forced reconnects on stuck PFTP
    private boolean rebondTried = false;              // recording-worker only — one OS bond reset per stuck episode
    private long lastBondPromptAt = 0;                // recording-worker only — rate-limits OS pairing dialogs
    private static final long BOND_PROMPT_MIN_MS = 10 * 60_000L; // one pairing prompt per 10 min at most
    // Engine-provided "is live RR actually stale right now?" — consulted at the moment a
    // watchdog action RUNS (exec can lag behind the tick that queued it by a whole PFTP op).
    private volatile java.util.function.BooleanSupplier rrStale = () -> true;
    // CAS-guarded so duplicate feature-ready callbacks can't queue two recording jobs.
    private final AtomicBoolean recordingHandledThisConn = new AtomicBoolean(false);
    // Bumped on every (re)connect. The recording job + its retries capture the gen they
    // belong to and bail if a newer connection has superseded them — so a retry scheduled
    // under a dropped connection never runs against a fresh one (the counters above are
    // reset per-connection on exec, keeping them exec-thread-confined).
    private final AtomicInteger connGen = new AtomicInteger(0);
    private final AtomicInteger streamGen = new AtomicInteger(0);
    // Serializes the live-stream subscription lifecycle (start/dispose). bleSdkFeatureReady
    // fires on the SDK callback thread while nudgeStreams runs on exec; without this the
    // check-then-set on hrDis/accDis could double-subscribe and double-count RR.
    private final Object streamLock = new Object();

    public PolarBle(Context ctx, String mac, boolean withAcc, Sink sink) {
        this.ctx = ctx.getApplicationContext();
        this.id = mac;
        this.withAcc = withAcc;
        this.sink = sink;
    }

    public void setRecordingStore(RecordingStore s) { this.recordingStore = s; }

    /** Whether an RR recording is currently running on the H10 (for the status badge). */
    public boolean isRecording() { return recordingActive; }

    /** A non-owned recording (e.g. Polar Beat) occupies the single slot, so we can't
     *  start ours — surfaced to the UI rather than silently deleting it. */
    public boolean isExternalBlocking() { return externalBlocking; }

    /** True when ACC is being duty-cycled for power saving — steps can't be counted
     *  accurately from short bursts, so the engine omits them. */
    public boolean accDutyCycled() { return withAcc && accEnabled && accDutyCycle; }

    public void start() {
        exec.execute(() -> {
            try {
                if (stopping) return;
                // Live HR does not require a bond. Pair only inside the deferred recording
                // worker; otherwise ensureBonded can outlive stop() and create an SDK client
                // after shutdown was requested (#14).
                Set<PolarBleApi.PolarBleSdkFeature> features = EnumSet.of(
                        PolarBleApi.PolarBleSdkFeature.FEATURE_HR,
                        PolarBleApi.PolarBleSdkFeature.FEATURE_POLAR_ONLINE_STREAMING,
                        PolarBleApi.PolarBleSdkFeature.FEATURE_POLAR_H10_EXERCISE_RECORDING,
                        // NOTE: NOT FEATURE_POLAR_DEVICE_TIME_SETUP — the H10 does not support
                        // time READ, so the SDK's feature-check probe hangs 10 s and aborts ALL
                        // streams (HR included). We start-anchor backfill to our own clock, so
                        // device time is unnecessary anyway.
                        PolarBleApi.PolarBleSdkFeature.FEATURE_DEVICE_INFO,
                        // Battery % via batteryLevelReceived. This is a plain GATT 0x180F read,
                        // NOT a feature-check probe like DEVICE_TIME_SETUP, so it does not hang.
                        PolarBleApi.PolarBleSdkFeature.FEATURE_BATTERY_INFO);
                PolarBleApi created = PolarBleApiDefaultImpl.defaultImplementation(ctx, features);
                try {
                    created.setApiLogger(s -> Log.d(TAG, "[sdk] " + s));
                    created.setAutomaticReconnection(true);
                    created.setApiCallback(callback);
                } catch (Throwable configureFailure) {
                    try { created.shutDown(); } catch (Throwable ignored) {}
                    throw configureFailure;
                }
                if (!apiSlot.publish(created, value -> {
                    try { value.shutDown(); } catch (Throwable ignored) {}
                })) return;
                sink.log("connecting " + id);
                stage("connecting", "initial", 1, 0);
                issueConnect("initial", 1);
            } catch (Throwable t) {
                sink.log("start failed: " + t.getMessage());
            }
        });
    }

    /** App returned to foreground — nudge the SDK to restart its BLE scan (it can't
     *  start a scan while the display is off), so a lost connection re-establishes. */
    public void foregroundEntered() {
        PolarBleApi a = api();
        if (a != null) { try { a.foregroundEntered(); } catch (Throwable ignored) {} }
    }

    /** See {@link #rrStale}. Wire before {@link #start()}. */
    public void setRrStaleCheck(java.util.function.BooleanSupplier s) { this.rrStale = s; }

    /** Bluetooth adapter power-cycled back ON: the SDK's auto-reconnect does not reliably
     *  survive an adapter cycle, so re-issue the connect when the link is down. */
    public void reconnectIfDown() {
        execSafe(() -> {
            if (stopping || api() == null || linkConnected) return;
            sink.log("bluetooth adapter restarted — re-issuing connect");
            issueConnect("bt-on", 1);
        });
    }

    /** Issue connectToDevice with bounded retries. A throw here (adapter mid-cycle, transient
     *  stack state) used to be logged once and never retried, leaving the session dead until
     *  an app restart. Runs (and reschedules itself) on exec. */
    private void issueConnect(String why, int attempt) {
        if (stopping || api() == null) return;
        try {
            if (attempt > 1) sink.log("connect (" + why + ") attempt " + attempt + "/" + MAX_CONNECT_ATTEMPTS);
            stage("connecting", why, attempt, 0);
            api().connectToDevice(id);
        } catch (Throwable t) {
            sink.log("connect (" + why + ") failed: " + t.getMessage());
            if (attempt < MAX_CONNECT_ATTEMPTS) {
                long next = System.currentTimeMillis() + RETRY_DELAY_S * 1000L;
                stage("retry_wait", "connect_" + why, attempt, next);
                try { exec.schedule(() -> issueConnect(why, attempt + 1), RETRY_DELAY_S, TimeUnit.SECONDS); }
                catch (RejectedExecutionException ignored) {}
            } else {
                stage("needs_action", "connect_retries_exhausted", attempt, 0);
            }
        }
    }

    /** Run work on the BLE worker, swallowing the rejection that happens if a teardown
     *  already shut the executor down (callbacks can still fire mid-stop). */
    private void execSafe(Runnable r) {
        if (stopping) return;
        try { exec.execute(r); } catch (RejectedExecutionException ignored) {}
    }

    /** User-triggered, bounded discovery on the owned SDK instance. It never auto-selects. */
    public void scanDevices(int seconds, java.util.function.Consumer<List<PolarDeviceInfo>> ok,
            java.util.function.Consumer<Throwable> fail) {
        PolarBleApi a = api();
        if (stopping || a == null) { fail.accept(new IllegalStateException("BLE engine is not ready")); return; }
        if (!selectionScanActive.compareAndSet(false, true)) {
            fail.accept(new IllegalStateException("An H10 scan is already running"));
            return;
        }
        AtomicBoolean terminal = new AtomicBoolean(false);
        stage("scanning", "device_selection", 0, System.currentTimeMillis() + seconds * 1000L);
        try {
            selectionScanDis = a.searchForDevice()
                .filter(info -> info.isConnectable() && info.getHasHeartRateService())
                .distinct(info -> info.getAddress() != null ? info.getAddress() : info.getDeviceId())
                .take(Math.max(1, Math.min(15, seconds)), TimeUnit.SECONDS)
                .toList()
                .doFinally(() -> {
                    selectionScanDis = null;
                    selectionScanActive.set(false);
                    if (terminal.compareAndSet(false, true))
                        fail.accept(new IllegalStateException("H10 scan was cancelled"));
                })
                .subscribe(list -> {
                    if (!terminal.compareAndSet(false, true)) return;
                    if (linkConnected) stage("connected", "selection_scan_complete", 0, 0);
                    ok.accept(list);
                }, error -> {
                    if (!terminal.compareAndSet(false, true)) return;
                    if (linkConnected) stage("connected", "selection_scan_failed", 0, 0);
                    fail.accept(error);
                });
        } catch (Throwable error) {
            selectionScanActive.set(false);
            if (terminal.compareAndSet(false, true)) fail.accept(error);
        }
    }

    private void recordingSafe(Runnable r) {
        if (stopping) return;
        try { recordingExec.execute(r); } catch (RejectedExecutionException ignored) {}
    }

    private void scheduleRecording(Runnable r, long delay, TimeUnit unit) {
        if (stopping) return;
        try { recordingExec.schedule(r, delay, unit); } catch (RejectedExecutionException ignored) {}
    }

    private boolean currentRecordingJob(int gen) {
        return !stopping && api() != null && linkConnected && gen == connGen.get();
    }

    private void stage(String value, String reason, int attempt, long nextRetryAt) {
        try { sink.onStage(value, reason, attempt, nextRetryAt); } catch (Throwable ignored) {}
    }

    /** Re-subscribe the live streams when the link is 'connected' but no data is flowing
     *  (a stream subscription errored). Gentle on purpose: it does NOT disconnect — an
     *  explicit disconnect won't auto-reconnect (the SDK treats it as intentional) and a
     *  reconnect needs a scan, which can't run with the screen off; repeated disconnect
     *  churn also wedges the BLE scanner. If the H10 is truly orphaned (after a force-stop /
     *  abrupt kill), only a Bluetooth toggle clears it. Driven by HrvEngine's watchdog. */
    public void nudgeStreams() {
        if (stopping || api() == null) return;
        execSafe(() -> {
            if (stopping || api() == null || !linkConnected) return;
            // Re-validate at execution time: exec may have been blocked by a long PFTP op
            // since the watchdog queued this — if RR resumed meanwhile, leave the streams be.
            if (!rrStale.getAsBoolean()) { sink.log("nudge skipped — RR already flowing"); return; }
            sink.log("nudge: re-subscribing streams (stale)");
            disposeStreams();
            startHr();
            beginAcc();
        });
    }

    /** Stream watchdog escalation: a 'connected' link that never delivered RR is a half-open
     *  orphan (e.g. after a force-stop / abrupt kill). Re-subscribing can't fix that — only a
     *  fresh link does. Force one clean disconnect→reconnect; the caller (HrvEngine watchdog)
     *  bounds how many times this runs per stale episode so churn can't wedge the scanner. */
    public void forceReconnect() {
        execSafe(() -> cleanReconnect(true));
    }

    public void stop() {
        stopping = true;
        stage("stopped", "user_or_service_stop", 0, 0);
        recordingActive = false;
        disposeStreams();
        Disposable scan = selectionScanDis; selectionScanDis = null;
        if (scan != null) try { scan.dispose(); } catch (Throwable ignored) {}
        selectionScanActive.set(false);
        final PolarBleApi a = apiSlot.stopAndTake();
        // Interrupt any in-flight blocking recording op so teardown is NOT queued
        // behind a multi-second fetch (which would skip disconnect/shutDown).
        work.shutdownNow();
        if (a == null) return;
        // Tear down on a throwaway thread (never the possibly-busy worker) and ALWAYS
        // disconnect + shutDown, so the single-connection H10 is never orphaned. Any
        // leftover device recording is handled by the next launch via DB-persisted meta
        // (recovered if the stop was an OS kill, reclaimed if the user stopped cleanly).
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
            if (stopping) return;
            linkConnected = true;
            recordingHandledThisConn.set(false);
            connGen.incrementAndGet();              // supersede retries scheduled under a prior connection
            recordingSafe(() -> recordingAttempt = 0); // recording counters stay on recordingExec
            sink.onConnected(true);
            stage("connected", "link_ready", 0, 0);
            sink.log("connected " + info.getDeviceId());
        }
        @Override public void deviceConnecting(PolarDeviceInfo info) {
            if (!stopping) sink.log("connecting…");
        }
        @Override public void deviceDisconnected(PolarDeviceInfo info) {
            if (stopping) return;
            linkConnected = false;
            sink.onConnected(false);
            stage("disconnected", "link_lost", 0, 0);
            recordingHandledThisConn.set(false);
            disposeStreams();  // streamLock-guarded; safe vs a concurrent startHr on exec
            sink.log("disconnected");
        }
        @Override public void bleSdkFeatureReady(String identifier, PolarBleApi.PolarBleSdkFeature feature) {
            if (stopping) return;
            switch (feature) {
                case FEATURE_HR:
                    startHr();
                    break;
                case FEATURE_POLAR_ONLINE_STREAMING:
                    beginAcc();
                    break;
                case FEATURE_POLAR_H10_EXERCISE_RECORDING:
                    if (recordingHandledThisConn.compareAndSet(false, true)) {
                        // Defer the PFTP work so the live HR/ACC streams establish first.
                        // Running heavy blocking PFTP ops on the single BLE link right at
                        // connect competed with stream setup and dropped the connection,
                        // and the H10 returns OPERATION_NOT_PERMITTED(106) until it settles.
                        final int gen = connGen.get();   // this connection's generation
                        if (!stopping) {
                            stage("recording_wait", "streams_settling", 0,
                                    System.currentTimeMillis() + RECORDING_DEFER_S * 1000L);
                            scheduleRecording(() -> runRecordingOnConnect(gen), RECORDING_DEFER_S, TimeUnit.SECONDS);
                        }
                    }
                    break;
                default:
                    break;
            }
        }
        @Override public void batteryLevelReceived(String identifier, int level) {
            if (stopping) return;
            sink.log("battery " + level + "%");
            sink.onBattery(level);
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
        synchronized (streamLock) {
            PolarBleApi a = api();
            if (stopping || a == null || (hrDis != null && !hrDis.isDisposed())) return;
            final int gen = streamGen.get();
            Disposable created = a.startHrStreaming(id).subscribe(
                    data -> {
                        if (stopping || !linkConnected || gen != streamGen.get()) return;
                        for (PolarHrData.PolarHrSample s : data.getSamples()) {
                            sink.onHr(s.getHr());
                            for (Integer rr : s.getRrsMs()) sink.onRr(rr.doubleValue());
                        }
                    },
                    err -> {
                        if (stopping || gen != streamGen.get()) return;
                        sink.log("hr stream err: " + err.getMessage());
                        synchronized (streamLock) {
                            if (gen == streamGen.get()) hrDis = null;
                        }
                    });
            hrDis = created.isDisposed() ? null : created;
        }
    }

    private void startAcc() {
        synchronized (streamLock) {
            PolarBleApi a = api();
            if (stopping || a == null || (accDis != null && !accDis.isDisposed())) return;
            final int accGen = accSchedule.generation();
            // Query the device's actual ACC capabilities and pick from them (25 Hz / ±2 G /
            // 16-bit when offered) instead of hardcoding — the H10 rejects unsupported combos
            // (a wrong/extra key made startAccStreaming error and posture never got samples).
            Disposable created = a.requestStreamSettings(id, PolarBleApi.PolarDeviceDataType.ACC)
                    .toFlowable()
                    .flatMap(available -> a.startAccStreaming(id, pickAccSetting(available)))
                    .subscribe(
                            data -> {
                                if (stopping || !linkConnected || accGen != accSchedule.generation()) return;
                                for (PolarAccelerometerData.PolarAccelerometerDataSample s : data.getSamples()) {
                                    sink.onAcc(s.getX(), s.getY(), s.getZ());
                                }
                            },
                            err -> {
                                if (stopping) return;
                                sink.log("acc stream err: " + err.getMessage());
                                synchronized (streamLock) {
                                    if (accGen == accSchedule.generation()) accDis = null;
                                }
                                // Default continuous mode previously had no recovery unless RR also
                                // failed. Retry the ACC stream itself without disturbing HR/RR.
                                if (!stopping && linkConnected && accEnabled && accGen == accSchedule.generation()) {
                                    try { exec.schedule(() -> {
                                        if (!stopping && linkConnected && accEnabled
                                                && accGen == accSchedule.generation()) beginAcc();
                                    }, RETRY_DELAY_S, TimeUnit.SECONDS); }
                                    catch (RejectedExecutionException ignored) {}
                                }
                            });
            accDis = created.isDisposed() ? null : created;
            sink.log("ACC streaming requested");
        }
    }

    /** Choose a light, supported ACC setting from what the device actually offers:
     *  prefer 25 Hz / 16-bit / ±2 G, else fall back to the smallest offered value. */
    private static PolarSensorSetting pickAccSetting(PolarSensorSetting available)
            throws com.polar.sdk.api.errors.PolarInvalidSensorSettingsError {
        Map<PolarSensorSetting.SettingType, Set<Integer>> avail = available.getSettings();
        Map<PolarSensorSetting.SettingType, Integer> chosen = new HashMap<>();
        putPreferred(chosen, avail, PolarSensorSetting.SettingType.SAMPLE_RATE, 25);
        putPreferred(chosen, avail, PolarSensorSetting.SettingType.RESOLUTION, 16);
        putPreferred(chosen, avail, PolarSensorSetting.SettingType.RANGE, 2);
        return new PolarSensorSetting(chosen);
    }

    private static void putPreferred(Map<PolarSensorSetting.SettingType, Integer> out,
            Map<PolarSensorSetting.SettingType, Set<Integer>> avail,
            PolarSensorSetting.SettingType type, int preferred) {
        Set<Integer> opts = avail != null ? avail.get(type) : null;
        if (opts == null || opts.isEmpty()) return;            // not offered — let the SDK default it
        out.put(type, opts.contains(preferred) ? preferred : Collections.min(opts));
    }

    private void disposeStreams() {
        cancelAccDuty();                 // stop the ACC duty cycle (if running) before tearing down
        synchronized (streamLock) {
            streamGen.incrementAndGet();
            Disposable h = hrDis, a = accDis;
            hrDis = null; accDis = null;
            if (h != null) { try { h.dispose(); } catch (Throwable ignored) {} }
            if (a != null) { try { a.dispose(); } catch (Throwable ignored) {} }
        }
    }

    /** Dispose only the ACC stream, leaving HR running — used between duty-cycle bursts. */
    private void disposeAcc() {
        synchronized (streamLock) {
            Disposable a = accDis; accDis = null;
            if (a != null) { try { a.dispose(); } catch (Throwable ignored) {} }
        }
    }

    /** Begin ACC sampling. 25 Hz is the H10's MINIMUM accelerometer rate, so when
     *  {@link #accDutyCycle} is on we cut its power by streaming a short burst once per
     *  period (posture refreshes each burst — it changes slowly; steps are omitted)
     *  rather than continuously. HR/RR are never affected. Runtime-toggleable. */
    private void beginAcc() {
        if (!withAcc || !accEnabled) return; // 姿勢推定OFF: ACCは一切購読しない
        final int gen = connGen.get();
        final boolean duty = accDutyCycle;
        accSchedule.begin(duty, ACC_DUTY_ON_MS, ACC_DUTY_PERIOD_MS,
                () -> !stopping && api() != null && linkConnected && accEnabled
                        && gen == connGen.get() && duty == accDutyCycle,
                this::startAcc, this::disposeAcc);
    }

    private void cancelAccDuty() {
        accSchedule.cancel();
    }

    /** Switch the ACC power mode at runtime (dashboard 省電力 toggle): true = burst/duty
     *  (low power, posture-only), false = continuous (full posture + steps). Re-applies to
     *  the live ACC stream without touching HR/RR. */
    public void setAccDutyCycle(boolean on) {
        if (accDutyCycle == on) return;
        accDutyCycle = on;
        execSafe(() -> {
            if (stopping || api() == null || !linkConnected || !withAcc || !accEnabled) return;
            cancelAccDuty();
            disposeAcc();
            beginAcc(); // re-reads accDutyCycle: continuous or burst
        });
    }

    /** Turn the accelerometer stream on/off at runtime (dashboard 姿勢推定 toggle).
     *  OFF unsubscribes ACC entirely — the H10 then only runs its ECG/RR path, which is the
     *  single biggest sensor-battery saving available (25 Hz is the ACC's MINIMUM rate, so
     *  even the duty-cycled power-save mode still pays for periodic ACC startup). HR/RR are
     *  untouched, so RMSSD keeps running. Applies live and to the next connect. */
    public void setAccEnabled(boolean on) {
        if (accEnabled == on) return;
        accEnabled = on;
        execSafe(() -> {
            if (stopping || api() == null || !withAcc) return;
            cancelAccDuty();
            disposeAcc();                          // OFF: drop the live subscription now
            if (accEnabled && linkConnected) beginAcc(); // ON: resume in the current power mode
        });
    }

    // --- recording lifecycle (dedicated serial worker; blocking RxJava stays off control) ---
    private enum FetchResult { PERSISTED, EMPTY, PFTP_FAILED, STORAGE_FAILED }
    private enum SlotClass { EMPTY, OURS_NO_META, FOREIGN, UNKNOWN }
    private enum RetryKind { NONE, PFTP, STORAGE }
    private enum PftpFailure { TRANSIENT, AUTHORIZATION, UNKNOWN }
    private static final class ActiveProbe {
        final RecordingOwnership.Kind kind; final String identifier;
        ActiveProbe(RecordingOwnership.Kind kind, String identifier) { this.kind = kind; this.identifier = identifier; }
    }
    private volatile PftpFailure lastPftpFailure = PftpFailure.UNKNOWN;

    /** Drive the single H10 slot on (re)connect. Recovers a DB-persisted recording if
     *  present (across reconnects AND app/OS restarts), else starts fresh while guarding
     *  a foreign slot. On a transient PFTP failure it retries rather than overwriting the
     *  not-yet-recovered gap. */
    private void runRecordingOnConnect(int gen) {
        if (!currentRecordingJob(gen)) return;
        // Authorization evidence belongs to this recovery pass. A prior auth error must not
        // make a later timeout/storage incident eligible for an unrelated bond reset.
        lastPftpFailure = PftpFailure.UNKNOWN;
        if (!ensurePftpBond(gen) || !currentRecordingJob(gen)) return;
        RetryKind retry = RetryKind.NONE;
        try {
            // Guard a foreign ACTIVE recording FIRST. An actively-recording exercise is NOT
            // listable until it is finalized, so classifySlot alone cannot see it — and both
            // recovery's stop and startFresh's slot-clear would kill it. requestRecordingStatus
            // sees it live; when it is someone else's (e.g. Polar Beat), leave the slot alone.
            ActiveProbe active = activeRecording();
            if (!currentRecordingJob(gen)) return;
            if (active.kind == RecordingOwnership.Kind.UNKNOWN) {
                sink.log("recording ownership unknown — leaving the slot untouched");
                retry = RetryKind.PFTP;
            } else if (active.kind == RecordingOwnership.Kind.FOREIGN) {
                externalBlocking = true;
                sink.log("foreign recording active (" + active.identifier + ") — leaving the slot untouched");
                stage("needs_action", "foreign_recording", 0, 0);
                return;
            }
            RecordingStore store = recordingStore;
            RecordingStore.OpenRec open = null;
            if (retry == RetryKind.NONE) {
                try { open = (store != null) ? store.getOpenRecording() : null; }
                catch (Throwable storageFailure) {
                    sink.log("recording metadata read failed: " + storageFailure.getMessage());
                    retry = RetryKind.STORAGE;
                }
            }
            // An active rmssd recording with no matching durable anchor is not safe to stop.
            if (retry == RetryKind.NONE && active.kind == RecordingOwnership.Kind.OURS
                    && (open == null || !active.identifier.equals(open.exId))) {
                externalBlocking = true;
                sink.log("untracked active rmssd recording — preserving it");
                stage("needs_action", "recording_anchor_missing", 0, 0);
                return;
            }
            if (retry == RetryKind.NONE && open != null) {
                // Unifies same-process reconnect AND post-restart recovery: the anchor and
                // exId come from the DB, so even a brand-new process recovers the gap.
                FetchResult recovered = recoverRecording(open, gen, active.kind == RecordingOwnership.Kind.OURS);
                if (recovered == FetchResult.PFTP_FAILED) retry = RetryKind.PFTP;
                else if (recovered == FetchResult.STORAGE_FAILED) retry = RetryKind.STORAGE;
            }
            if (retry == RetryKind.NONE && currentRecordingJob(gen)) {
                // (Re-)classify even after a recovery: recovery only settles OUR exercise's
                // fate — the slot could still hold a payload we must never overwrite, and
                // startFreshRecording's slot-clear would otherwise churn on PFTP 106.
                SlotClass slot = classifySlot();
                if (slot == SlotClass.UNKNOWN) {
                    retry = RetryKind.PFTP; // list failed — retry rather than guessing the slot state
                } else if (slot != SlotClass.EMPTY) {
                    externalBlocking = true;
                    sink.log(slot == SlotClass.FOREIGN
                            ? "external recording present — not starting (slot not ours)"
                            : "untracked rmssd recording present — preserving it; recovery metadata is missing");
                    return; // never auto-delete a foreign or unanchored device payload
                } else {
                    externalBlocking = false;
                    FetchResult started = startFreshRecording(gen);
                    if (started == FetchResult.PFTP_FAILED) retry = RetryKind.PFTP;
                    else if (started == FetchResult.STORAGE_FAILED) retry = RetryKind.STORAGE;
                }
            }
        } catch (Throwable t) {
            sink.log("recording-on-connect failed: " + t.getMessage());
            retry = RetryKind.PFTP;
        }
        if (retry == RetryKind.NONE) {
            recordingAttempt = 0; recoveryReconnects = 0; rebondTried = false;
            stage("streaming", "recording_ready", 0, 0);
            return;
        }
        if (currentRecordingJob(gen)) {
            RecordingRecoveryPolicy.Failure failure = retry == RetryKind.STORAGE
                    ? RecordingRecoveryPolicy.Failure.STORAGE : RecordingRecoveryPolicy.Failure.PFTP;
            RecordingRecoveryPolicy.Action action = RecordingRecoveryPolicy.decide(failure,
                    recordingAttempt, MAX_RECORDING_ATTEMPTS, recoveryReconnects,
                    MAX_RECOVERY_RECONNECTS, lastPftpFailure == PftpFailure.AUTHORIZATION,
                    !rrStale.getAsBoolean(), rebondTried);
            if (action == RecordingRecoveryPolicy.Action.RETRY) {
                recordingAttempt++;
                String reason = retry == RetryKind.STORAGE ? "storage_retry" : "pftp_retry";
                sink.log("recording " + reason + " " + recordingAttempt + "/" + MAX_RECORDING_ATTEMPTS
                        + " in " + RETRY_DELAY_S + "s");
                long next = System.currentTimeMillis() + RETRY_DELAY_S * 1000L;
                stage("retry_wait", reason, recordingAttempt, next);
                scheduleRecording(() -> runRecordingOnConnect(gen), RETRY_DELAY_S, TimeUnit.SECONDS);
            } else if (action == RecordingRecoveryPolicy.Action.PAUSE_STORAGE) {
                // Disk/DB failures cannot be repaired by touching BLE. Preserve the H10 slot
                // and finish in an observable state until a later reconnect/app restart retries.
                stage("needs_action", "storage_unavailable", recordingAttempt, 0);
                sink.log("recording recovery paused — local storage unavailable; H10 slot preserved");
            } else if (action == RecordingRecoveryPolicy.Action.RECONNECT) {
                // PFTP stayed 106 through every retry on THIS link. A phone-side abrupt drop
                // (e.g. a Bluetooth toggle after a force-stop) can wedge the H10's PFTP so it
                // never clears on the same connection — only a fresh link does (what re-strapping
                // the H10 achieves). Force one clean reconnect and let recovery retry on the fresh
                // connection (recordingAttempt resets in deviceConnected).
                recoveryReconnects++;
                sink.log("recording stuck after retries — forcing clean reconnect "
                        + recoveryReconnects + "/" + MAX_RECOVERY_RECONNECTS);
                execSafe(this::forceReconnectForRecording);
            } else if (action == RecordingRecoveryPolicy.Action.REBOND) {
                // Fresh links didn't help either. RR flowing while PFTP still fails is the
                // signature of a one-sided bond loss (H10 battery pull / reset while the phone
                // still reports BONDED) — only re-pairing fixes that. A wedged sensor has stale
                // RR too, and rebonding THEN would destroy a good bond for nothing and leave an
                // unbonded reconnect loop spamming OS pairing dialogs — so the gate stays open
                // until RR proves the sensor alive. One reset per stuck episode.
                rebondTried = true;
                sink.log("recording still stuck with live RR — resetting the OS bond and reconnecting");
                PolarBleApi a = api();
                if (a != null) try { a.disconnectFromDevice(id); } catch (Throwable ignored) {}
                lastBondPromptAt = System.currentTimeMillis(); // rebond prompts the OS pairing dialog
                boolean ok = PolarBonding.rebond(ctx, id, sink::log);
                sink.log(ok ? "bond re-established" : "bond reset failed");
                try { exec.schedule(() -> issueConnect("rebond", 1), 3, TimeUnit.SECONDS); }
                catch (RejectedExecutionException ignored) {}
            } else {
                stage("needs_action", "pftp_retries_exhausted", recordingAttempt, 0);
                sink.log("recording recovery gave up — re-strap H10 to recover");
            }
        }
    }

    /** PFTP requires an encrypted (bonded) link — ANY PFTP op on an unbonded one makes the
     *  Android stack fire an OS pairing-request dialog, and with the retry/reconnect loops
     *  around recording that turns into an endless stream of dialogs. So all PFTP work is
     *  gated here instead: already bonded → proceed; otherwise prompt for pairing at most
     *  once per {@link #BOND_PROMPT_MIN_MS}, and only while RR is flowing (an unresponsive
     *  H10 cannot complete SMP, so prompting would be pure dialog spam). While unbonded the
     *  recording job re-checks periodically without prompting — live HR/RR needs no bond and
     *  keeps streaming throughout. Must run on exec. */
    private boolean ensurePftpBond(int gen) {
        if (PolarBonding.isBonded(ctx, id)) return true;
        long now = System.currentTimeMillis();
        if (rrStale.getAsBoolean()) {
            sink.log("not bonded and RR not flowing — postponing pairing (re-strap H10 if this persists)");
        } else if (now - lastBondPromptAt >= BOND_PROMPT_MIN_MS) {
            lastBondPromptAt = now;
            sink.log("not bonded — requesting OS pairing (accept the dialog on the phone)");
            PolarBonding.ensureBonded(ctx, id, sink::log);
            if (PolarBonding.isBonded(ctx, id)) return true;
            sink.log("pairing not completed — recording deferred (live HR/RR unaffected)");
        } else {
            sink.log("not bonded — pairing prompt rate-limited; recording deferred");
        }
        // Quiet re-check: picks up a bond made later (user accepts the dialog or pairs from
        // Settings) without another prompt, then resumes recording on this same connection.
        scheduleRecording(() -> runRecordingOnConnect(gen), 60, TimeUnit.SECONDS);
        return false;
    }

    /** Four-way ownership probe. UNKNOWN is deliberately fail-closed: no recording-changing
     *  operation may run until ownership is positively known (#20). */
    private ActiveProbe activeRecording() {
        try {
            androidx.core.util.Pair<Boolean, String> st = api().requestRecordingStatus(id)
                    .timeout(OP_TIMEOUT_S, TimeUnit.SECONDS).blockingGet();
            if (st == null) return new ActiveProbe(RecordingOwnership.Kind.UNKNOWN, null);
            String idf = st.second;
            return new ActiveProbe(RecordingOwnership.classify(st.first, idf, EX_PREFIX), idf);
        } catch (Throwable t) {
            sink.log("requestRecordingStatus failed: " + t.getMessage());
            notePftpFailure(t);
            return new ActiveProbe(RecordingOwnership.Kind.UNKNOWN, null);
        }
    }

    /** Recovery's PFTP is wedged on the current link — get a fresh link so recovery retries on
     *  a new connection (recordingAttempt resets in deviceConnected). Already on exec. RR may
     *  be flowing perfectly here (it's PFTP that's stuck), so no staleness gate. */
    private void forceReconnectForRecording() { cleanReconnect(false); }

    /** Disconnect then reconnect to obtain a fresh link (live streams or recording wedged on
     *  the current one). An explicit disconnect disables the SDK's auto-reconnect, so we issue
     *  the reconnect ourselves after a short gap. Must run on exec. */
    private void cleanReconnect(boolean onlyIfRrStale) {
        if (stopping || api() == null) return;
        // Re-validate at execution time: this can run a whole PFTP op after the watchdog
        // queued it — disconnecting a link whose RR recovered meanwhile would only re-create
        // the outage it was meant to fix.
        if (onlyIfRrStale && !rrStale.getAsBoolean()) { sink.log("forced reconnect skipped — RR already flowing"); return; }
        try { api().disconnectFromDevice(id); } catch (Throwable ignored) {}
        try {
            exec.schedule(() -> {
                if (stopping || api() == null) return;
                sink.log("reconnecting after stall");
                issueConnect("stall", 1);
            }, 3, TimeUnit.SECONDS);
        } catch (RejectedExecutionException ignored) {}
    }

    /** Stop → fetch → replay+persist → remove a recoverable recording. */
    private FetchResult recoverRecording(RecordingStore.OpenRec open, int gen, boolean active) {
        try {
            // Stop FIRST. An actively-recording exercise is NOT listable until it is
            // finalized, so listing before stopping makes matchEntry find nothing and we
            // would wrongly discard a live gap. No-op if already stopped (memory full).
            if (active) {
                if (!stopRecording()) return FetchResult.PFTP_FAILED;
                if (!currentRecordingJob(gen)) return FetchResult.PFTP_FAILED;
                recordingActive = false;
            }
            List<PolarExerciseEntry> entries = listExercises();
            if (entries == null) {
                // Listing FAILED (PFTP 106 / timeout) — NOT the same as "no recording". We must
                // not conclude it is gone: that would drop the anchor (recRemoved) and let the
                // subsequent startFresh delete the un-fetched gap. Keep it and retry next time.
                sink.log("recover: listExercises failed — keeping recording for retry");
                return FetchResult.PFTP_FAILED;
            }
            if (!currentRecordingJob(gen)) return FetchResult.PFTP_FAILED;
            PolarExerciseEntry entry = matchEntry(entries, open.exId);
            if (entry == null) {
                // Listing SUCCEEDED and there is genuinely no matching exercise (start never
                // persisted on-device, or already removed) → safe to mark removed.
                sink.log("recover: no device entry for " + open.exId + " — marking removed");
                RecordingStore s = recordingStore;
                try { if (s != null) s.recRemoved(open.exId); }
                catch (Throwable storageFailure) {
                    sink.log("recording metadata finalize failed: " + storageFailure.getMessage());
                    return FetchResult.STORAGE_FAILED;
                }
                return FetchResult.EMPTY;
            }
            boolean uncertainAnchor = RecordingStartAttempt.requiresQuarantine(open.state);
            return fetchAndBackfill(entry, open.anchorStartMs, open.exId, gen, uncertainAnchor);
        } catch (Throwable t) {
            sink.log("recover failed: " + t.getMessage());
            notePftpFailure(t);
            return FetchResult.PFTP_FAILED;
        }
    }

    private FetchResult fetchAndBackfill(PolarExerciseEntry entry, long anchorStartMs, String exId,
            int gen, boolean uncertainAnchor) {
        // No anchor validity check here: recPersistGap quarantines an implausible anchor
        // (raw RR preserved durably in SQLite) and returns QUARANTINED, which lets the device
        // exercise be removed below. An early EMPTY return instead left the slot occupied
        // forever — no branch could ever clear it.
        RecordingStore store = recordingStore;
        if (store == null) return FetchResult.STORAGE_FAILED;
        try {
            PolarExerciseData data = api().fetchExercise(id, entry)
                    .timeout(FETCH_TIMEOUT_S, TimeUnit.SECONDS).blockingGet();
            if (!currentRecordingJob(gen)) return FetchResult.PFTP_FAILED;
            List<Integer> samples = data != null ? data.getHrSamples() : null; // RR ms (recorded as RR)
            int n = samples != null ? samples.size() : 0;
            double[] rr = new double[n];
            long sum = 0;
            for (int i = 0; i < n; i++) { rr[i] = samples.get(i); sum += samples.get(i); }
            if (n < 2) sink.log("backfill: " + n + " samples — preserving raw payload before removal");
            boolean truncated = detectTruncated(n, sum, anchorStartMs);
            RecordingStore.PersistResult persisted;
            try {
                store.recFetching(exId, n, sum, truncated);
                persisted = uncertainAnchor
                        ? store.recPersistUncertainGap(rr, anchorStartMs, exId)
                        : store.recPersistGap(rr, anchorStartMs, exId, truncated);
            } catch (Throwable storageFailure) {
                sink.log("backfill storage failed: " + storageFailure.getMessage());
                return FetchResult.STORAGE_FAILED;
            }
            if (!persisted.isDurable()) {
                sink.log("backfill not persisted; keeping exercise for retry");
                return FetchResult.STORAGE_FAILED; // never reset a healthy BLE link for a DB/disk failure
            }
            if (!currentRecordingJob(gen)) return FetchResult.PFTP_FAILED;
            // Durable. Remove from the device; if remove fails the recording stays open
            // ('persisted') and a later retry re-fetches idempotently before reclaiming.
            try {
                api().removeExercise(id, entry).timeout(OP_TIMEOUT_S, TimeUnit.SECONDS).blockingAwait();
                store.recRemoved(exId);
                return FetchResult.PERSISTED;
            } catch (Throwable t) {
                sink.log("removeExercise failed (data persisted): " + t.getMessage());
                notePftpFailure(t);
                return FetchResult.PFTP_FAILED; // don't start fresh yet — retry the remove
            }
        } catch (Throwable t) {
            sink.log("fetchAndBackfill failed: " + t.getMessage());
            notePftpFailure(t);
            return FetchResult.PFTP_FAILED;
        }
    }

    private FetchResult startFreshRecording(int gen) {
        if (!currentRecordingJob(gen)) return FetchResult.PFTP_FAILED;
        // No setLocalTime: the H10 doesn't support the time feature cleanly (see start()),
        // and backfill is start-anchored to our own clock, so device time is not needed.
        // The caller has positively classified the slot as empty, or has just durably recovered
        // and removed its tracked exercise. Never auto-delete an untracked payload here.
        RecordingStartAttempt attempt = RecordingStartAttempt.create(System::currentTimeMillis);
        String exId = attempt.exId;
        long startReqMs = attempt.anchorStartMs;
        RecordingStore store = recordingStore;
        if (store == null) return FetchResult.STORAGE_FAILED;
        try { store.recStarting(exId, startReqMs); }
        catch (Throwable storageFailure) {
            sink.log("recording anchor persistence failed: " + storageFailure.getMessage());
            return FetchResult.STORAGE_FAILED;
        }
        try {
            api().startRecording(id, exId,
                    PolarH10OfflineExerciseApi.RecordingInterval.INTERVAL_1S,
                    PolarH10OfflineExerciseApi.SampleType.RR)
                    .timeout(OP_TIMEOUT_S, TimeUnit.SECONDS).blockingAwait();
            if (!currentRecordingJob(gen)) return FetchResult.PFTP_FAILED;
            recordingActive = true;
            try { store.recActive(exId, System.currentTimeMillis(), false); }
            catch (Throwable storageFailure) {
                sink.log("recording acknowledgement persistence failed: " + storageFailure.getMessage());
                return FetchResult.STORAGE_FAILED;
            }
            sink.log("RR recording started");
            return FetchResult.PERSISTED;
        } catch (Throwable t) {
            sink.log("startRecording failed: " + t.getMessage());
            notePftpFailure(t);
            if (currentRecordingJob(gen)) {
                // The command may have reached the H10 before the acknowledgement timed out.
                // Correlate the exact attempt ID; never retry with an older anchor.
                boolean definitelyNotStarted = isDefiniteStartRejection(t);
                ActiveProbe probe = definitelyNotStarted
                        ? new ActiveProbe(RecordingOwnership.Kind.INACTIVE, null)
                        : activeRecording();
                RecordingStartAttempt.Resolution resolution = RecordingStartAttempt.resolveFailure(
                        definitelyNotStarted, probe.kind, exId, probe.identifier);
                if (resolution == RecordingStartAttempt.Resolution.ACTIVE_UNCERTAIN) {
                    recordingActive = true;
                    try { store.recActive(exId, System.currentTimeMillis(), true); }
                    catch (Throwable storageFailure) { return FetchResult.STORAGE_FAILED; }
                    sink.log("RR recording start reconciled after timeout");
                    return FetchResult.PERSISTED;
                }
                if (resolution == RecordingStartAttempt.Resolution.PRESERVE_UNCERTAIN) {
                    sink.log("recording start outcome uncertain — preserving anchor and not retrying destructively");
                    return FetchResult.PFTP_FAILED;
                }
            }
            // A confirmed non-start is terminal for this attempt. The outer delayed retry
            // creates a fresh ID and anchor, so recovered points cannot inherit retry delay.
            try { store.recRemoved(exId); }
            catch (Throwable storageFailure) { return FetchResult.STORAGE_FAILED; }
            recordingActive = false;
            return FetchResult.PFTP_FAILED;
        }
    }

    // --- recording helpers ---------------------------------------------------
    private List<PolarExerciseEntry> listExercises() {
        try {
            return api().listExercises(id).toList().timeout(LIST_TIMEOUT_S, TimeUnit.SECONDS).blockingGet();
        } catch (Throwable t) {
            sink.log("listExercises failed: " + t.getMessage());
            notePftpFailure(t);
            return null;
        }
    }

    /** Stop a positively identified owned active recording. Failure is observable and blocks
     *  listing/removal; guessing after a failed stop can lose the still-active payload. */
    private boolean stopRecording() {
        try {
            api().stopRecording(id).timeout(OP_TIMEOUT_S, TimeUnit.SECONDS).blockingAwait();
            return true;
        } catch (Throwable t) {
            sink.log("stopRecording failed: " + t.getMessage());
            notePftpFailure(t);
            return false;
        }
    }

    /** Find the on-device exercise matching our exId; fall back to the single owned slot. */
    private static PolarExerciseEntry matchEntry(List<PolarExerciseEntry> entries, String exId) {
        if (entries == null || entries.isEmpty()) return null;
        if (exId != null) {
            for (PolarExerciseEntry e : entries) {
                if (exId.equals(entryIdentifier(e))) return e;
            }
        }
        // Fallback ONLY when the single entry has no usable identifier (firmware quirk) —
        // never match a DIFFERENT known identifier to our exId (it would replay the wrong
        // recording at the wrong anchor). A failed start leaves no device entry, so this
        // returns null and the caller self-heals to a fresh start.
        if (entries.size() == 1 && entryIdentifier(entries.get(0)) == null) return entries.get(0);
        return null;
    }

    private SlotClass classifySlot() {
        List<PolarExerciseEntry> entries = listExercises();
        if (entries == null) return SlotClass.UNKNOWN;
        if (entries.isEmpty()) return SlotClass.EMPTY;
        RecordingStore store = recordingStore;
        boolean kept = false;
        for (PolarExerciseEntry e : entries) {
            String idf = entryIdentifier(e);
            if (idf != null && !idf.startsWith(EX_PREFIX)) return SlotClass.FOREIGN; // known-foreign
            if (idf == null) { kept = true; continue; }
            // ^ unreadable identifier (firmware quirk): matchEntry treats a single null-id
            //   entry as OURS during recovery, so classifying it FOREIGN here would block the
            //   slot forever. Preserve it as ours-without-meta instead (never auto-deleted).
            if (store != null && store.canRemoveDiscarded(idf)) {
                try {
                    api().removeExercise(id, e).timeout(OP_TIMEOUT_S, TimeUnit.SECONDS).blockingAwait();
                    sink.log("removed explicitly discarded recording " + idf);
                    continue;
                } catch (Throwable t) {
                    sink.log("remove discarded recording failed: " + t.getMessage());
                    notePftpFailure(t);
                    return SlotClass.UNKNOWN;
                }
            }
            kept = true;
        }
        return kept ? SlotClass.OURS_NO_META : SlotClass.EMPTY;
    }

    private static String entryIdentifier(PolarExerciseEntry e) {
        try { return e.getIdentifier(); } catch (Throwable t) { return null; }
    }

    /** Heuristic memory-full detection: the recording auto-stopped well before now AND is
     *  near the RR cap / ~18 h, so only [start, start+duration] is valid (truncated). */
    private static boolean detectTruncated(int rrCount, long sumMs, long anchorStartMs) {
        long tail = System.currentTimeMillis() - (anchorStartMs + sumMs);
        boolean bigTail = tail > TRUNCATE_TAIL_MS;          // last beat is long before now
        boolean nearCap = rrCount >= (long) (RR_CAP * 0.98);
        boolean longDur = sumMs >= FULL_DURATION_MS;
        return bigTail && (nearCap || longDur);
    }

    private void notePftpFailure(Throwable t) {
        StringBuilder detail = new StringBuilder();
        for (Throwable c = t; c != null && detail.length() < 1000; c = c.getCause()) {
            detail.append(' ').append(c.getClass().getName()).append(' ').append(c.getMessage());
        }
        String s = detail.toString().toLowerCase(java.util.Locale.ROOT);
        lastPftpFailure = (s.contains("auth") || s.contains("bond") || s.contains("encrypt")
                || s.contains("insufficient security") || s.contains("permission denied"))
                ? PftpFailure.AUTHORIZATION
                : (s.contains("timeout") || s.contains("operation_not_permitted") || s.contains("106"))
                    ? PftpFailure.TRANSIENT : PftpFailure.UNKNOWN;
    }

    /** H10/PFTP 106 rejects the start command before a recording is created. Other transport
     *  errors can have an unknown acknowledgement outcome and must be reconciled by ID. */
    private static boolean isDefiniteStartRejection(Throwable t) {
        StringBuilder detail = new StringBuilder();
        for (Throwable c = t; c != null && detail.length() < 1000; c = c.getCause())
            detail.append(' ').append(c.getClass().getName()).append(' ').append(c.getMessage());
        String s = detail.toString().toLowerCase(java.util.Locale.ROOT);
        return s.contains("operation_not_permitted") || s.contains("operation not permitted")
                || s.matches(".*(^|[^0-9])106([^0-9]|$).*");
    }
}
