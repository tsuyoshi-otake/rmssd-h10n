package dev.otake.rmssdh10n.hrv;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/**
 * Respiration-rate estimation from RR intervals via RSA (Welch PSD). Originally a
 * port of src/respiration.js; the native engine is now Android's only compute
 * path, so this has DIVERGED from the JS reference on purpose to cut dropouts:
 *   - scan band extended down to 0.10 Hz so slow/relaxed breathing (6–9/min at
 *     rest, while coding, asleep) is no longer rejected as out-of-band. The
 *     0.10–0.15 Hz "slow" sub-band overlaps the Mayer wave (~0.1 Hz baroreflex),
 *     so it is admitted only under a stricter SNR + sharpness gate and reported
 *     at lower confidence (Task Force 1996 HF=0.15–0.40; Julien 2006 Mayer wave;
 *     HRV-biofeedback resonance ≈ 6/min = 0.1 Hz).
 *   - MIN_SNR lowered 2.5→2.0: a low-confidence number beats a blank (the UI
 *     shows signal quality separately). Natarajan 2021 gates RSA peaks by SNR.
 * The per-failure blanking is handled by the consumer (HrvEngine holds the last
 * good value and decays confidence). Pure DSP otherwise: linear resample to 4 Hz,
 * 2nd-order polynomial detrend, Hann-windowed Welch periodogram, peak vs
 * median-floor SNR + sharpness → signal-quality confidence.
 */
public final class Respiration {
    private static final double FS = 4;
    // SCAN_MIN is how low we look; SEARCH_MIN is the normal (HF) full-confidence
    // lower bound. [SCAN_MIN, SEARCH_MIN) is the gated slow-breathing sub-band.
    private static final double SCAN_MIN = 0.10, SEARCH_MIN = 0.15, SEARCH_MAX = 0.45, STEP = 0.005;
    private static final double SEG_SEC = 60, OVERLAP = 0.5;
    private static final double PREVIEW_SPAN_MS = 30000, MIN_SPAN_MS = 60000;
    private static final int MIN_ENTRIES = 20;
    private static final double MIN_SNR = 2.0;          // normal band
    private static final double SLOW_SNR = 4.0;         // slow band: stricter (Mayer guard)
    private static final double SLOW_MAX_WIDTH = 0.04;  // slow band: peak must be sharp

    public static final class Result {
        public final Double breathsPerMin; // null when no estimate
        public final Double confidence;     // signal quality 0..1
        public final boolean valid, preview;
        public final String reason;
        public final Double snr, freqHz, peakWidthHz; // diagnostics (null on fail)
        public final boolean slow;                     // peak fell in the slow sub-band
        Result(Double br, Double conf, boolean valid, boolean preview, String reason,
               Double snr, Double freqHz, Double peakWidthHz, boolean slow) {
            this.breathsPerMin = br; this.confidence = conf; this.valid = valid;
            this.preview = preview; this.reason = reason;
            this.snr = snr; this.freqHz = freqHz; this.peakWidthHz = peakWidthHz; this.slow = slow;
        }
    }

    private static Result fail(String reason) {
        return new Result(null, 0.0, false, false, reason, null, null, null, false);
    }

