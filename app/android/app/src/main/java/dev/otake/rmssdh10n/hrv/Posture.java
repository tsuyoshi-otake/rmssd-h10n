package dev.otake.rmssdh10n.hrv;

/**
 * Torso posture & activity from the H10's chest accelerometer. Java port of
 * src/posture.js. A low-pass of the raw samples recovers the gravity direction;
 * the lean angle is the angle vs a calibrated UPRIGHT reference. The supine
 * reference fixes the body's posterior + lateral axes so a lying gravity vector
 * resolves to supine / prone / left / right.
 *
 * Inputs are milli-G (gravity ≈ 1000 mg).
 */
public final class Posture {
    private static final double RAD2DEG = 180.0 / Math.PI;

    /** Mutable 3-vector (mg). Public so calibration/state can be inspected & seeded. */
    public static final class Vec {
        public double x, y, z;
        public Vec(double x, double y, double z) { this.x = x; this.y = y; this.z = z; }
        Vec copy() { return new Vec(x, y, z); }
    }

    static double mag(Vec v) { return Math.sqrt(v.x * v.x + v.y * v.y + v.z * v.z); }
    static double dot(Vec a, Vec b) { return a.x * b.x + a.y * b.y + a.z * b.z; }
    static Vec sub(Vec a, Vec b) { return new Vec(a.x - b.x, a.y - b.y, a.z - b.z); }
    static Vec scale(Vec a, double k) { return new Vec(a.x * k, a.y * k, a.z * k); }
    static Vec cross(Vec a, Vec b) {
        return new Vec(a.y * b.z - a.z * b.y, a.z * b.x - a.x * b.z, a.x * b.y - a.y * b.x);
    }
    static Vec norm(Vec v) { double m = mag(v); return m < 1e-6 ? null : scale(v, 1 / m); }

    static double angleBetween(Vec a, Vec b) {
        double ma = mag(a), mb = mag(b);
        if (ma < 1e-6 || mb < 1e-6) return 0;
        double c = dot(a, b) / (ma * mb);
        c = Math.max(-1, Math.min(1, c));
        return Math.acos(c) * RAD2DEG;
    }

    // Lean bins (deg) and gates (mg).
    static final double LEAN_UPRIGHT = 12, LEAN_LEAN = 35, LEAN_RECLINED = 65;
    static final double REST_ACTIVITY = 45, MOVE_ACTIVITY = 130, G_MIN = 750, G_MAX = 1250;

    public static final class Result {
        public final boolean receiving, calibrated, moving;
        public final String state;       // nosignal|uncal|upright|lean|reclined|lying
        public final Integer leanDeg;    // null when not calibrated/receiving
        public final Integer activity;   // null when not receiving
        public final String sleepPos;    // supine|prone|left|right, only when lying
        public final String leanDir;     // forward|back when leaning (needs supineRef), else null

        Result(boolean receiving, boolean calibrated, String state, Integer leanDeg,
               Integer activity, boolean moving, String sleepPos, String leanDir) {
            this.receiving = receiving; this.calibrated = calibrated; this.state = state;
            this.leanDeg = leanDeg; this.activity = activity; this.moving = moving;
            this.sleepPos = sleepPos; this.leanDir = leanDir;
        }
    }

    private final double aG, aA;
    public Vec g = null;            // running gravity estimate (mg)
    private double activity = 0;    // EMA of |sample − gravity| (mg)
    private long samples = 0;
    private long lastSampleAt = 0;
    public Vec ref = null;          // upright reference
    public Long calibratedAt = null;
    public Vec supineRef = null;    // on-the-back reference
    public int latSign = 1;

    public Posture() { this(null, null, 1, 25); }

    public Posture(Vec ref, Vec supineRef, int latSign, int sampleRate) {
        this.aG = 1 - Math.exp(-1.0 / (1.5 * sampleRate));
        this.aA = 1 - Math.exp(-1.0 / (0.7 * sampleRate));
        this.ref = ref;
        this.supineRef = supineRef;
        this.latSign = latSign == -1 ? -1 : 1;
    }

