package dev.otake.rmssdh10n.hrv;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * Offline gap-backfill: replays historical RR intervals (fetched from Polar H10
 * onboard memory after a disconnection) through the SAME compute classes the live
 * HrvEngine uses, and emits one HRV point per whole second at reconstructed wall
 * timestamps.
 *
 * Start-anchor strategy: the recording is pinned to {@code anchorStartMs} (OUR
 * clock time when startRecording was issued — RTC-independent of the embedded
 * device and accurate even if the H10 auto-stopped on full memory). Timestamps are
 * walked forward from that anchor by summing RR, so every beat carries a
 * reconstructed wall-ms coordinate.
 *
 * The compute parameters (window size, RESP_WINDOW_MS, respEvery, last-5 median
 * smoothing) are intentionally identical to {@code HrvEngine} so that backfilled
 * numbers are numerically compatible with live points and can be merged into the
 * same time-series without discontinuities.
 */
public final class Backfill {

    /** One reconstructed 1 Hz point.  Fields mirror the subset the live point
     *  JSON needs; posture/steps are intentionally absent (no ACC offline). */
    public static final class Pt {
        /** Whole-second epoch ms (floor of reconstructed beat wall-clock). */
        public final long tMs;
        /** Raw window RMSSD rounded to 1 dp, or null when window is too small. */
        public final Double rmssd;
        /** Heart rate in bpm, or null. */
        public final Integer hr;
        /** Smoothed respiration rate in breaths/min rounded 1 dp, or null. */
        public final Double resp;
        /** Autonomic-state tone string from the classifier, or null. */
        public final String tone;

        public Pt(long tMs, Double rmssd, Integer hr, Double resp, String tone) {
            this.tMs  = tMs;
            this.rmssd = rmssd;
            this.hr   = hr;
            this.resp = resp;
            this.tone = tone;
        }
    }

    // -----------------------------------------------------------------------
    // Constants mirrored from HrvEngine (keep in sync if HrvEngine changes).
    // -----------------------------------------------------------------------
    /** Accepted-NN window fed to Respiration.estimate — mirrors RESP_WINDOW_MS. */
    private static final long RESP_WINDOW_MS = 120_000L;
    /** Respiration is recomputed once every this many ticks — mirrors respEvery. */
    private static final int RESP_EVERY = 3;

