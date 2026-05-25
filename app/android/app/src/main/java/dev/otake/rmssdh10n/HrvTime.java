package dev.otake.rmssdh10n;

import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;
import java.util.TimeZone;

/**
 * JST (+09:00) time helpers shared by the native engine. Every engine timestamp goes
 * through {@link #localIso(long)} (matching src/time.js); the UTC/Z form is never
 * produced, so the dashboard and CSV stay on one wall-clock convention.
 */
final class HrvTime {
    private HrvTime() {}

    private static final SimpleDateFormat ISO;
    static {
        ISO = new SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss.SSS'+09:00'", Locale.US);
        ISO.setTimeZone(TimeZone.getTimeZone("GMT+09:00")); // JST, matching src/time.js
    }

    /** Epoch ms → JST ISO-8601 with an explicit {@code +09:00} offset (never UTC/Z). */
    static synchronized String localIso(long epochMs) { return ISO.format(new Date(epochMs)); }

    /** Epoch ms of the most recent JST midnight at or before {@code now} (daily-step boundary). */
    static long jstMidnight(long now) {
        long jst = now + 9L * 3600 * 1000;
        long dayIdx = Math.floorDiv(jst, 86400000L);
        return dayIdx * 86400000L - 9L * 3600 * 1000;
    }
}
