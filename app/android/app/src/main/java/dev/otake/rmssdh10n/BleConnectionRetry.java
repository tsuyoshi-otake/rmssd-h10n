package dev.otake.rmssdh10n;

import java.util.concurrent.atomic.AtomicLong;
import java.util.function.DoubleSupplier;
import java.util.function.LongSupplier;

/** Owns one connection deadline/retry. All events except stop run on the BLE control lane. */
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
    private boolean waiting;
    private int attempt;

    BleConnectionRetry(Timer timer, Link link, LongSupplier clock, DoubleSupplier jitter) {
        this.timer = timer; this.link = link; this.clock = clock; this.jitter = jitter;
    }

    void request(String reason) {
        if (stopped || waiting || link.connected()) return;
        waiting = true;
        attempt = Math.min(20, attempt + 1);
        link.report("connecting", reason, attempt, clock.getAsLong() + DEADLINE_MS);
        arm(DEADLINE_MS, () -> failed("connect_timeout"));
        try { if (!stopped) link.connect(); }
        catch (Exception error) { failed("connect_error"); }
    }

    /** SDK owns its normal automatic reconnect; supervise that wait without a duplicate request. */
    void disconnected() {
        if (stopped || waiting) return;
        waiting = true;
        link.report("connecting", "sdk_reconnect", attempt, clock.getAsLong() + DEADLINE_MS);
        arm(DEADLINE_MS, () -> failed("connect_timeout"));
    }

    void connected() {
        waiting = false; attempt = 0;
        clearTimer();
    }

    /** An intentional clean disconnect has already settled before this explicit request. */
    void reconnect(String reason) {
        if (stopped || link.connected()) return;
        clearTimer(); waiting = false;
        request(reason);
    }

    private void failed(String reason) {
        if (stopped) return;
        if (link.connected()) { connected(); return; }
        clearTimer();
        try { link.cancel(); }
        catch (Exception error) {
            // Do not overlap a new connect with a request whose cancellation failed.
            waiting = false;
            link.report("needs_action", "connect_cancel_failed", attempt, 0);
            stop();
            return;
        }
        if (stopped) return;
        long base = Math.min(240_000L, 30_000L << Math.min(3, Math.max(0, attempt - 1)));
        long delay = (long) (base * (1 + Math.max(0, Math.min(1, jitter.getAsDouble())) * 0.25));
        waiting = true;
        link.report("retry_wait", reason, attempt, clock.getAsLong() + delay);
        arm(delay, () -> { waiting = false; request("deadline_retry"); });
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
