package dev.otake.rmssdh10n.hrv;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

public class RmssdTest {
    @Test
    public void rmssdSdnnHrFromKnownSequence() {
        Rmssd w = new Rmssd(600000, 3); // big window, low startup gate for the math fixture
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

    @Test
    public void startupSpikeNeedsEnoughCleanDiffs() {
        Rmssd w = new Rmssd(600000);
        assertTrue(w.add(0, 800));
        assertTrue(w.add(1, 990));  // within accept gate, but its successive diffs are outliers
        assertTrue(w.add(2, 800));

        Rmssd.Result early = w.compute(null);
        assertEquals(3, early.count);
        assertNull("do not publish RMSSD from only startup outlier diffs", early.rmssd);
        assertTrue("HR can still be reported", early.hr > 0);

        for (int i = 3; i < 12; i++) w.add(i, 800);
        Rmssd.Result settled = w.compute(null);
        assertTrue("startup spike should not linger in RMSSD, got " + settled.rmssd,
                settled.rmssd != null && settled.rmssd < 5.0);
    }

    @Test
    public void robustGateIgnoresSingleEctopicDiff() {
        // Steady ~800 ms RR (very low RMSSD) with ONE accepted outlier beat that
        // squeaks past the ±25 % accept gate (990 = +23.75 % off median 800), like a
        // compensatory pause. Its two ~190 ms successive diffs would otherwise put a
        // ~55 ms plateau into the 30 s window; the robust gate must exclude them.
        Rmssd w = new Rmssd(600000);
        for (int i = 0; i < 12; i++) w.add(i, 800);
        assertTrue(w.add(12, 990));            // accepted as a beat (level within 25 %)
        for (int i = 13; i < 25; i++) w.add(i, 800);
        Rmssd.Result r = w.compute(null);
        assertEquals(25, r.count);             // beat kept — HR/SDNN intact
        assertEquals(0, r.corrected);          // not rejected, only its diffs are gated
        assertTrue("robust rmssd should collapse to ~0, got " + r.rmssd, r.rmssd < 5.0);
    }

    @Test
    public void robustGateKeepsGenuineVariability() {
        // Alternating 800/860 → every successive diff is 60 ms of REAL variability
        // (60 < 20 % of the ~830 mean ≈ 166), so none may be gated.
        Rmssd w = new Rmssd(600000);
        for (int i = 0; i < 20; i++) w.add(i, (i % 2 == 0) ? 800 : 860);
        Rmssd.Result r = w.compute(null);
        assertEquals(60.0, r.rmssd, 1e-6);     // unchanged: all diffs retained
    }
}
