package dev.otake.rmssdh10n;

import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.function.BooleanSupplier;

/** Owns every ACC duty-cycle timer so a mode change invalidates old burst endings. */
final class AccStreamSchedule {
    private final ScheduledExecutorService executor;
    private ScheduledFuture<?> cycle, end;
    private int generation;

    AccStreamSchedule(ScheduledExecutorService executor) { this.executor = executor; }

    synchronized int generation() { return generation; }

    synchronized void begin(boolean duty, long onMs, long periodMs, BooleanSupplier eligible,
            Runnable start, Runnable stop) {
        cancelLocked();
        if (!duty) { if (eligible.getAsBoolean()) start.run(); return; }
        final int token = generation;
        Runnable burst = () -> {
            synchronized (AccStreamSchedule.this) {
                if (token != generation || !eligible.getAsBoolean()) return;
                start.run();
                if (end != null) end.cancel(false);
                try {
                    end = executor.schedule(() -> {
                        synchronized (AccStreamSchedule.this) {
                            if (token == generation && eligible.getAsBoolean()) stop.run();
                        }
                    }, onMs, TimeUnit.MILLISECONDS);
                } catch (RejectedExecutionException ignored) {}
            }
        };
        try { cycle = executor.scheduleAtFixedRate(burst, 0, periodMs, TimeUnit.MILLISECONDS); }
        catch (RejectedExecutionException ignored) {}
    }

    synchronized void cancel() { cancelLocked(); }

    private void cancelLocked() {
        generation++;
        if (cycle != null) cycle.cancel(false);
        if (end != null) end.cancel(false);
        cycle = null; end = null;
    }
}
