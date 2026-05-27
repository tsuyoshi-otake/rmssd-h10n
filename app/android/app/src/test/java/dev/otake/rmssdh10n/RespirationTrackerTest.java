package dev.otake.rmssdh10n;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

public class RespirationTrackerTest {
    private static final long HOLD = 120_000;

    @Test public void bufferEvictsBeatsOlderThanWindow() {
        RespirationTracker t = new RespirationTracker(3);
        t.addAcceptedBeat(0, 800);
        t.addAcceptedBeat(100_000, 800);
        assertEquals(2, t.bufferSize());      // both within the 120 s window of t=100_000
        t.addAcceptedBeat(130_000, 800);      // window now [10_000, 130_000] → t=0 evicts
        assertEquals(2, t.bufferSize());
    }

    @Test public void smoothFullConfidenceWhenFresh() {
        List<double[]> hist = Arrays.asList(new double[]{ 12.0, 0.8 });
        RespirationTracker.Result r = RespirationTracker.smooth(hist, 0, true, HOLD);
        assertEquals(12.0, r.brpm, 1e-9);
        assertEquals(0.8, r.confidence, 1e-9);
        assertTrue(r.preview);
    }

    @Test public void smoothDecaysConfidenceWithStaleness() {
        List<double[]> hist = Arrays.asList(new double[]{ 12.0, 0.8 });
        RespirationTracker.Result half = RespirationTracker.smooth(hist, HOLD / 2, false, HOLD);
        assertEquals(0.4, half.confidence, 1e-9);  // 0.8 * (1 - 0.5)
        assertFalse(half.preview);
    }

    @Test public void smoothEmptyHistoryIsAllNull() {
        RespirationTracker.Result r = RespirationTracker.smooth(new ArrayList<>(), 0, true, HOLD);
        assertNull(r.brpm);
        assertNull(r.confidence);
        assertFalse(r.preview);                    // preview is forced off with no estimate
    }

    @Test public void smoothTakesMedianOfRecentEstimates() {
        List<double[]> hist = Arrays.asList(
            new double[]{ 11.0, 0.5 }, new double[]{ 15.0, 0.9 }, new double[]{ 13.0, 0.7 });
        RespirationTracker.Result r = RespirationTracker.smooth(hist, 0, false, HOLD);
        assertEquals(13.0, r.brpm, 1e-9);          // median of 11,13,15
        assertEquals(0.7, r.confidence, 1e-9);     // median of 0.5,0.7,0.9
    }
}
