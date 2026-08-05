package dev.otake.rmssdh10n;

import org.json.JSONObject;

import dev.otake.rmssdh10n.hrv.Analysis;
import dev.otake.rmssdh10n.hrv.Posture;

/**
 * JSON builders shared by the live status frame, the live chart point and the offline
 * backfill point, so the key set never drifts between them. Nullable numerics become
 * {@link JSONObject#NULL} (present-but-null, not absent) to match the WebView pipeline.
 */
final class HrvJson {
    private HrvJson() {}

    /** Coerce a nullable number to a JSON value: null → {@link JSONObject#NULL}. */
    static Object jn(Double v) { return v == null ? JSONObject.NULL : v; }

    /** Build the point JSON shared by the live tick and the offline backfill, so the
     *  key set never drifts between the two. Backfilled points pass null posture/steps. */
    static String buildPointJson(String wall, Double rmssd, Double hr, Double resp, String tone,
            Integer lean, String posture, String leanDir, Integer activity, int step,
            String body, String sleepPos) throws Exception {
        JSONObject point = new JSONObject();
        point.put("t", wall);
        point.put("rmssd", jn(rmssd));
        point.put("hr", jn(hr));
        point.put("resp", jn(resp));
        point.put("tone", tone != null ? tone : JSONObject.NULL);
        point.put("lean", lean != null ? lean : JSONObject.NULL);
        point.put("posture", posture != null ? posture : JSONObject.NULL);
        point.put("leanDir", leanDir != null ? leanDir : JSONObject.NULL);
        point.put("activity", activity != null ? activity : JSONObject.NULL);
        point.put("step", step);
        point.put("body", body != null ? body : JSONObject.NULL);
        point.put("sleepPos", sleepPos != null ? sleepPos : JSONObject.NULL);
        return point.toString();
    }

    /** Autonomic/body state sub-object for the status frame, or null when unclassified
     *  (org.json drops a null value, so the {@code state} key is then absent — preserved). */
    static JSONObject stateJson(Analysis.State s) throws Exception {
        if (s == null) return null;
        JSONObject o = new JSONObject();
        o.put("label", s.label);
        o.put("tone", s.tone);
        o.put("detail", s.detail);
        o.put("arousal", s.arousal != null ? s.arousal : JSONObject.NULL);
        o.put("recovery", s.recovery != null ? s.recovery : JSONObject.NULL);
        o.put("load", s.load != null ? s.load : JSONObject.NULL);
        return o;
    }

    /** Posture/sleep-position sub-object for the status frame. */
    static JSONObject postureJson(Posture.Result p) throws Exception {
        return postureJson(p, true);
    }

    /** As above, but {@code enabled=false} (姿勢推定OFF) marks the block {@code disabled} so the
     *  dashboard says "OFF by choice" instead of "no ACC signal" — the two look identical in
     *  {@link Posture.Result} (both are 'nosignal') but mean very different things. */
    static JSONObject postureJson(Posture.Result p, boolean enabled) throws Exception {
        JSONObject o = new JSONObject();
        if (!enabled) o.put("disabled", true);
        o.put("receiving", p.receiving);
        o.put("calibrated", p.calibrated);
        o.put("state", p.state);
        o.put("leanDeg", p.leanDeg != null ? p.leanDeg : JSONObject.NULL);
        o.put("activity", p.activity != null ? p.activity : JSONObject.NULL);
        o.put("moving", p.moving);
        o.put("sleepPos", p.sleepPos != null ? p.sleepPos : JSONObject.NULL);
        o.put("leanDir", p.leanDir != null ? p.leanDir : JSONObject.NULL);
        return o;
    }
}
