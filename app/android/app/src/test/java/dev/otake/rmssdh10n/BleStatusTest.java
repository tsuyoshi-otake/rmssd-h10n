package dev.otake.rmssdh10n;

import org.json.JSONObject;
import org.junit.Test;

import static org.junit.Assert.*;

public class BleStatusTest {
    @Test public void boundedHistoryAndStructuredState() throws Exception {
        BleStatus status = new BleStatus();
        for (int i = 0; i < BleStatus.MAX_EVENTS * 10; i++)
            status.transition("retry_wait", "connect_retry", i, System.currentTimeMillis() + 5000);
        JSONObject json = new JSONObject(status.diagnosticsJson());
        assertEquals(BleStatus.MAX_EVENTS, json.getJSONArray("events").length());
        assertEquals("retry_wait", json.getJSONObject("current").getString("stage"));
        assertFalse(status.diagnosticsJson().contains("rr_ms"));
    }

    @Test public void rejectsFreeFormSensitiveFields() throws Exception {
        BleStatus status = new BleStatus();
        status.transition("AA:BB:CC:DD", "raw 812.5", 0, 0);
        JSONObject current = status.snapshotJson();
        assertEquals("idle", current.getString("stage"));
        assertEquals("none", current.getString("reason"));
    }
}
