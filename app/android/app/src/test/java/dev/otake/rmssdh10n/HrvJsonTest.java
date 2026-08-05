package dev.otake.rmssdh10n;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import org.json.JSONObject;
import org.junit.Test;

import java.util.Arrays;
import java.util.HashSet;
import java.util.Set;

/** Locks the point-JSON schema so the live tick and the offline backfill never drift,
 *  and that nullable numerics are present-but-null (JSONObject.NULL), not absent. */
public class HrvJsonTest {
    private static final Set<String> POINT_KEYS = new HashSet<>(Arrays.asList(
        "t", "rmssd", "hr", "resp", "tone", "lean", "posture", "leanDir",
        "activity", "step", "body", "sleepPos"));

    // android.jar's org.json stub has no keySet(); collect via the keys() iterator
    // (present in both the stub API and the real org.json used at test runtime).
    private static Set<String> keysOf(JSONObject o) {
        Set<String> keys = new HashSet<>();
        for (java.util.Iterator<String> it = o.keys(); it.hasNext(); ) keys.add(it.next());
        return keys;
    }

    @Test public void jnCoercesNullToJsonNull() {
        assertEquals(JSONObject.NULL, HrvJson.jn(null));
        assertEquals(12.5, ((Number) HrvJson.jn(12.5)).doubleValue(), 1e-9);
    }

    @Test public void livePointHasFullSchema() throws Exception {
        JSONObject o = new JSONObject(HrvJson.buildPointJson(
            "2021-01-01T00:00:00.000+09:00", 42.0, 60.0, 14.0, "calm",
            5, "sitting", "fwd", 30, 3, "sitting", "left"));
        assertEquals(POINT_KEYS, keysOf(o));
        assertEquals(42.0, o.getDouble("rmssd"), 1e-9);
        assertEquals(5, o.getInt("lean"));
        assertEquals("sitting", o.getString("posture"));
        assertEquals(3, o.getInt("step"));
    }

    @Test public void backfillPointSharesSchemaWithLive() throws Exception {
        // Backfill passes null posture/steps — the key set must match the live point exactly.
        JSONObject o = new JSONObject(HrvJson.buildPointJson(
            "2021-01-01T00:00:00.000+09:00", 42.0, 60.0, 14.0, "calm",
            null, null, null, null, 0, null, null));
        assertEquals(POINT_KEYS, keysOf(o));
        assertTrue(o.isNull("lean"));
        assertTrue(o.isNull("posture"));
        assertTrue(o.isNull("sleepPos"));
        assertEquals(0, o.getInt("step"));
    }

    @Test public void nullNumericsArePresentButNull() throws Exception {
        JSONObject o = new JSONObject(HrvJson.buildPointJson(
            "t", null, null, null, null, null, null, null, null, 0, null, null));
        assertTrue(o.has("rmssd"));
        assertTrue(o.isNull("rmssd"));
        assertTrue(o.has("hr"));
        assertTrue(o.isNull("hr"));
    }

    @Test public void stateJsonNullWhenUnclassified() throws Exception {
        assertNull(HrvJson.stateJson(null));
    }

    /** 姿勢推定OFF and "no ACC signal" produce an identical Posture.Result, so only the
     *  explicit `disabled` marker lets the dashboard tell "off by choice" from "no data". */
    @Test public void postureJsonMarksDisabledOnlyWhenTurnedOff() throws Exception {
        dev.otake.rmssdh10n.hrv.Posture.Result p = new dev.otake.rmssdh10n.hrv.Posture().compute(0L);
        JSONObject off = HrvJson.postureJson(p, false);
        assertTrue(off.optBoolean("disabled"));
        JSONObject on = HrvJson.postureJson(p, true);
        assertTrue(!on.has("disabled"));
        assertTrue(!HrvJson.postureJson(p).has("disabled")); // legacy overload unchanged
    }
}
