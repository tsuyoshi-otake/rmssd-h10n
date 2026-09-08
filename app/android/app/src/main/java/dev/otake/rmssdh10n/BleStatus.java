package dev.otake.rmssdh10n;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayDeque;
import java.util.Deque;

/** Bounded, privacy-safe BLE lifecycle history shared by status and diagnostics. */
final class BleStatus {
    static final int MAX_EVENTS = 64;

    static final class Event {
        final String stage, reason;
        final int attempt;
        final long changedAt, nextRetryAt;
        Event(String stage, String reason, int attempt, long changedAt, long nextRetryAt) {
            this.stage = clean(stage, "idle");
            this.reason = clean(reason, "none");
            this.attempt = Math.max(0, attempt);
            this.changedAt = changedAt;
            this.nextRetryAt = Math.max(0, nextRetryAt);
        }

        JSONObject json() throws Exception {
            return new JSONObject()
                    .put("stage", stage).put("reason", reason).put("attempt", attempt)
                    .put("changedAt", HrvTime.localIso(changedAt))
                    .put("nextRetryAt", nextRetryAt > 0 ? HrvTime.localIso(nextRetryAt) : JSONObject.NULL);
        }
    }

    private final Deque<Event> events = new ArrayDeque<>();
    private Event current = new Event("idle", "not_started", 0, System.currentTimeMillis(), 0);

    synchronized void transition(String stage, String reason, int attempt, long nextRetryAt) {
        Event event = new Event(stage, reason, attempt, System.currentTimeMillis(), nextRetryAt);
        current = event;
        events.addLast(event);
        while (events.size() > MAX_EVENTS) events.removeFirst();
    }

    synchronized JSONObject snapshotJson() throws Exception { return current.json(); }

    synchronized String diagnosticsJson() {
        try {
            JSONArray arr = new JSONArray();
            for (Event event : events) arr.put(event.json());
            return new JSONObject()
                    .put("exportedAt", HrvTime.localIso(System.currentTimeMillis()))
                    .put("current", current.json()).put("events", arr).toString();
        } catch (Exception ignored) { return "{\"events\":[]}"; }
    }

    synchronized int size() { return events.size(); }

    private static String clean(String value, String fallback) {
        if (value == null || !value.matches("[a-z0-9_]{1,48}")) return fallback;
        return value;
    }
}
