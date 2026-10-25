package dev.otake.rmssdh10n;

import java.util.function.BooleanSupplier;

/** Single funnel for publishing BLE lifecycle stages from the SDK main thread, the control
 *  lane, the recording lane and the selection-scan subscriber. It guarantees that
 *  - nothing is published after the terminal stop stage;
 *  - a stage guarded by {@code stillCurrent} (a recording job of one link generation) is
 *    checked and published atomically, so a superseded job cannot overwrite a newer state;
 *  - a selection scan is an overlay: when it ends, the stage it covered is published again
 *    unless something newer replaced the scan in the meantime. */
final class BleStageReporter {
    interface Out { void stage(String stage, String reason, int attempt, long nextRetryAt); }

    private static final class Stage {
        final String stage, reason;
        final int attempt;
        final long nextRetryAt;
        final boolean overlay;
        Stage(String stage, String reason, int attempt, long nextRetryAt, boolean overlay) {
            this.stage = stage; this.reason = reason; this.attempt = attempt; this.nextRetryAt = nextRetryAt;
            this.overlay = overlay;
        }
    }

    private final Out out;
    private Stage current, beneath;
    private boolean stopped;

    BleStageReporter(Out out) { this.out = out; }

    synchronized void report(String stage, String reason, int attempt, long nextRetryAt) {
        if (!stopped) publish(new Stage(stage, reason, attempt, nextRetryAt, false));
    }

    synchronized void reportIf(BooleanSupplier stillCurrent, String stage, String reason, int attempt, long nextRetryAt) {
        if (!stopped && stillCurrent.getAsBoolean()) publish(new Stage(stage, reason, attempt, nextRetryAt, false));
    }

    /** An overlay still on display (even one whose scan already ended with nothing to
     *  restore) is never what a later overlay covers. */
    synchronized void beginOverlay(String stage, String reason, int attempt, long nextRetryAt) {
        if (stopped) return;
        Stage covered = shownOverlay() ? beneath : current;
        publish(new Stage(stage, reason, attempt, nextRetryAt, true));
        beneath = covered;
    }

    synchronized void endOverlay() {
        Stage restore = shownOverlay() ? beneath : null;
        beneath = null;
        if (!stopped && restore != null) publish(restore);
    }

    /** Terminal: publishes the stop stage once and rejects every later report. */
    synchronized void stop(String reason) {
        if (stopped) return;
        publish(new Stage("stopped", reason, 0, 0, false));
        stopped = true;
    }

    private boolean shownOverlay() { return current != null && current.overlay; }

    private void publish(Stage stage) {
        current = stage;
        try { out.stage(stage.stage, stage.reason, stage.attempt, stage.nextRetryAt); }
        catch (Throwable ignored) {}
    }
}
