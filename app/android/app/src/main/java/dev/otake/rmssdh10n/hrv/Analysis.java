package dev.otake.rmssdh10n.hrv;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * Baseline tracking + autonomic-state ("mood") estimation. Java port of the core
 * of src/analysis.js used by the native engine: a per-session rest-gated
 * baseline freeze (seedable from a persisted baseline) + lnRMSSD-based state
 * classification with hysteresis. The WebView keeps the advanced re-baselining
 * (adaptive / full-history / manual) actions.
 *
 * Coarse autonomic-arousal heuristic for self-tracking — not a diagnosis.
 */
public final class Analysis {

    static double median(List<Double> arr) {
        if (arr.isEmpty()) return Double.NaN;
        List<Double> s = new ArrayList<>(arr);
        Collections.sort(s);
        int m = s.size() >> 1;
        return (s.size() % 2 == 1) ? s.get(m) : (s.get(m - 1) + s.get(m)) / 2.0;
    }

    /** Frozen baseline reference. */
    public static final class Base {
        public final double rmssd, hr;
        public Base(double rmssd, double hr) { this.rmssd = rmssd; this.hr = hr; }
    }

    /** Per-session rest-gated baseline. */
    public static final class Baseline {
        private final int need;
        private final boolean restGate;
        private final double restHrTol;
        private final List<Double> rmssd = new ArrayList<>();
        private final List<Double> hr = new ArrayList<>();
        private final List<Double> recentHr = new ArrayList<>();
        private Base frozen = null;

        public Baseline() { this(60, true, 6); }
        public Baseline(int need, boolean restGate, double restHrTol) {
            this.need = need; this.restGate = restGate; this.restHrTol = restHrTol;
        }

        public void add(Double rmssdV, Double hrV) {
            if (rmssdV == null || hrV == null) return;
            if (frozen != null) return;
            recentHr.add(hrV);
            if (recentHr.size() > 10) recentHr.remove(0);
            boolean gated = false;
            if (restGate && recentHr.size() >= 5) {
                double med = median(recentHr);
                if (!Double.isNaN(med) && Math.abs(hrV - med) > restHrTol) gated = true;
            }
            if (!gated) {
                rmssd.add(rmssdV);
                hr.add(hrV);
                if (rmssd.size() >= need) frozen = new Base(median(rmssd), median(hr));
            }
        }

        public Base get() { return frozen; }
        public double progress() { return frozen != null ? 1 : (double) rmssd.size() / need; }
        public void loadFrozen(double r, double h) { if (r > 0 && h > 0) frozen = new Base(r, h); }
        public void reset() { rmssd.clear(); hr.clear(); recentHr.clear(); frozen = null; }
    }

    // ln thresholds for RMSSD change vs baseline.
    private static final double LN_BIG_DROP = Math.log(0.55);
    private static final double LN_DROP = Math.log(0.8);
    private static final double LN_UP_SLIGHT = Math.log(1.1);
    private static final double LN_UP = Math.log(1.25);

    public static final class State {
        public final String label, tone, detail;
        public final Integer arousal;
        public final Double recovery, load;
        State(String label, String tone, Integer arousal, Double recovery, Double load, String detail) {
            this.label = label; this.tone = tone; this.arousal = arousal;
            this.recovery = recovery; this.load = load; this.detail = detail;
        }
    }

    public static State classifyRaw(Double rmssd, Double hr, Base base) {
        if (rmssd == null || hr == null)
            return new State("計測待ち", "wait", null, null, null, "心拍データ待機中");
        if (base == null)
            return new State("キャリブレーション中", "wait", null, null, null, "基準値を計測中…");

        double dLn = (base.rmssd > 0 && rmssd > 0) ? Math.log(rmssd / base.rmssd) : 0;
        double hrDelta = hr - base.hr;
        int arousal = (int) Math.max(0, Math.min(100, Math.round(50 + hrDelta * 2.2 - dLn * 35)));
        double recovery = Math.max(-1, Math.min(1, dLn / 0.7));
        double load = Math.max(-1, Math.min(1, hrDelta / 12));

        String label, tone, detail;
        if (hrDelta >= 12 || dLn <= LN_BIG_DROP) {
            label = "高負荷・興奮"; tone = "high";
            detail = "心拍が大きく上昇 / HRVが大きく低下。強い負荷や興奮の状態。";
        } else if (dLn <= LN_DROP && hrDelta >= 5) {
            label = "ストレス・緊張↑"; tone = "tense";
            detail = "HRV低下＋心拍上昇。負荷・緊張がかかっている可能性。";
        } else if (hrDelta >= 4 && dLn <= LN_UP_SLIGHT) {
            label = "集中"; tone = "focus";
            detail = "軽い覚醒。タスクに没頭しているフロー寄りの状態。";
        } else if (dLn >= LN_UP && hrDelta <= -2) {
            label = "リラックス・回復"; tone = "calm";
            detail = "HRV上昇＋心拍低下。迷走神経（副交感）優位で回復している状態。";
        } else if (dLn >= LN_UP_SLIGHT && hrDelta <= 2) {
            label = "回復傾向"; tone = "recover";
            detail = "HRVが基準よりやや高く心拍は基準付近。落ち着いてきている傾向。";
        } else {
            label = "平常・安定"; tone = "neutral";
            detail = "基準値の近く。安定した状態。";
        }
        return new State(label, tone, arousal, recovery, load, detail);
    }

    /** Hysteresis wrapper: a new label must hold minDwellMs before it commits. */
    public static final class Classifier {
        private final long minDwellMs;
        private State current = null;
        private String pendingLabel = null;
        private long pendingSince = 0;

        public Classifier() { this(45000); }
        public Classifier(long minDwellMs) { this.minDwellMs = minDwellMs; }

        public State update(Double rmssd, Double hr, Base base, long nowMs) {
            State raw = classifyRaw(rmssd, hr, base);
            if ("wait".equals(raw.tone) || current == null || "wait".equals(current.tone)) {
                current = raw; pendingLabel = null; return raw;
            }
            if (raw.label.equals(current.label)) {
                current = raw; pendingLabel = null; return current;
            }
            if (pendingLabel == null || !pendingLabel.equals(raw.label)) {
                pendingLabel = raw.label; pendingSince = nowMs;
            } else if (nowMs - pendingSince >= minDwellMs) {
                current = raw; pendingLabel = null; return current;
            }
            // Hold current label but keep live scores responsive.
            return new State(current.label, current.tone, raw.arousal, raw.recovery, raw.load, current.detail);
        }
    }

    private Analysis() {}
}
