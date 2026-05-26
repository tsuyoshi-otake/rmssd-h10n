package dev.otake.rmssdh10n.hrv;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * Sliding-window RMSSD/SDNN/HR with Kubios-style artifact rejection. Java port
 * of src/rmssd.js (RmssdWindow + median), kept numerically equivalent so the
 * native service produces the same values the WebView pipeline does.
 *
 * A beat is rejected when physiologically implausible (absolute range), too far
 * from the LOCAL MEDIAN of recent accepted beats, or (during warm-up) jumping
 * too far from the previous beat.
 */
public final class Rmssd {

    /** Window snapshot. Nullable fields mirror JS `null` (no reading yet). */
    public static final class Result {
        public final Double rmssd;
        public final Double rmssdEma;
        public final Double hr;
        public final Double sdnn;
        public final int count;
        public final int corrected;

        Result(Double rmssd, Double rmssdEma, Double hr, Double sdnn, int count, int corrected) {
            this.rmssd = rmssd; this.rmssdEma = rmssdEma; this.hr = hr; this.sdnn = sdnn;
            this.count = count; this.corrected = corrected;
        }
    }

    private static final class Entry {
        final double tMs, rr;
        Entry(double tMs, double rr) { this.tMs = tMs; this.rr = rr; }
    }

    static Double median(List<Double> arr) {
        if (arr.isEmpty()) return null;
        List<Double> s = new ArrayList<>(arr);
        Collections.sort(s);
        int m = s.size() >> 1;
        return (s.size() % 2 == 1) ? s.get(m) : (s.get(m - 1) + s.get(m)) / 2.0;
    }

    static Double median(double[] arr) {
        if (arr.length == 0) return null;
        double[] s = arr.clone();
        java.util.Arrays.sort(s);
        int m = s.length >> 1;
        return (s.length % 2 == 1) ? s[m] : (s[m - 1] + s[m]) / 2.0;
    }

    // Robust-RMSSD gate (see compute): a successive RR difference is excluded from
    // the windowed RMSSD when it exceeds a physiological cap (> REL_FLOOR of mean RR
    // — a successive change that large is an artifact/ectopic, Malik-style) and, once
    // enough diffs exist, a Hampel/MAD outlier threshold. Keeps one bad beat from
    // dominating the mean-of-squares and lingering in the 30 s window as a plateau.
    private static final double ROBUST_REL_FLOOR = 0.20;
    private static final double ROBUST_MAD_K = 4.0;
    private static final int    ROBUST_MIN_DIFFS = 5;

    private final double windowMs, minRr, maxRr, maxRelJump, localTol, emaAlpha;
    private final int localN;
    private final List<Entry> entries = new ArrayList<>();
    private final List<Double> recent = new ArrayList<>(); // last N accepted RR
    private Double lastAccepted = null;
    public int rejected = 0;
    private Double rmssdEma = null;

    public Rmssd() { this(30000); }

    public Rmssd(double windowMs) {
        this(windowMs, 300, 2000, 0.25, 0.25, 7, 20);
    }

    public Rmssd(double windowMs, double minRr, double maxRr,
                 double maxRelJump, double localTol, int localN, double emaTau) {
        this.windowMs = windowMs;
        this.minRr = minRr;
        this.maxRr = maxRr;
        this.maxRelJump = maxRelJump;
        this.localTol = localTol;
        this.localN = localN;
        this.emaAlpha = 1.0 / (emaTau + 1.0);
    }

    /** @return true if accepted, false if rejected as an artifact. */
    public boolean add(double tMs, double rr) {
        if (rr < minRr || rr > maxRr) { rejected++; return false; }

        if (recent.size() >= 3) {
            Double med = median(recent);
            if (med != null && med > 0 && Math.abs(rr - med) / med > localTol) { rejected++; return false; }
        } else if (lastAccepted != null) {
            if (Math.abs(rr - lastAccepted) / lastAccepted > maxRelJump) { rejected++; return false; }
        }

        entries.add(new Entry(tMs, rr));
        lastAccepted = rr;
        recent.add(rr);
        if (recent.size() > localN) recent.remove(0);
        evict(tMs);
        return true;
    }

    private void evict(double nowMs) {
        double cutoff = nowMs - windowMs;
        int i = 0;
        while (i < entries.size() && entries.get(i).tMs < cutoff) i++;
        if (i > 0) entries.subList(0, i).clear();
    }

    /** @param nowMs current session time (nullable); evicts stale entries first. */
    public Result compute(Double nowMs) {
        if (nowMs != null) evict(nowMs);
        int count = entries.size();
        if (count < 2) {
            Double hr = count == 1 ? 60000.0 / entries.get(0).rr : null;
            return new Result(null, rmssdEma, hr, null, count, rejected);
        }

        // Mean RR first — SDNN/HR use it, and the robust-RMSSD floor scales by it.
        double sum = 0;
        for (Entry e : entries) sum += e.rr;
        double mean = sum / count;

        // RMSSD over successive RR differences, with a robustness gate so a single
        // artifact/ectopic difference can't dominate the mean-of-squares (and then
        // sit in the 30 s window as a plateau). RMSSD requires artifact-free RR
        // (Task Force 1996); this is in-window correction in the spirit of Kubios.
        // A difference is excluded when it exceeds a physiological cap (> 20 % of mean
        // RR — a successive change that large is an artifact, Malik-style) and, once
        // enough diffs exist, a Hampel/MAD outlier threshold. SDNN/HR stay over all
        // beats — the beat itself is plausible, only its diff is gross.
        int dn = count - 1;
        double[] diff = new double[dn];
        double[] absd = new double[dn];
        for (int i = 1; i < count; i++) {
            double d = entries.get(i).rr - entries.get(i - 1).rr;
            diff[i - 1] = d;
            absd[i - 1] = Math.abs(d);
        }
        double thr = ROBUST_REL_FLOOR * mean;
        if (dn >= ROBUST_MIN_DIFFS) {
            double med = median(absd);
            double[] dev = new double[dn];
            for (int i = 0; i < dn; i++) dev[i] = Math.abs(absd[i] - med);
            double stat = med + ROBUST_MAD_K * 1.4826 * median(dev);
            if (stat > thr) thr = stat;
        }
        double sumSqDiff = 0;
        int used = 0;
        for (int i = 0; i < dn; i++) {
            if (absd[i] <= thr) { sumSqDiff += diff[i] * diff[i]; used++; }
        }
        if (used == 0) { for (int i = 0; i < dn; i++) sumSqDiff += diff[i] * diff[i]; used = dn; }
        double rmssd = Math.sqrt(sumSqDiff / used);

        double varSum = 0;
        for (Entry e : entries) varSum += (e.rr - mean) * (e.rr - mean);
        double sdnn = Math.sqrt(varSum / count);
        double hr = 60000.0 / mean;

        rmssdEma = (rmssdEma == null) ? rmssd : emaAlpha * rmssd + (1 - emaAlpha) * rmssdEma;
        return new Result(rmssd, rmssdEma, hr, sdnn, count, rejected);
    }
}
