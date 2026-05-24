package dev.otake.rmssdh10n.hrv;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

public class RmssdTest {
    @Test
    public void rmssdSdnnHrFromKnownSequence() {
        Rmssd w = new Rmssd(600000); // big window: no eviction
        assertTrue(w.add(0, 800));
        assertTrue(w.add(1, 820));
        assertTrue(w.add(2, 810));
        assertTrue(w.add(3, 830));
        Rmssd.Result r = w.compute(null);
        assertEquals(4, r.count);
        assertEquals(0, r.corrected);
        // successive diffs 20,-10,20 -> sqrt(900/3)
        assertEquals(Math.sqrt(900.0 / 3.0), r.rmssd, 1e-6);
        assertEquals(60000.0 / 815.0, r.hr, 1e-6);     // mean 815
        assertEquals(Math.sqrt(500.0 / 4.0), r.sdnn, 1e-6); // pop variance
    }

    @Test
    public void rejectsLocalMedianOutlier() {
        Rmssd w = new Rmssd(600000);
        w.add(0, 800); w.add(1, 800); w.add(2, 800); w.add(3, 800); // 4 accepted, recent>=3
        assertFalse(w.add(4, 1400)); // 75% off median 800 -> rejected
        Rmssd.Result r = w.compute(null);
        assertEquals(4, r.count);
        assertEquals(1, r.corrected);
    }

    @Test
    public void rejectsImplausibleRange() {
        Rmssd w = new Rmssd(600000);
        assertFalse(w.add(0, 200));  // < minRr
        assertFalse(w.add(1, 2500)); // > maxRr
        Rmssd.Result r = w.compute(null);
        assertEquals(0, r.count);
        assertEquals(2, r.corrected);
        assertNull(r.rmssd);
    }

    @Test
    public void singleBeatGivesHrNoRmssd() {
        Rmssd w = new Rmssd(600000);
        w.add(0, 1000);
        Rmssd.Result r = w.compute(null);
        assertEquals(1, r.count);
        assertNull(r.rmssd);
        assertEquals(60.0, r.hr, 1e-9);
    }
}
