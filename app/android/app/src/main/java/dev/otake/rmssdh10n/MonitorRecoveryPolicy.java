package dev.otake.rmssdh10n;

/** Restart eligibility shared by boot, foreground return and the durable recovery job. */
final class MonitorRecoveryPolicy {
    enum Decision { STOPPED, LOCKED, PERMISSION_REQUIRED, RUNNING, COOLDOWN, START }

    static Decision decide(String engine, String device, boolean unlocked, boolean permitted,
            boolean running, long now, long nextAttempt) {
        if (!"native".equals(engine) || DeviceSelection.normalize(device) == null) return Decision.STOPPED;
        if (!unlocked) return Decision.LOCKED;
        if (!permitted) return Decision.PERMISSION_REQUIRED;
        if (running) return Decision.RUNNING;
        // A wall-clock rollback must not defer recovery indefinitely.
        if (nextAttempt > now && nextAttempt - now <= 15 * 60_000L) return Decision.COOLDOWN;
        return Decision.START;
    }

    static long retryDelay(int failures, double jitter) {
        long base = Math.min(12 * 60_000L, 30_000L << Math.min(5, Math.max(0, failures - 1)));
        return (long) (base * (1 + Math.max(0, Math.min(1, jitter)) * 0.25));
    }

    private MonitorRecoveryPolicy() {}
}
