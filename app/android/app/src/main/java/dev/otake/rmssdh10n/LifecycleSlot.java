package dev.otake.rmssdh10n;

import java.util.function.Consumer;

/** Atomic ownership handoff for a resource created concurrently with stop(). */
final class LifecycleSlot<T> {
    private volatile T value;
    private boolean stopped;

    synchronized boolean publish(T candidate, Consumer<T> close) {
        if (stopped) {
            close.accept(candidate);
            return false;
        }
        value = candidate;
        return true;
    }

    T get() { return value; }

    synchronized T stopAndTake() {
        stopped = true;
        T current = value;
        value = null;
        return current;
    }
}
