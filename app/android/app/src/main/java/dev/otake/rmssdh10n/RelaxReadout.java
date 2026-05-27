package dev.otake.rmssdh10n;

import java.util.Locale;

/**
 * Builds the Japanese relax-mode voice readout (心拍 / 呼吸 / RMSSD値+基準比 / 状態).
 * Pure text — no emoji (matching the dashboard's color+text convention) — spoken by
 * the service's TextToSpeech so it carries with the screen off. Takes only the scalars
 * it needs (baseline RMSSD, state label) so it stays independent of Analysis.
 */
final class RelaxReadout {
    private RelaxReadout() {}

    /** Compose the readout. {@code hr} is required; respiration is announced only when its
     *  confidence clears 0.35 (or is unknown); RMSSD shows the 30 s card value plus a
     *  vs-baseline hint from the smoothed value; the state label is appended when known. */
    static String format(Double hr, Double resp, Double respConf, Double rmssd,
                         Double rmssdSm, Double baseRmssd, String stateLabel) {
        StringBuilder sb = new StringBuilder();
        sb.append("心拍").append(Math.round(hr));
        if (resp != null && (respConf == null || respConf >= 0.35)) sb.append("、呼吸").append(Math.round(resp));
        if (rmssd != null) {
            sb.append("、RMSSDは").append(String.format(Locale.US, "%.1f", rmssd)); // the 30s card value
            if (rmssdSm != null && baseRmssd != null && baseRmssd > 0) {
                double ratio = rmssdSm / baseRmssd; // direction uses the smoothed value (matches state)
                sb.append(ratio >= 1.15 ? "、基準より高め" : ratio <= 0.85 ? "、基準より低め" : "、基準どおり");
            }
        }
        if (stateLabel != null && !stateLabel.isEmpty()) sb.append("、").append(stateLabel);
        sb.append("。");
        return sb.toString();
    }
}
