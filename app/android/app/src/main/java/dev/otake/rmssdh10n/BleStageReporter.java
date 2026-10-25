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
        Stage(String stage, String reason, int attempt, long nextRetryAt) {
            this.stage = stage; this.reason = reason; this.attempt = attempt; this.nextRetryAt = nextRetryAt;
        }
    }

    private final Out out;
    private Stage current, overlay, beneath;
    private boolean stopped;

    BleStageReporter(Out out) { this.out = out; }

    synchronized void report(String stage, String reason, int attempt, long nextRetryAt) {
        if (!stopped) publish(new Stage(stage, reason, attempt, nextRetryAt));
    }

    synchronized void reportIf(BooleanSupplier stillCurrent, String stage, String reason, int attempt, long nextRetryAt) {
        if (!stopped && stillCurrent.getAsBoolean()) publish(new Stage(stage, reason, attempt, nextRetryAt));
    }

    synchronized void beginOverlay(String stage, String reason, int attempt, long nextRetryAt) {
        if (stopped) return;
        Stage covered = current == overlay ? beneath : current;
        overlay = new Stage(stage, reason, attempt, nextRetryAt);
        publish(overlay);
        beneath = covered;
    }

    synchronized void endOverlay() {
        Stage restore = current == overlay ? beneath : null;
        overlay = null; beneath = null;
        if (!stopped && restore != null) publish(restore);
    }

    /** Terminal: publishes the stop stage once and rejects every later report. */
    synchronized void stop(String reason) {
        if (stopped) return;
        publish(new Stage("stopped", reason, 0, 0));
        stopped = true;
    }

    private void publish(Stage stage) {
        current = stage;
        try { out.stage(stage.stage, stage.reason, stage.attempt, stage.nextRetryAt); }
        catch (Throwable ignored) {}
    }
}
