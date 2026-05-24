package dev.otake.rmssdh10n.hrv;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/**
 * Respiration-rate estimation from RR intervals via RSA (Welch PSD). Java port
 * of src/respiration.js, numerically equivalent so the native engine reports the
 * same breaths/min the WebView pipeline does. Pure DSP: linear resample to 4 Hz,
 * 2nd-order polynomial detrend, Hann-windowed Welch periodogram over the search
 * band, peak vs median-floor SNR + sharpness → signal-quality confidence.
 */
public final class Respiration {
    private static final double FS = 4;
    private static final double SEARCH_MIN = 0.15, SEARCH_MAX = 0.45, MAYER_MAX = 0.16, STEP = 0.005;
    private static final double SEG_SEC = 60, OVERLAP = 0.5;
    private static final double PREVIEW_SPAN_MS = 30000, MIN_SPAN_MS = 60000;
    private static final int MIN_ENTRIES = 20;
    private static final double MIN_SNR = 2.5;

    public static final class Result {
        public final Double breathsPerMin; // null when no estimate
        public final Double confidence;     // signal quality 0..1
        public final boolean valid, preview;
        public final String reason;
        Result(Double br, Double conf, boolean valid, boolean preview, String reason) {
            this.breathsPerMin = br; this.confidence = conf; this.valid = valid;
            this.preview = preview; this.reason = reason;
        }
    }

    private static Result fail(String reason) { return new Result(null, 0.0, false, false, reason); }

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
        for (double f = SEARCH_MIN; f <= SEARCH_MAX + 1e-9; f += STEP) freqs.add(f);

        int segLen = (int) Math.round(SEG_SEC * FS);
        int hop = Math.max(1, (int) Math.round(segLen * (1 - OVERLAP)));
        double[] psd = welch(sig, FS, freqs, segLen, hop);

        double peakP = -1; int peakK = -1;
        for (int k = 0; k < psd.length; k++) if (psd[k] > peakP) { peakP = psd[k]; peakK = k; }
        double floor = median(psd);
        double snr = floor > 0 ? peakP / floor : 0;
        double peakF = freqs.get(peakK);

        if (snr < MIN_SNR) return fail("no_clear_peak");
        if (peakK <= 0 || peakK >= psd.length - 1) return fail("peak_at_band_edge");

        double half = peakP / 2;
        int lo = peakK, hi = peakK;
        while (lo > 0 && psd[lo] > half) lo--;
        while (hi < psd.length - 1 && psd[hi] > half) hi++;
        double peakWidthHz = (hi - lo) * STEP;

        double snrScore = clamp01((snr - MIN_SNR) / (12 - MIN_SNR));
        double widthScore = clamp01(1 - peakWidthHz / 0.08);
        double confidence = 0.7 * snrScore + 0.3 * widthScore;
        if (peakF < MAYER_MAX && snr < 6) confidence *= 0.4;

        boolean preview = span < MIN_SPAN_MS;
        if (preview) confidence *= 0.6;

        double br = Math.round(peakF * 60 * 10) / 10.0;
        double conf = Math.round(clamp01(confidence) * 100) / 100.0;
        return new Result(br, conf, !preview, preview, preview ? "preview" : "ok");
    }

    private static double clamp01(double v) { return Math.max(0, Math.min(1, v)); }

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
