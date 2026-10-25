package dev.otake.rmssdh10n;

import java.util.concurrent.atomic.AtomicLong;
import java.util.function.DoubleSupplier;
import java.util.function.LongSupplier;

/** Owns the link lifecycle: whether the link is up, the one connection deadline/retry, and
 *  every link-level stage it reports. All events except stop run on the BLE control lane.
 *
 *  The Polar SDK posts deviceDisconnected for any session leaving OPEN/OPENING/OPEN_PARK,
 *  including the session our own deadline cancel closes. Only the loss of an established
 *  link is therefore reported as link_lost; the echo of a cancel or an intentional restart
 *  is ignored, because the deadline/settle timer armed with it already owns the outcome. */
final class BleConnectionRetry {
    interface Timer { Runnable after(long delayMs, Runnable action); }
    interface Link {
        boolean connected();
        void connect() throws Exception;
        void cancel() throws Exception;
        void report(String stage, String reason, int attempt, long nextAt);
    }
    static final long DEADLINE_MS = 60_000;
    private final Timer timer;
    private final Link link;
    private final LongSupplier clock;
    private final DoubleSupplier jitter;
    private final AtomicLong generation = new AtomicLong();
    private volatile boolean stopped;
    private volatile Runnable cancelTimer;
    /** Who owns the next step, as seen on the control lane: nothing yet (IDLE), a connect
     *  deadline, backoff or restart settle (WAITING), the established link (UP), or nobody until
     *  the SDK connects or the adapter restarts, because a native request could not be
     *  cancelled (HALTED). */
    private enum Phase { IDLE, WAITING, UP, HALTED }
    private Phase phase = Phase.IDLE;
    private int attempt;
    private String stage, reason;   // the last link stage reported, for a stale connected()
    private int stageAttempt;
    private long stageNextAt;

    BleConnectionRetry(Timer timer, Link link, LongSupplier clock, DoubleSupplier jitter) {
        this.timer = timer; this.link = link; this.clock = clock; this.jitter = jitter;
    }

    void request(String reason) {
        if (stopped || phase != Phase.IDLE) return;
        // The SDK got there first: own the link now instead of handing off to a queued
        // connected() that may already be stale when the control lane reaches it.
        if (link.connected()) { connected(); return; }
        phase = Phase.WAITING;
        attempt = Math.min(20, attempt + 1);
        report("connecting", reason, attempt, clock.getAsLong() + DEADLINE_MS);
        arm(DEADLINE_MS, () -> failed("connect_timeout"));
        try { if (!stopped) link.connect(); }
        catch (Exception error) { failed("connect_error"); }
    }

    /** SDK link loss. An established link hands over to the SDK's own automatic reconnect,
     *  supervised by a deadline without a duplicate request. */
    void disconnected() {
        if (stopped || phase != Phase.UP) return;
        phase = Phase.WAITING;
        report("disconnected", "link_lost", attempt, 0);
        report("connecting", "sdk_reconnect", attempt, clock.getAsLong() + DEADLINE_MS);
        arm(DEADLINE_MS, () -> failed("connect_timeout"));
    }

    /** The SDK reported a link. A report the control lane sees only after the link was
     *  marked down again (an intentional restart, or the loss already processed on the main
     *  thread) is stale: the deadline, settle or halt that owns the next step stays. The
     *  recording lane may have shown that short-lived link meanwhile, so the owner's stage is
     *  put back; an up supervisor is about to see the loss itself. */
    void connected() {
        if (stopped) return;
        if (!link.connected()) {
            if (phase != Phase.UP && stage != null) link.report(stage, reason, stageAttempt, stageNextAt);
            return;
        }
        boolean wasUp = phase == Phase.UP;
        phase = Phase.UP; attempt = 0;
        clearTimer();
        if (!wasUp) report("connected", "link_ready", 0, 0);
    }

    /** Intentional clean reconnect: tear the current link down and connect again once the
     *  disconnect has had {@code settleMs} to settle. It never waits for the SDK's
     *  disconnect callback, which arrives late or not at all on a wedged stack. */
    void restart(String reason, long settleMs) {
        if (stopped || phase == Phase.HALTED) return;
        clearTimer();
        phase = Phase.WAITING;
        report("connecting", reason, attempt, clock.getAsLong() + settleMs + DEADLINE_MS);
        if (!cancelled()) return;
        arm(settleMs, () -> { phase = Phase.IDLE; request(reason); });
    }

    /** Connect afresh now (after a rebond). The SDK ignores connectToDevice unless its
     *  session is closed, so whatever may still be pending — the SDK's own parked reconnect
     *  or a connect of ours issued when a settle fired first — is cancelled before it. */
    void reconnect(String reason) {
        if (stopped || phase == Phase.HALTED || phase == Phase.UP || link.connected()) return;
        clearTimer(); phase = Phase.IDLE;
        if (cancelled()) request(reason);
    }

    /** The Bluetooth adapter power-cycled: connect again immediately instead of after the
     *  backoff, even from a halt. Power-off parks an open session, and the SDK ignores
     *  connectToDevice on any session that is not closed, so the session is cancelled first
     *  (this also covers a connect of ours issued after the adapter came back but before
     *  this broadcast); the supervisor stays halted if that cancel fails. */
    void adapterRestarted() {
        if (stopped || phase == Phase.UP || link.connected()) return;
        clearTimer(); phase = Phase.IDLE;
        if (cancelled()) request("bt_on");
    }

    /** A deadline expired or a connect threw; the supervisor is WAITING and stays so through
     *  the backoff. */
    private void failed(String reason) {
        if (stopped) return;
        if (link.connected()) { connected(); return; }
        clearTimer();
        if (!cancelled()) return;
        long base = 30_000L << Math.min(3, Math.max(0, attempt - 1));   // 30 s .. 240 s
        long delay = (long) (base * (1 + Math.max(0, Math.min(1, jitter.getAsDouble())) * 0.25));
        report("retry_wait", reason, attempt, clock.getAsLong() + delay);
        arm(delay, () -> { phase = Phase.IDLE; request("deadline_retry"); });
    }

    /** Cancel the pending native request. When that fails, halt: the SDK would ignore a new
     *  connect on the session still open; a later SDK connection or an adapter restart ends
     *  the halt. */
    private boolean cancelled() {
        try { link.cancel(); return !stopped; }
        catch (Exception error) {
            phase = Phase.HALTED;
            report("needs_action", "connect_cancel_failed", attempt, 0);
            return false;
        }
    }

    private void report(String stage, String reason, int attempt, long nextAt) {
        this.stage = stage; this.reason = reason; stageAttempt = attempt; stageNextAt = nextAt;
        link.report(stage, reason, attempt, nextAt);
    }

    private void arm(long delay, Runnable action) {
        clearTimer();
        long token = generation.get();
        Runnable cancel = timer.after(delay, () -> {
            if (!stopped && token == generation.get()) action.run();
        });
        cancelTimer = cancel;
        if (stopped) cancel.run();   // stop() ran on its own thread while this timer was armed
    }

    private void clearTimer() {
        generation.incrementAndGet();
        Runnable cancel = cancelTimer; cancelTimer = null;
        if (cancel != null) cancel.run();
    }

    /** Stop does not wait for a native Bluetooth call to return. */
    void stop() { stopped = true; clearTimer(); }
}
