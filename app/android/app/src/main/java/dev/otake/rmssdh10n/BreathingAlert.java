package dev.otake.rmssdh10n;

/**
 * Voice warning for the "low RMSSD + shallow breathing" case. The H10/RSA path
 * estimates breathing rate, not tidal volume, so "shallow" is represented by a
 * high confident respiration rate: rapid shallow breathing is the actionable
 * pattern this app can observe.
 */
final class BreathingAlert {
    static final double SHALLOW_BRPM = 24.0;
    static final double MIN_RESP_CONF = 0.35;
    static final double MAX_RMSSD_RATIO = 1.0;
    static final long SUSTAIN_MS = 30_000L;
    static final long COOLDOWN_MS = 180_000L;

    private boolean enabled;
    private long since = 0;
    private long lastSpokenAt = 0;

    synchronized boolean isEnabled() { return enabled; }

    synchronized void setEnabled(boolean enabled) {
        this.enabled = enabled;
        if (!enabled) {
            since = 0;
            lastSpokenAt = 0;
        }
    }

    synchronized boolean isActive(boolean fresh, Double rmssdSmoothed, Double baseRmssd,
                                  Double resp, Double respConf, boolean respPreview) {
        return enabled && fresh && matches(rmssdSmoothed, baseRmssd, resp, respConf, respPreview);
    }

    synchronized String update(long nowMs, boolean fresh, Double rmssdSmoothed, Double baseRmssd,
                               Double resp, Double respConf, boolean respPreview) {
        if (!isActive(fresh, rmssdSmoothed, baseRmssd, resp, respConf, respPreview)) {
            since = 0;
            return null;
        }
        if (since == 0) since = nowMs;
        if (nowMs - since < SUSTAIN_MS) return null;
        if (lastSpokenAt != 0 && nowMs - lastSpokenAt < COOLDOWN_MS) return null;
        lastSpokenAt = nowMs;
        return "警告。RMSSDが基準以下で、呼吸が速く浅い可能性があります。ゆっくり深く呼吸してください。";
    }

    static boolean matches(Double rmssdSmoothed, Double baseRmssd,
                           Double resp, Double respConf, boolean respPreview) {
        if (rmssdSmoothed == null || baseRmssd == null || !(baseRmssd > 0)) return false;
        if (resp == null || respPreview) return false;
        if (respConf == null || respConf < MIN_RESP_CONF) return false;
        return rmssdSmoothed <= baseRmssd * MAX_RMSSD_RATIO && resp >= SHALLOW_BRPM;
    }
}
