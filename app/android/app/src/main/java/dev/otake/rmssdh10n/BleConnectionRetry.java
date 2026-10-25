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
    private boolean up;       // an established link, as seen on the control lane
    private boolean waiting;  // a connect deadline, backoff or restart settle owns the next step
    private boolean halted;   // a native request could not be cancelled; never overlap it
    private int attempt;

    BleConnectionRetry(Timer timer, Link link, LongSupplier clock, DoubleSupplier jitter) {
        this.timer = timer; this.link = link; this.clock = clock; this.jitter = jitter;
    }

    void request(String reason) {
        if (stopped || halted || waiting || up || link.connected()) return;
        waiting = true;
        attempt = Math.min(20, attempt + 1);
        link.report("connecting", reason, attempt, clock.getAsLong() + DEADLINE_MS);
        arm(DEADLINE_MS, () -> failed("connect_timeout"));
        try { if (!stopped) link.connect(); }
        catch (Exception error) { failed("connect_error"); }
    }

    /** SDK link loss. An established link hands over to the SDK's own automatic reconnect,
     *  supervised by a deadline without a duplicate request. */
    void disconnected() {
        if (stopped || !up) return;
        up = false;
        link.report("disconnected", "link_lost", attempt, 0);
        waiting = true;
        link.report("connecting", "sdk_reconnect", attempt, clock.getAsLong() + DEADLINE_MS);
        arm(DEADLINE_MS, () -> failed("connect_timeout"));
    }

    void connected() {
        if (stopped) return;
        boolean wasUp = up;
        up = true; waiting = false; halted = false; attempt = 0;
        clearTimer();
        if (!wasUp) link.report("connected", "link_ready", 0, 0);
    }

    /** Intentional clean reconnect: tear the current link down and connect again once the
     *  disconnect has had {@code settleMs} to settle. It never waits for the SDK's
     *  disconnect callback, which arrives late or not at all on a wedged stack. */
    void restart(String reason, long settleMs) {
        if (stopped || halted) return;
        clearTimer();
        up = false; waiting = true;
        link.report("connecting", reason, attempt, clock.getAsLong() + settleMs + DEADLINE_MS);
        if (!cancelled()) return;
        arm(settleMs, () -> { waiting = false; request(reason); });
    }

    /** Connect now; the intentional disconnect before it has already settled. */
    void reconnect(String reason) {
        if (stopped || up || link.connected()) return;
        clearTimer(); waiting = false;
        request(reason);
    }

    /** The Bluetooth adapter power-cycled: every native request died with it, so even a
     *  halted supervisor may connect again, immediately instead of after the backoff. */
    void adapterRestarted() {
        if (stopped || up || link.connected()) return;
        clearTimer(); waiting = false; halted = false;
        request("bt_on");
    }

    private void failed(String reason) {
        if (stopped) return;
        if (link.connected()) { connected(); return; }
        clearTimer();
        if (!cancelled()) return;
        long base = Math.min(240_000L, 30_000L << Math.min(3, Math.max(0, attempt - 1)));
        long delay = (long) (base * (1 + Math.max(0, Math.min(1, jitter.getAsDouble())) * 0.25));
        waiting = true;
        link.report("retry_wait", reason, attempt, clock.getAsLong() + delay);
        arm(delay, () -> { waiting = false; request("deadline_retry"); });
    }

    /** Cancel the pending native request. When that fails, halt instead of overlapping a new
     *  connect with it; a later SDK connection or an adapter restart ends the halt. */
    private boolean cancelled() {
        try { link.cancel(); return !stopped; }
        catch (Exception error) {
            waiting = false; halted = true;
            link.report("needs_action", "connect_cancel_failed", attempt, 0);
            return false;
        }
    }

    private void arm(long delay, Runnable action) {
        clearTimer();
        long token = generation.get();
        Runnable cancel = timer.after(delay, () -> {
            if (!stopped && token == generation.get()) action.run();
        });
        cancelTimer = cancel;
        if (stopped || token != generation.get()) cancel.run();
    }

    private void clearTimer() {
        generation.incrementAndGet();
        Runnable cancel = cancelTimer; cancelTimer = null;
        if (cancel != null) cancel.run();
    }

    /** Stop does not wait for a native Bluetooth call to return. */
    void stop() { stopped = true; clearTimer(); }
}
