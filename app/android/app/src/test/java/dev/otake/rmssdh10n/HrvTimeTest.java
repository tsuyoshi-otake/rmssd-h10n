package dev.otake.rmssdh10n;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

/** Locks the JST (+09:00) timestamp contract: every engine time is JST, never UTC/Z. */
public class HrvTimeTest {
    // 2021-01-01 00:00:00 JST  ==  2020-12-31 15:00:00 UTC  ==  epoch 1609426800000
    private static final long JAN1_MIDNIGHT_JST = 1609426800000L;

    @Test public void localIsoUsesJstOffsetNeverZulu() {
        String iso = HrvTime.localIso(JAN1_MIDNIGHT_JST);
        assertEquals("2021-01-01T00:00:00.000+09:00", iso);
        assertTrue(iso.endsWith("+09:00"));
        assertFalse(iso.contains("Z"));
    }

    @Test public void localIsoFormatsMillisInJst() {
        long epoch = JAN1_MIDNIGHT_JST + 12 * 3600_000L + 34 * 60_000L + 56_000L + 789L;
        assertEquals("2021-01-01T12:34:56.789+09:00", HrvTime.localIso(epoch));
    }

    @Test public void jstMidnightFloorsToJstDayBoundary() {
        // noon → same day's midnight
        assertEquals(JAN1_MIDNIGHT_JST, HrvTime.jstMidnight(JAN1_MIDNIGHT_JST + 12 * 3600_000L));
        // one ms before next JST midnight → still the same day
        assertEquals(JAN1_MIDNIGHT_JST, HrvTime.jstMidnight(JAN1_MIDNIGHT_JST + 86400_000L - 1));
        // exactly next JST midnight → rolls over
        long next = JAN1_MIDNIGHT_JST + 86400_000L;
        assertEquals(next, HrvTime.jstMidnight(next));
    }
}
