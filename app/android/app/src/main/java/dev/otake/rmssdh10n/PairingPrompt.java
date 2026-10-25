package dev.otake.rmssdh10n;

import java.util.function.BooleanSupplier;
import java.util.function.LongSupplier;

/** When the app may ask the user to pair the H10. Android shows the pairing consent as a dialog
 *  only while the app that asked is in front; otherwise it becomes a notification that the 30 s
 *  SMP timeout outlives. A prompt is therefore spent only while the user can answer it, and at
 *  most once per {@link #MIN_INTERVAL_MS}: the connect retries, the recording retries and the
 *  dialog's own dismissal (which resumes the app) would otherwise ask again and again.
 *
 *  One instance is shared by every path that can prompt — pairing before a connect on the
 *  control lane, the recording lane's PFTP gate and its bond reset — so they draw on one budget.
 *  Thread-safe. */
final class PairingPrompt {
    static final long MIN_INTERVAL_MS = 10 * 60_000L;
    private final LongSupplier clock;            // monotonic
    private final BooleanSupplier userCanAnswer;
    private boolean prompted;
    private long promptedAt;

    PairingPrompt(LongSupplier clock, BooleanSupplier userCanAnswer) {
        this.clock = clock; this.userCanAnswer = userCanAnswer;
    }

    /** Whether a prompt may be shown now. Does not spend it. */
    synchronized boolean allowed() {
        return (!prompted || clock.getAsLong() - promptedAt >= MIN_INTERVAL_MS) && userCanAnswer.getAsBoolean();
    }

    /** Spends the prompt when it is allowed; the caller then shows it. */
    synchronized boolean claim() {
        if (!allowed()) return false;
        spent();
        return true;
    }

    /** A prompt shown without asking first (the bond reset re-pairs) still spends the interval. */
    synchronized void spent() {
        prompted = true;
        promptedAt = clock.getAsLong();
    }
}
