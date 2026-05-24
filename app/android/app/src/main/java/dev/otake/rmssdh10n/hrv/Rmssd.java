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

        double sumSqDiff = 0;
        for (int i = 1; i < count; i++) {
            double diff = entries.get(i).rr - entries.get(i - 1).rr;
            sumSqDiff += diff * diff;
        }
        double rmssd = Math.sqrt(sumSqDiff / (count - 1));

        double sum = 0;
        for (Entry e : entries) sum += e.rr;
        double mean = sum / count;
        double varSum = 0;
        for (Entry e : entries) varSum += (e.rr - mean) * (e.rr - mean);
        double sdnn = Math.sqrt(varSum / count);
        double hr = 60000.0 / mean;

        rmssdEma = (rmssdEma == null) ? rmssd : emaAlpha * rmssd + (1 - emaAlpha) * rmssdEma;
        return new Result(rmssd, rmssdEma, hr, sdnn, count, rejected);
    }
}
