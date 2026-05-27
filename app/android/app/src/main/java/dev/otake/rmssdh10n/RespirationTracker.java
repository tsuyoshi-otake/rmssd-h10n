package dev.otake.rmssdh10n;

import android.util.Log;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

import dev.otake.rmssdh10n.hrv.Respiration;

/**
 * Owns the RSA respiration estimate: the accepted-NN buffer (fed per beat), the heavy
 * Welch recompute throttled to every Nth tick, and the last-good hold that fades the
 * confidence with staleness — a single failed 3 s recompute must not blank a ~1–2 min
 * average (Schäfer & Kratky 2008: RSA-derived rate is stable over >1 min windows).
 *
 * Not internally synchronized: the engine calls {@link #addAcceptedBeat} (from the BLE
 * callback) and {@link #compute} (from the tick) under its own lock, exactly as before.
 */
final class RespirationTracker {
    private static final String TAG = "HrvEngine"; // keep "[resp]" log lines under the engine tag
    private static final long RESP_WINDOW_MS = 120_000;
    private static final long RESP_HOLD_MS = 120_000;

    private final int every;                                  // recompute cadence in ticks
    private final List<double[]> buffer = new ArrayList<>();  // {tMs, rr} accepted NN beats
    private final List<double[]> history = new ArrayList<>(); // {brpm, conf} recent estimates
    private boolean preview = false;
    private long lastGoodMs = 0;   // wall-clock of the most recent accepted estimate
    private long lastLogMs = 0;    // throttle for dropout-reason logging

    RespirationTracker(int every) { this.every = every; }

    /** Smoothed output of a tick compute. */
    static final class Result {
        final Double brpm, confidence; final boolean preview;
        Result(Double brpm, Double confidence, boolean preview) {
            this.brpm = brpm; this.confidence = confidence; this.preview = preview;
        }
    }

    /** Add an accepted NN beat and evict beats older than the window. Caller holds the lock. */
    void addAcceptedBeat(double tMs, double rrMs) {
        buffer.add(new double[]{ tMs, rrMs });
        double cutoff = tMs - RESP_WINDOW_MS;
        while (!buffer.isEmpty() && buffer.get(0)[0] < cutoff) buffer.remove(0);
    }

    /** Recompute the Welch PSD every {@code every} ticks, then median-smooth with a
     *  staleness-decayed confidence. A failed recompute does NOT clear history (that is the
     *  point of the hold); only crossing RESP_HOLD_MS of staleness drops it. Caller holds lock. */
    Result compute(long now, long tickCount) {
        if (tickCount % every == 0) {
            int m = buffer.size();
            double[] xs = new double[m], ys = new double[m];
            for (int i = 0; i < m; i++) { xs[i] = buffer.get(i)[0]; ys[i] = buffer.get(i)[1]; }
            Respiration.Result rr = Respiration.estimate(xs, ys);
            if (rr.breathsPerMin != null && (rr.valid || rr.preview)) {
                history.add(new double[]{ rr.breathsPerMin, rr.confidence });
                if (history.size() > 5) history.remove(0);
                preview = rr.preview;
                lastGoodMs = now;
            } else if (now - lastLogMs > 30000) {
                // Throttled dropout diagnostics: see why estimates are missing.
                Log.i(TAG, "[resp] miss reason=" + rr.reason + " snr=" + rr.snr
                        + " f=" + rr.freqHz + " buf=" + m);
                lastLogMs = now;
            }
        }
        long staleAge = lastGoodMs > 0 ? (now - lastGoodMs) : Long.MAX_VALUE;
        if (staleAge > RESP_HOLD_MS) { history.clear(); preview = false; }
        return smooth(history, staleAge, preview, RESP_HOLD_MS);
    }

    /** Pure: median brpm of the recent estimates, confidence faded linearly to 0 over
     *  {@code holdMs} of staleness. Empty history → all-null, preview off. */
    static Result smooth(List<double[]> history, long staleAge, boolean preview, long holdMs) {
        if (history.isEmpty()) return new Result(null, null, false);
        List<Double> brs = new ArrayList<>(), cfs = new ArrayList<>();
        for (double[] e : history) { brs.add(e[0]); cfs.add(e[1]); }
        double decay = Math.max(0, 1 - (double) staleAge / holdMs);
        return new Result(round1(medianOf(brs)), round2(medianOf(cfs) * decay), preview);
    }

    /** Beats currently in the RSA window (test/diagnostic visibility). */
    int bufferSize() { return buffer.size(); }

    private static double round1(double v) { return Math.round(v * 10.0) / 10.0; }
    private static double round2(double v) { return Math.round(v * 100.0) / 100.0; }
    private static double medianOf(List<Double> a) {
        if (a.isEmpty()) return 0;
        List<Double> s = new ArrayList<>(a);
        Collections.sort(s);
        int m = s.size() >> 1;
        return (s.size() % 2 == 1) ? s.get(m) : (s.get(m - 1) + s.get(m)) / 2.0;
    }
}