    /** entries ascending by tMs (artifact-cleaned NN beats). */
    public static Result estimate(double[] xs, double[] ys) {
        int n = xs.length;
        if (n < MIN_ENTRIES) return fail("insufficient_intervals");
        double span = xs[n - 1] - xs[0];
        if (span < PREVIEW_SPAN_MS) return fail("insufficient_span");

        double[] grid = resampleLinear(xs, ys, FS, xs[0], xs[n - 1]);
        if (grid.length < 8) return fail("too_few_samples");
        double[] sig = detrendPoly2(grid);

        List<Double> freqs = new ArrayList<>();
        for (double f = SCAN_MIN; f <= SEARCH_MAX + 1e-9; f += STEP) freqs.add(f);

        int segLen = (int) Math.round(SEG_SEC * FS);
        int hop = Math.max(1, (int) Math.round(segLen * (1 - OVERLAP)));
        double[] psd = welch(sig, FS, freqs, segLen, hop);

        double floor = median(psd);

        // Pick the strongest LOCAL maximum (not the global max) over the whole
        // band: a genuine oscillation is a bump (a bin above both neighbours)
        // whose fundamental dominates its harmonics, while the Mayer wave (~0.1 Hz
        // baroreflex) leaks only a monotonically-decaying tail into the low end —
        // which is NOT a local max and so is excluded by construction (it would
        // otherwise win as the global max and be reported as a bogus ~6/min
        // "breath", as seen live with snr≈52 at exactly 0.10 Hz).
        int peakK = bestLocalMax(psd, freqs, SCAN_MIN, SEARCH_MAX + 1e-9);
        if (peakK < 0) {
            double gf = freqs.get(argmax(psd));
            return new Result(null, 0.0, false, false, "no_clear_peak",
                    null, Math.round(gf * 1000) / 1000.0, null, false);
        }
        double snr = floor > 0 ? psd[peakK] / floor : 0;
        double peakF = freqs.get(peakK);
        double peakWidthHz = widthAt(psd, peakK);

        // Classify by frequency. The slow sub-band (0.10–0.15 Hz) overlaps the
        // Mayer wave, so it is admitted only under a strict SNR + sharpness gate
        // and reported at lower confidence; the HF band uses the normal gate.
        boolean slow = peakF < SEARCH_MIN;
        if (slow) {
            if (snr < SLOW_SNR || peakWidthHz > SLOW_MAX_WIDTH) {
                return new Result(null, 0.0, false, false, "slow_unconfirmed",
                        Math.round(snr * 10) / 10.0, Math.round(peakF * 1000) / 1000.0,
                        Math.round(peakWidthHz * 1000) / 1000.0, true);
            }
        } else if (snr < MIN_SNR) {
            return new Result(null, 0.0, false, false, "no_clear_peak",
                    Math.round(snr * 10) / 10.0, Math.round(peakF * 1000) / 1000.0,
                    Math.round(peakWidthHz * 1000) / 1000.0, false);
        }

        double snrScore = clamp01((snr - MIN_SNR) / (12 - MIN_SNR));
        double widthScore = clamp01(1 - peakWidthHz / 0.08);
        double confidence = 0.7 * snrScore + 0.3 * widthScore;
        if (slow) confidence *= 0.5; // honest: slow band is harder to separate from LF

        boolean preview = span < MIN_SPAN_MS;
        if (preview) confidence *= 0.6;

        double br = Math.round(peakF * 60 * 10) / 10.0;
        double conf = Math.round(clamp01(confidence) * 100) / 100.0;
        String reason = preview ? "preview" : (slow ? "ok_slow" : "ok");
        return new Result(br, conf, !preview, preview, reason,
                Math.round(snr * 10) / 10.0, Math.round(peakF * 1000) / 1000.0,
                Math.round(peakWidthHz * 1000) / 1000.0, slow);
    }

    private static double clamp01(double v) { return Math.max(0, Math.min(1, v)); }

    /** Strongest interior local maximum whose frequency is in [fLo, fHi), or -1.
     *  A local max (psd[k] above both neighbours) is a real oscillatory bump; a
     *  monotonic LF tail leaking into the band is excluded by construction. */
    private static int bestLocalMax(double[] psd, List<Double> freqs, double fLo, double fHi) {
        int best = -1; double bestP = -1;
        for (int k = 1; k < psd.length - 1; k++) {
            double f = freqs.get(k);
            if (f < fLo || f >= fHi) continue;
            if (psd[k] > psd[k - 1] && psd[k] >= psd[k + 1] && psd[k] > bestP) { bestP = psd[k]; best = k; }
        }
        return best;
    }

    /** -3 dB (half-power) width around bin k, in Hz. */
    private static double widthAt(double[] psd, int k) {
        double half = psd[k] / 2;
        int lo = k, hi = k;
        while (lo > 0 && psd[lo] > half) lo--;
        while (hi < psd.length - 1 && psd[hi] > half) hi++;
        return (hi - lo) * STEP;
    }

    private static int argmax(double[] a) {
        int idx = 0; double m = a.length > 0 ? a[0] : 0;
        for (int i = 1; i < a.length; i++) if (a[i] > m) { m = a[i]; idx = i; }
        return idx;
    }

