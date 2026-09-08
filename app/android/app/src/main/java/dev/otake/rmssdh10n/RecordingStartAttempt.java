package dev.otake.rmssdh10n;

import java.util.function.LongSupplier;

/** Binds one start command to one durable ID and wall-clock anchor. */
final class RecordingStartAttempt {
    enum Resolution { ACTIVE_UNCERTAIN, RETRY_NEW_ATTEMPT, PRESERVE_UNCERTAIN }
    final String exId;
    final long anchorStartMs;

    private RecordingStartAttempt(String exId, long anchorStartMs) {
        this.exId = exId;
        this.anchorStartMs = anchorStartMs;
    }

    static RecordingStartAttempt create(LongSupplier clock) {
        long anchor = clock.getAsLong();
        return new RecordingStartAttempt("rmssd-" + anchor, anchor);
    }

    static Resolution resolveFailure(boolean definitelyNotStarted, RecordingOwnership.Kind probe,
            String expectedId, String actualId) {
        if (definitelyNotStarted || probe == RecordingOwnership.Kind.INACTIVE) return Resolution.RETRY_NEW_ATTEMPT;
        if (probe == RecordingOwnership.Kind.OURS && expectedId != null && expectedId.equals(actualId))
            return Resolution.ACTIVE_UNCERTAIN;
        return Resolution.PRESERVE_UNCERTAIN;
    }

    /** A persisted 'starting' row has no acknowledgement (for example after process death),
     *  so its exact start wall time is uncertain even if the matching device slot is active. */
    static boolean requiresQuarantine(String state) {
        return "starting".equals(state) || (state != null && state.endsWith("_uncertain"));
    }
}
