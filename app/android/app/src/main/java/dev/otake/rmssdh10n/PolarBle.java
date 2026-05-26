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
import java.util.concurrent.Executors;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
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
        /** Recording acknowledged by the H10 → 'active'. */
        void recActive(String exId, long startAckMs);
        /** Fetched from the device → stamp counts + truncation, state 'fetching'. */
        void recFetching(String exId, long rrCount, long durationMs, boolean truncated);
        /** Replay + durably persist the gap RR (+ ledger). Return true once durable; the
         *  device exercise is removed only on true so a failure retries idempotently. */
        boolean recPersistGap(double[] rrMs, long anchorStartMs, String exId, boolean truncated);
        /** Device exercise removed → 'removed' (terminal). */
        void recRemoved(String exId);

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

    private final ScheduledExecutorService exec = Executors.newSingleThreadScheduledExecutor(r -> {
        Thread t = new Thread(r, "polar-ble");
        t.setDaemon(true);
        return t;
    });

    private PolarBleApi api;
    private volatile Disposable hrDis;
    private volatile Disposable accDis;
    private volatile ScheduledFuture<?> accDutyFuture; // repeating ACC duty-cycle task (null = none)
    private volatile boolean accDutyCycle = false;     // power-save ACC mode (runtime-toggleable); true = burst-sample. Default OFF = continuous
    private volatile boolean stopping = false;
    private volatile boolean recordingActive = false;
    private volatile boolean linkConnected = false;   // BLE link up — gates PFTP ops
    private volatile boolean externalBlocking = false;// a non-owned recording occupies the slot
    private int recordingAttempt = 0;                 // exec-thread only — PFTP retry counter
    private int recoveryReconnects = 0;               // exec-thread only — bounded forced reconnects on stuck PFTP
    // CAS-guarded so duplicate feature-ready callbacks can't queue two recording jobs.
    private final AtomicBoolean recordingHandledThisConn = new AtomicBoolean(false);
    // Bumped on every (re)connect. The recording job + its retries capture the gen they
    // belong to and bail if a newer connection has superseded them — so a retry scheduled
    // under a dropped connection never runs against a fresh one (the counters above are
    // reset per-connection on exec, keeping them exec-thread-confined).
    private final AtomicInteger connGen = new AtomicInteger(0);
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
    public boolean accDutyCycled() { return withAcc && accDutyCycle; }

    public void start() {
        exec.execute(() -> {
            try {
                PolarBonding.ensureBonded(ctx, id, sink::log); // pair first — PFTP needs an encrypted link
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

    /** Run work on the BLE worker, swallowing the rejection that happens if a teardown
     *  already shut the executor down (callbacks can still fire mid-stop). */
    private void execSafe(Runnable r) {
        if (stopping) return;
        try { exec.execute(r); } catch (RejectedExecutionException ignored) {}
    }

    /** Re-subscribe the live streams when the link is 'connected' but no data is flowing
     *  (a stream subscription errored). Gentle on purpose: it does NOT disconnect — an
     *  explicit disconnect won't auto-reconnect (the SDK treats it as intentional) and a
     *  reconnect needs a scan, which can't run with the screen off; repeated disconnect
     *  churn also wedges the BLE scanner. If the H10 is truly orphaned (after a force-stop /
     *  abrupt kill), only a Bluetooth toggle clears it. Driven by HrvEngine's watchdog. */
    public void nudgeStreams() {
        if (stopping || api == null) return;
        execSafe(() -> {
            if (stopping || api == null || !linkConnected) return;
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
        execSafe(this::cleanReconnect);
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
            linkConnected = true;
            recordingHandledThisConn.set(false);
            connGen.incrementAndGet();              // supersede retries scheduled under a prior connection
            execSafe(() -> recordingAttempt = 0);   // reset the per-connection retry budget on exec
            sink.onConnected(true);
            sink.log("connected " + info.getDeviceId());
        }
        @Override public void deviceConnecting(PolarDeviceInfo info) { sink.log("connecting…"); }
        @Override public void deviceDisconnected(PolarDeviceInfo info) {
            linkConnected = false;
            sink.onConnected(false);
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
                            try { exec.schedule(() -> runRecordingOnConnect(gen), RECORDING_DEFER_S, TimeUnit.SECONDS); }
                            catch (RejectedExecutionException ignored) {}
                        }
                    }
                    break;
                default:
                    break;
            }
        }
        @Override public void batteryLevelReceived(String identifier, int level) {
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
            if (api == null || (hrDis != null && !hrDis.isDisposed())) return;
            hrDis = api.startHrStreaming(id).subscribe(
                    data -> {
                        for (PolarHrData.PolarHrSample s : data.getSamples()) {
                            sink.onHr(s.getHr());
                            for (Integer rr : s.getRrsMs()) sink.onRr(rr.doubleValue());
                        }
                    },
                    err -> { sink.log("hr stream err: " + err.getMessage()); hrDis = null; });
        }
    }

    private void startAcc() {
        synchronized (streamLock) {
            if (api == null || (accDis != null && !accDis.isDisposed())) return;
            // Query the device's actual ACC capabilities and pick from them (25 Hz / ±2 G /
            // 16-bit when offered) instead of hardcoding — the H10 rejects unsupported combos
            // (a wrong/extra key made startAccStreaming error and posture never got samples).
            accDis = api.requestStreamSettings(id, PolarBleApi.PolarDeviceDataType.ACC)
                    .toFlowable()
                    .flatMap(available -> api.startAccStreaming(id, pickAccSetting(available)))
                    .subscribe(
                            data -> {
                                for (PolarAccelerometerData.PolarAccelerometerDataSample s : data.getSamples()) {
                                    sink.onAcc(s.getX(), s.getY(), s.getZ());
                                }
                            },
                            err -> { sink.log("acc stream err: " + err.getMessage()); accDis = null; });
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
        if (!withAcc) return;
        if (!accDutyCycle) { startAcc(); return; }
        cancelAccDuty();
        final int gen = connGen.get();
        Runnable burst = () -> {
            if (stopping || api == null || !linkConnected || gen != connGen.get()) return;
            startAcc();
            try { exec.schedule(() -> { if (gen == connGen.get()) disposeAcc(); }, ACC_DUTY_ON_MS, TimeUnit.MILLISECONDS); }
            catch (RejectedExecutionException ignored) {}
        };
        try { accDutyFuture = exec.scheduleAtFixedRate(burst, 0, ACC_DUTY_PERIOD_MS, TimeUnit.MILLISECONDS); }
        catch (RejectedExecutionException ignored) {}
    }

    private void cancelAccDuty() {
        ScheduledFuture<?> f = accDutyFuture; accDutyFuture = null;
        if (f != null) { try { f.cancel(false); } catch (Throwable ignored) {} }
    }

    /** Switch the ACC power mode at runtime (dashboard 省電力 toggle): true = burst/duty
     *  (low power, posture-only), false = continuous (full posture + steps). Re-applies to
     *  the live ACC stream without touching HR/RR. */
    public void setAccDutyCycle(boolean on) {
        if (accDutyCycle == on) return;
        accDutyCycle = on;
        execSafe(() -> {
            if (stopping || api == null || !linkConnected || !withAcc) return;
            cancelAccDuty();
            disposeAcc();
            beginAcc(); // re-reads accDutyCycle: continuous or burst
        });
    }

    // --- recording lifecycle (exec thread; blocking RxJava is fine here) -----
    private enum FetchResult { PERSISTED, EMPTY, FAILED }
    private enum SlotClass { EMPTY, OURS_NO_META, FOREIGN }

    /** Drive the single H10 slot on (re)connect. Recovers a DB-persisted recording if
     *  present (across reconnects AND app/OS restarts), else starts fresh while guarding
     *  a foreign slot. On a transient PFTP failure it retries rather than overwriting the
     *  not-yet-recovered gap. */
    private void runRecordingOnConnect(int gen) {
        if (stopping || api == null || !linkConnected || gen != connGen.get()) return;
        boolean needRetry = false;
        try {
            RecordingStore store = recordingStore;
            RecordingStore.OpenRec open = (store != null) ? store.getOpenRecording() : null;
            if (open != null) {
                // Unifies same-process reconnect AND post-restart recovery: the anchor and
                // exId come from the DB, so even a brand-new process recovers the gap.
                if (recoverRecording(open) == FetchResult.FAILED) needRetry = true; // keep the gap
            } else if (classifySlot() == SlotClass.FOREIGN) {
                externalBlocking = true;
                sink.log("external recording present — not starting (slot not ours)");
                return; // never auto-delete a non-owned recording (e.g. Polar Beat)
            }
            externalBlocking = false;
            // startFreshRecording clears the slot first (stop + remove ours), so an EMPTY or
            // OURS_NO_META slot needs nothing extra here. A failed start → retry (don't give up).
            if (!needRetry && !startFreshRecording()) needRetry = true;
        } catch (Throwable t) {
            sink.log("recording-on-connect failed: " + t.getMessage());
            needRetry = true;
        }
        if (!needRetry) { recoveryReconnects = 0; return; } // recovered/started — clear the reconnect budget
        if (!stopping && linkConnected && gen == connGen.get()) {
            if (recordingAttempt < MAX_RECORDING_ATTEMPTS) {
                recordingAttempt++;
                sink.log("recording retry " + recordingAttempt + "/" + MAX_RECORDING_ATTEMPTS
                        + " in " + RETRY_DELAY_S + "s");
                try { exec.schedule(() -> runRecordingOnConnect(gen), RETRY_DELAY_S, TimeUnit.SECONDS); }
                catch (RejectedExecutionException ignored) {}
            } else if (recoveryReconnects < MAX_RECOVERY_RECONNECTS) {
                // PFTP stayed 106 through every retry on THIS link. A phone-side abrupt drop
                // (e.g. a Bluetooth toggle after a force-stop) can wedge the H10's PFTP so it
                // never clears on the same connection — only a fresh link does (what re-strapping
                // the H10 achieves). Force one clean reconnect and let recovery retry on the fresh
                // connection (recordingAttempt resets in deviceConnected).
                recoveryReconnects++;
                sink.log("recording stuck after retries — forcing clean reconnect "
                        + recoveryReconnects + "/" + MAX_RECOVERY_RECONNECTS);
                forceReconnectForRecording();
            } else {
                sink.log("recording recovery gave up (PFTP 106 persists) — re-strap H10 to recover");
            }
        }
    }

    /** Recovery's PFTP is wedged on the current link — get a fresh link so recovery retries on
     *  a new connection (recordingAttempt resets in deviceConnected). Already on exec. */
    private void forceReconnectForRecording() { cleanReconnect(); }

    /** Disconnect then reconnect to obtain a fresh link (live streams or recording wedged on
     *  the current one). An explicit disconnect disables the SDK's auto-reconnect, so we issue
     *  the reconnect ourselves after a short gap. Must run on exec. */
    private void cleanReconnect() {
        if (stopping || api == null) return;
        try { api.disconnectFromDevice(id); } catch (Throwable ignored) {}
        try {
            exec.schedule(() -> {
                if (stopping || api == null) return;
                try { sink.log("reconnecting after stall"); api.connectToDevice(id); }
                catch (Throwable t) { sink.log("reconnect failed: " + t.getMessage()); }
            }, 3, TimeUnit.SECONDS);
        } catch (RejectedExecutionException ignored) {}
    }

    /** Stop → fetch → replay+persist → remove a recoverable recording. */
    private FetchResult recoverRecording(RecordingStore.OpenRec open) {
        try {
            // Stop FIRST. An actively-recording exercise is NOT listable until it is
            // finalized, so listing before stopping makes matchEntry find nothing and we
            // would wrongly discard a live gap. No-op if already stopped (memory full).
            stopRecordingQuietly();
            recordingActive = false;
            List<PolarExerciseEntry> entries = listExercises();
            if (entries == null) {
                // Listing FAILED (PFTP 106 / timeout) — NOT the same as "no recording". We must
                // not conclude it is gone: that would drop the anchor (recRemoved) and let the
                // subsequent startFresh delete the un-fetched gap. Keep it and retry next time.
                sink.log("recover: listExercises failed — keeping recording for retry");
                return FetchResult.FAILED;
            }
            PolarExerciseEntry entry = matchEntry(entries, open.exId);
            if (entry == null) {
                // Listing SUCCEEDED and there is genuinely no matching exercise (start never
                // persisted on-device, or already removed) → safe to mark removed.
                sink.log("recover: no device entry for " + open.exId + " — marking removed");
                RecordingStore s = recordingStore;
                if (s != null) s.recRemoved(open.exId);
                return FetchResult.EMPTY;
            }
            return fetchAndBackfill(entry, open.anchorStartMs, open.exId);
        } catch (Throwable t) {
            sink.log("recover failed: " + t.getMessage());
            return FetchResult.FAILED;
        }
    }

    private FetchResult fetchAndBackfill(PolarExerciseEntry entry, long anchorStartMs, String exId) {
        if (anchorStartMs <= 0) { sink.log("backfill: no valid start anchor"); return FetchResult.EMPTY; }
        RecordingStore store = recordingStore;
        if (store == null) return FetchResult.FAILED;
        try {
            PolarExerciseData data = api.fetchExercise(id, entry)
                    .timeout(FETCH_TIMEOUT_S, TimeUnit.SECONDS).blockingGet();
            List<Integer> samples = data != null ? data.getHrSamples() : null; // RR ms (recorded as RR)
            int n = samples != null ? samples.size() : 0;
            if (n < 2) {
                sink.log("backfill: " + n + " samples (nothing to replay)");
                try { api.removeExercise(id, entry).timeout(OP_TIMEOUT_S, TimeUnit.SECONDS).blockingAwait(); }
                catch (Throwable ignored) {}
                store.recRemoved(exId);
                return FetchResult.EMPTY;
            }
            double[] rr = new double[n];
            long sum = 0;
            for (int i = 0; i < n; i++) { rr[i] = samples.get(i); sum += samples.get(i); }
            boolean truncated = detectTruncated(n, sum, anchorStartMs);
            store.recFetching(exId, n, sum, truncated);
            boolean persisted = store.recPersistGap(rr, anchorStartMs, exId, truncated);
            if (!persisted) {
                sink.log("backfill not persisted; keeping exercise for retry");
                return FetchResult.FAILED; // retry next reconnect — idempotent (same anchor → same seconds)
            }
            // Durable. Remove from the device; if remove fails the recording stays open
            // ('persisted') and a later retry re-fetches idempotently before reclaiming.
            try {
                api.removeExercise(id, entry).timeout(OP_TIMEOUT_S, TimeUnit.SECONDS).blockingAwait();
                store.recRemoved(exId);
                return FetchResult.PERSISTED;
            } catch (Throwable t) {
                sink.log("removeExercise failed (data persisted): " + t.getMessage());
                return FetchResult.FAILED; // don't start fresh yet — retry the remove
            }
        } catch (Throwable t) {
            sink.log("fetchAndBackfill failed: " + t.getMessage());
            return FetchResult.FAILED;
        }
    }

    private boolean startFreshRecording() {
        if (stopping || api == null || !linkConnected) return false;
        // No setLocalTime: the H10 doesn't support the time feature cleanly (see start()),
        // and backfill is start-anchored to our own clock, so device time is not needed.
        // Ensure the single slot is FREE first: stop any active recording (e.g. one left
        // running across an app update) and remove our stale/stopped exercises — a non-empty
        // slot makes startRecording return OPERATION_NOT_PERMITTED(106). A FOREIGN slot was
        // already excluded by the caller, so removeOurExercises only ever touches ours.
        stopRecordingQuietly();
        removeOurExercises();
        String exId = EX_PREFIX + System.currentTimeMillis();
        long startReqMs = System.currentTimeMillis();
        RecordingStore store = recordingStore;
        if (store != null) store.recStarting(exId, startReqMs); // persist BEFORE the call (survives a kill)
        boolean ok = false;
        for (int attempt = 1; attempt <= 3 && !ok && !stopping && linkConnected; attempt++) {
            try {
                api.startRecording(id, exId,
                        PolarH10OfflineExerciseApi.RecordingInterval.INTERVAL_1S,
                        PolarH10OfflineExerciseApi.SampleType.RR)
                        .timeout(OP_TIMEOUT_S, TimeUnit.SECONDS).blockingAwait();
                ok = true;
            } catch (Throwable t) {
                sink.log("startRecording attempt " + attempt + " failed: " + t.getMessage());
                if (attempt < 3) sleepQuietly(RETRY_DELAY_S * 1000L);
            }
        }
        if (ok) {
            recordingActive = true;
            if (store != null) store.recActive(exId, System.currentTimeMillis());
            sink.log("RR recording started");
            return true;
        }
        recordingActive = false;
        // Leave meta 'starting': the next attempt's getOpenRecording self-heals it (no device
        // exercise exists for a failed start → matchEntry null → marked removed → fresh start).
        sink.log("startRecording failed after retries");
        return false;
    }

    // --- recording helpers ---------------------------------------------------
    private List<PolarExerciseEntry> listExercises() {
        try {
            return api.listExercises(id).toList().timeout(LIST_TIMEOUT_S, TimeUnit.SECONDS).blockingGet();
        } catch (Throwable t) {
            sink.log("listExercises failed: " + t.getMessage());
            return null;
        }
    }

    /** Stop the active recording if any — best-effort (errors when none is running). */
    private void stopRecordingQuietly() {
        try { api.stopRecording(id).timeout(OP_TIMEOUT_S, TimeUnit.SECONDS).blockingAwait(); }
        catch (Throwable ignored) {}
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
        if (entries == null || entries.isEmpty()) return SlotClass.EMPTY;
        for (PolarExerciseEntry e : entries) {
            String idf = entryIdentifier(e);
            if (idf == null || !idf.startsWith(EX_PREFIX)) return SlotClass.FOREIGN; // not ours
        }
        return SlotClass.OURS_NO_META; // ours, but no recoverable meta (old / un-anchored)
    }

    /** Remove only OUR exercises (rmssd- prefix) to reclaim the slot; never a foreign one. */
    private void removeOurExercises() {
        List<PolarExerciseEntry> entries = listExercises();
        if (entries == null) return;
        int removed = 0;
        for (PolarExerciseEntry e : entries) {
            String idf = entryIdentifier(e);
            if (idf == null || !idf.startsWith(EX_PREFIX)) continue;
            try { api.removeExercise(id, e).timeout(OP_TIMEOUT_S, TimeUnit.SECONDS).blockingAwait(); removed++; }
            catch (Throwable t) { sink.log("remove ours failed: " + t.getMessage()); }
        }
        if (removed > 0) sink.log("reclaimed " + removed + " un-anchored recording(s)");
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

    private static void sleepQuietly(long ms) {
        try { Thread.sleep(ms); } catch (InterruptedException ignored) { Thread.currentThread().interrupt(); }
    }
}
