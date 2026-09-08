package dev.otake.rmssdh10n;

import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledThreadPoolExecutor;
import java.util.concurrent.ThreadFactory;

/** Two bounded single-lane queues: live control stays responsive while serial PFTP waits. */
final class BleControlQueue {
    private final ScheduledThreadPoolExecutor control;
    private final ScheduledThreadPoolExecutor recording;

    BleControlQueue() {
        control = lane("polar-ble");
        recording = lane("polar-recording");
    }

    ScheduledExecutorService control() { return control; }
    ScheduledExecutorService recording() { return recording; }

    int pendingControl() { return control.getQueue().size(); }
    int pendingRecording() { return recording.getQueue().size(); }

    void shutdownNow() {
        control.shutdownNow();
        recording.shutdownNow();
    }

    private static ScheduledThreadPoolExecutor lane(String name) {
        ThreadFactory factory = task -> {
            Thread thread = new Thread(task, name);
            thread.setDaemon(true);
            return thread;
        };
        ScheduledThreadPoolExecutor executor = new ScheduledThreadPoolExecutor(1, factory);
        executor.setRemoveOnCancelPolicy(true);
        executor.setExecuteExistingDelayedTasksAfterShutdownPolicy(false);
        executor.setContinueExistingPeriodicTasksAfterShutdownPolicy(false);
        return executor;
    }
}