    /**
     * Replay per-beat RR through fresh Rmssd / StateClassifier / Respiration
     * instances and emit one Pt per whole second spanned by the beats.
     *
     * @param rrMs        per-beat RR intervals in milliseconds, chronological order
     * @param anchorStartMs wallclock (epoch ms) the recording began on our clock
     *                    (start-anchor); beats are laid forward from here
     * @param baseRmssd  frozen baseline RMSSD; {@code <= 0} or {@link Double#NaN}
     *                   means no baseline (tone will be null / "wait")
     * @param baseHr     frozen baseline HR;     {@code <= 0} or {@link Double#NaN}
     *                   means no baseline
     * @return points ordered by tMs ascending; empty when rrMs is too short
     */
    public static List<Pt> replay(double[] rrMs, long anchorStartMs,
                                  double baseRmssd, double baseHr) {
        List<Pt> out = new ArrayList<>();
        int n = rrMs.length;
        if (n == 0) return out;

        // ------------------------------------------------------------------
        // 1. Reconstruct wall-clock timestamps via start-anchor forward-walk:
        //    beatWall[i] = anchorStartMs + sum(rrMs[0..i]) — the i-th interval ends
        //    at the running sum after the recording started.
        // ------------------------------------------------------------------
        double[] beatWall = new double[n];
        double acc = anchorStartMs;
        for (int i = 0; i < n; i++) { acc += rrMs[i]; beatWall[i] = acc; }

        // ------------------------------------------------------------------
        // 2. Fresh compute instances — same constructor params as HrvEngine.
        //    HrvEngine line 61: new Rmssd(30000)   (default 30 s window)
        //    HrvEngine line 66: new Analysis.Classifier(45000)
        // ------------------------------------------------------------------
        Rmssd win = new Rmssd(30_000);
        Analysis.Classifier classifier = new Analysis.Classifier(45_000);

        // Build a frozen Base only when both values are valid.
        Analysis.Base base = null;
        if (baseRmssd > 0 && Double.isFinite(baseRmssd)
                && baseHr > 0 && Double.isFinite(baseHr)) {
            base = new Analysis.Base(baseRmssd, baseHr);
        }

        // Respiration state — mirrors HrvEngine's respBuffer / respHistory.
        List<double[]> respBuffer  = new ArrayList<>(); // {tMs, rr}
        List<double[]> respHistory = new ArrayList<>(); // {brpm, conf}
        Double respOut  = null;
        int tickIndex   = 0; // counts 1-Hz ticks for RESP_EVERY throttle

        // ------------------------------------------------------------------
        // 3. Whole-second sweep.
        // ------------------------------------------------------------------
        long tFirst = (long) Math.floor(beatWall[0]  / 1000.0) * 1000L;
        // Ceil (not floor) of the last beat so the final partial second is swept too: with a
        // floor bound and a `beatWall <= tSec` feed, a last beat at e.g. xx.900 is never fed
        // (no tSec ever reaches it) and the backfill ends up to ~1 s early, dropping the closing
        // beats from the last window. Ceil adds one frame that consumes them. No-op when the last
        // beat lands exactly on a second boundary.
        long tLast  = (long) Math.ceil(beatWall[n - 1] / 1000.0) * 1000L;

        // next beat index to feed (beats are fed lazily as time advances).
        int nextBeat = 0;

        for (long tSec = tFirst; tSec <= tLast; tSec += 1000L) {
            tickIndex++;

            // Feed all beats whose reconstructed wall-clock <= tSec.
            while (nextBeat < n && beatWall[nextBeat] <= tSec) {
                double bw = beatWall[nextBeat];
                double rr = rrMs[nextBeat];
                boolean accepted = win.add(bw, rr);
                if (accepted) {
                    respBuffer.add(new double[]{ bw, rr });
                    double cutoff = bw - RESP_WINDOW_MS;
                    while (!respBuffer.isEmpty() && respBuffer.get(0)[0] < cutoff) {
                        respBuffer.remove(0);
                    }
                }
                nextBeat++;
            }

            // Compute RMSSD window at this second.
            Rmssd.Result r = win.compute((double) tSec);

            Double rmssdVal = r.rmssd  != null ? round1(r.rmssd)  : null;
            Integer hrVal   = r.hr     != null ? (int) Math.round(r.hr) : null;
            Double rmssdEma = r.rmssdEma; // already smoothed inside Rmssd

            // Gate: skip this second if there is no reading at all (mirrors
            // HrvEngine's "if (hrVal != null || rmssd != null)" guard).
            if (hrVal == null && rmssdVal == null) continue;

            // Classifier (uses EMA-smoothed RMSSD, rounded, like HrvEngine).
            Double rmssdSmoothed = rmssdEma != null ? round1(rmssdEma) : null;
            Double hrDouble      = hrVal != null ? (double) hrVal : null;
            Analysis.State state = classifier.update(rmssdSmoothed, hrDouble, base, tSec);
            String tone = (state != null && state.tone != null) ? state.tone : null;

            // Respiration: recompute every RESP_EVERY ticks (mirrors HrvEngine).
            if (tickIndex % RESP_EVERY == 0) {
                int m = respBuffer.size();
                if (m >= 2) {
                    double[] xs = new double[m], ys = new double[m];
                    for (int i = 0; i < m; i++) {
                        xs[i] = respBuffer.get(i)[0];
                        ys[i] = respBuffer.get(i)[1];
                    }
                    Respiration.Result rr = Respiration.estimate(xs, ys);
                    if (rr.breathsPerMin != null && (rr.valid || rr.preview)) {
                        respHistory.add(new double[]{ rr.breathsPerMin, rr.confidence });
                        if (respHistory.size() > 5) respHistory.remove(0);
                    }
                    // A failed estimate does NOT clear history (matches live hold logic).
                }
            }
            // Last-5 median smoothing of respiration (mirrors HrvEngine).
            if (!respHistory.isEmpty()) {
                List<Double> brs = new ArrayList<>();
                for (double[] e : respHistory) brs.add(e[0]);
                respOut = round1(medianOf(brs));
            }
            // Note: no RESP_HOLD_MS decay in backfill — there is no real-time
            // wall-clock staleness in an offline replay.  Once respHistory is
            // non-empty it remains until a new estimate arrives or the replay ends.

            out.add(new Pt(tSec, rmssdVal, hrVal, respOut, tone));
        }

        return out;
    }

    // -----------------------------------------------------------------------
    // Private helpers (mirrors HrvEngine's static helpers).
    // -----------------------------------------------------------------------
    private static double round1(double v) { return Math.round(v * 10.0) / 10.0; }

    private static double medianOf(List<Double> a) {
        if (a.isEmpty()) return 0;
        List<Double> s = new ArrayList<>(a);
        Collections.sort(s);
        int m = s.size() >> 1;
        return (s.size() % 2 == 1) ? s.get(m) : (s.get(m - 1) + s.get(m)) / 2.0;
    }

    private Backfill() {}
}
