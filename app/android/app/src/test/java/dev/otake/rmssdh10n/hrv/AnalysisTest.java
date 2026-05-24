package dev.otake.rmssdh10n.hrv;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;

import org.junit.Test;

public class AnalysisTest {
    @Test
    public void baselineFreezesAfterEnoughSettledSamples() {
        Analysis.Baseline b = new Analysis.Baseline();
        assertNull(b.get());
        for (int i = 0; i < 60; i++) b.add(30.0, 60.0); // steady -> not gated
        Analysis.Base base = b.get();
        assertNotNull(base);
        assertEquals(30.0, base.rmssd, 1e-9);
        assertEquals(60.0, base.hr, 1e-9);
        assertEquals(1.0, b.progress(), 1e-9);
    }

    @Test
    public void classifyRawZones() {
        Analysis.Base base = new Analysis.Base(30, 60);
        assertEquals("neutral", Analysis.classifyRaw(30.0, 60.0, base).tone);   // at baseline
        assertEquals("high", Analysis.classifyRaw(30.0, 75.0, base).tone);      // HR +15
        assertEquals("calm", Analysis.classifyRaw(45.0, 57.0, base).tone);      // RMSSD up, HR down
        assertEquals("wait", Analysis.classifyRaw(null, 60.0, base).tone);      // no data
        assertEquals("wait", Analysis.classifyRaw(30.0, 60.0, null).tone);      // no baseline
    }

    @Test
    public void seededBaselineLoads() {
        Analysis.Baseline b = new Analysis.Baseline();
        b.loadFrozen(42.0, 55.0);
        assertNotNull(b.get());
        assertEquals(42.0, b.get().rmssd, 1e-9);
    }
}
