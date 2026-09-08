package dev.otake.rmssdh10n;

/** Explicit terminal/retry ownership for recording recovery failures. */
final class RecordingRecoveryPolicy {
    enum Failure { PFTP, STORAGE }
    enum Action { RETRY, PAUSE_STORAGE, RECONNECT, REBOND, NEEDS_ACTION }
    private RecordingRecoveryPolicy() {}

    static Action decide(Failure failure, int attempts, int maxAttempts,
            int reconnects, int maxReconnects, boolean authorizationEvidence,
            boolean rrFresh, boolean rebondTried) {
        if (attempts < maxAttempts) return Action.RETRY;
        if (failure == Failure.STORAGE) return Action.PAUSE_STORAGE;
        if (reconnects < maxReconnects) return Action.RECONNECT;
        if (authorizationEvidence && rrFresh && !rebondTried) return Action.REBOND;
        return Action.NEEDS_ACTION;
    }
}