    private static double[] resampleLinear(double[] xs, double[] ys, double fs, double t0, double tEnd) {
        double dtMs = 1000 / fs;
        int n = Math.max(2, (int) Math.floor((tEnd - t0) / dtMs) + 1);
        double[] out = new double[n];
        int j = 0;
        for (int i = 0; i < n; i++) {
            double t = t0 + i * dtMs;
            while (j < xs.length - 2 && xs[j + 1] < t) j++;
            double x0 = xs[j], x1 = xs[j + 1];
            double frac = x1 > x0 ? (t - x0) / (x1 - x0) : 0;
            out[i] = ys[j] + (ys[j + 1] - ys[j]) * Math.max(0, Math.min(1, frac));
        }
        return out;
    }

    private static double[] detrendPoly2(double[] y) {
        int n = y.length;
        if (n < 3) return Arrays.copyOf(y, n);
        double s0 = n, s1 = 0, s2 = 0, s3 = 0, s4 = 0, b0 = 0, b1 = 0, b2 = 0;
        for (int i = 0; i < n; i++) {
            double x = i / (double) (n - 1);
            double x2 = x * x;
            s1 += x; s2 += x2; s3 += x2 * x; s4 += x2 * x2;
            b0 += y[i]; b1 += x * y[i]; b2 += x2 * y[i];
        }
        double[][] m = {
            { s0, s1, s2, b0 },
            { s1, s2, s3, b1 },
            { s2, s3, s4, b2 },
        };
        for (int col = 0; col < 3; col++) {
            int piv = col;
            for (int r = col + 1; r < 3; r++) if (Math.abs(m[r][col]) > Math.abs(m[piv][col])) piv = r;
            if (Math.abs(m[piv][col]) < 1e-12) continue;
            double[] tmp = m[col]; m[col] = m[piv]; m[piv] = tmp;
            for (int r = 0; r < 3; r++) {
                if (r == col) continue;
                double f = m[r][col] / m[col][col];
                for (int k = col; k < 4; k++) m[r][k] -= f * m[col][k];
            }
        }
        double a = m[0][3] / (m[0][0] == 0 ? 1 : m[0][0]);
        double b = m[1][3] / (m[1][1] == 0 ? 1 : m[1][1]);
        double c = m[2][3] / (m[2][2] == 0 ? 1 : m[2][2]);
        double[] out = new double[n];
        for (int i = 0; i < n; i++) {
            double x = i / (double) (n - 1);
            out[i] = y[i] - (a + b * x + c * x * x);
        }
        return out;
    }

    private static double[] hann(int n) {
        double[] w = new double[n];
        if (n < 2) { Arrays.fill(w, 1); return w; }
        for (int i = 0; i < n; i++) w[i] = 0.5 * (1 - Math.cos((2 * Math.PI * i) / (n - 1)));
        return w;
    }

    private static double powerAt(double[] sig, int off, int len, double[] win, double f, double fs) {
        double w = (2 * Math.PI * f) / fs;
        double re = 0, im = 0;
        for (int nn = 0; nn < len; nn++) {
            double v = sig[off + nn] * win[nn];
            re += v * Math.cos(w * nn);
            im -= v * Math.sin(w * nn);
        }
        return re * re + im * im;
    }

    private static double[] welch(double[] sig, double fs, List<Double> freqs, int segLen, int hop) {
        int N = sig.length;
        int eff = Math.min(segLen, N);
        double[] win = hann(eff);
        List<Integer> starts = new ArrayList<>();
        for (int s = 0; s + eff <= N; s += hop) starts.add(s);
        if (starts.isEmpty()) starts.add(0);
        double[] psd = new double[freqs.size()];
        for (int s0 : starts) {
            for (int k = 0; k < freqs.size(); k++) psd[k] += powerAt(sig, s0, eff, win, freqs.get(k), fs);
        }
        for (int k = 0; k < psd.length; k++) psd[k] /= starts.size();
        return psd;
    }

    private static double median(double[] arr) {
        if (arr.length == 0) return 0;
        double[] s = arr.clone();
        Arrays.sort(s);
        int m = s.length >> 1;
        return (s.length % 2 == 1) ? s[m] : (s[m - 1] + s[m]) / 2.0;
    }

    private Respiration() {}
}