    public void add(double sx, double sy, double sz) {
        samples++;
        lastSampleAt = System.currentTimeMillis();
        if (g == null) { g = new Vec(sx, sy, sz); return; }
        g.x += aG * (sx - g.x);
        g.y += aG * (sy - g.y);
        g.z += aG * (sz - g.z);
        double res = Math.sqrt((sx - g.x) * (sx - g.x) + (sy - g.y) * (sy - g.y) + (sz - g.z) * (sz - g.z));
        activity += aA * (res - activity);

        if (ref == null && samples > 3 * 25 && activity < REST_ACTIVITY) {
            double gm = mag(g);
            if (gm > G_MIN && gm < G_MAX) setRefInternal();
        }
    }

    private Vec setRefInternal() {
        if (g == null) return null;
        ref = g.copy();
        calibratedAt = System.currentTimeMillis();
        return ref.copy();
    }

    // Manual upright calibration. Reject while moving so a press mid-motion can't
    // lock in a garbage reference (the auto path already rest-gates); the caller
    // tells the user to hold still and try again.
    public Vec setReference() {
        if (g == null) return null;
        if (activity > REST_ACTIVITY) return null;
        return setRefInternal();
    }

    public Vec setSupineReference() {
        if (g == null || ref == null) return null;
        if (angleBetween(g, ref) < 55) return null; // not lying down
        supineRef = g.copy();
        return supineRef.copy();
    }

    /** supine/prone/left/right from upright (longitudinal) + supine (posterior) refs. */
    public String sleepPos() {
        if (g == null || ref == null || supineRef == null) return null;
        Vec Lh = norm(ref);
        if (Lh == null) return null;
        Vec Ph = norm(sub(supineRef, scale(Lh, dot(supineRef, Lh))));
        if (Ph == null) return null;
        Vec Lat = norm(cross(Lh, Ph));
        if (Lat == null) return null;
        Vec gp = sub(g, scale(Lh, dot(g, Lh)));
        double aP = dot(gp, Ph), aLat = latSign * dot(gp, Lat);
        if (Math.abs(aP) >= Math.abs(aLat)) return aP > 0 ? "supine" : "prone";
        return aLat > 0 ? "right" : "left";
    }

    /** Front/back lean direction while sitting/standing: project the off-upright
     *  part of gravity onto the posterior axis (supineRef ⊥ upright). +ve = the
     *  back tilts down (reclining onto a backrest), -ve = the chest tilts down
     *  (slouching forward). Returns "forward" | "back", or null without supineRef.
     *  This is what a chest accelerometer needs to tell 前のめり from もたれ — the
     *  lean *angle* alone is the same for both. */
    public String leanDir() {
        if (g == null || ref == null || supineRef == null) return null;
        Vec Lh = norm(ref);
        if (Lh == null) return null;
        Vec Ph = norm(sub(supineRef, scale(Lh, dot(supineRef, Lh))));
        if (Ph == null) return null;
        Vec gp = sub(g, scale(Lh, dot(g, Lh)));
        return dot(gp, Ph) > 0 ? "back" : "forward";
    }

    public Result compute() { return compute(System.currentTimeMillis()); }

    public Result compute(long nowMs) {
        boolean receiving = g != null && (nowMs - lastSampleAt) < 3000;
        if (g == null || !receiving) {
            return new Result(false, ref != null, "nosignal", null, null, false, null, null);
        }
        int act = (int) Math.round(activity);
        boolean moving = act > MOVE_ACTIVITY;
        if (ref == null) {
            return new Result(true, false, "uncal", null, act, moving, null, null);
        }
        int leanDeg = (int) Math.round(angleBetween(g, ref));
        String state;
        if (leanDeg <= LEAN_UPRIGHT) state = "upright";
        else if (leanDeg <= LEAN_LEAN) state = "lean";
        else if (leanDeg <= LEAN_RECLINED) state = "reclined";
        else state = "lying";
        String sp = state.equals("lying") ? sleepPos() : null;
        // Forward vs back only matters while sitting/standing and leaning enough.
        String dir = (!state.equals("lying") && leanDeg > LEAN_UPRIGHT) ? leanDir() : null;
        return new Result(true, true, state, leanDeg, act, moving, sp, dir);
    }
}
