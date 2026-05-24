package dev.otake.rmssdh10n.hrv;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

/** Golden checks mirror src/steps.js's self-test. */
public class StepsTest {
    private static final int SR = 25;

    @Test
    public void walkingThenStill() {
        Steps sc = new Steps(SR);
        int n = 10 * SR; // 10 s of ~2 Hz walking (≈120 spm) -> ~20 steps
        for (int i = 0; i < n; i++) {
            double t = i / (double) SR;
            double z = -1000 - 200 * Math.sin(2 * Math.PI * 2 * t);
            double x = 12 * Math.sin(2 * Math.PI * 2 * t + 1);
            double y = 8 * Math.random();
            sc.add(x, y, z);
        }
        assertTrue("~20 steps (" + sc.steps + ")", sc.steps >= 16 && sc.steps <= 24);
        assertTrue("~120 spm (" + sc.cadence() + ")", sc.cadence() >= 110 && sc.cadence() <= 130);

        int before = sc.steps;
        for (int i = 0; i < 6 * SR; i++) {
            sc.add(3 * (Math.random() - .5), 3 * (Math.random() - .5), -1000 + 3 * (Math.random() - .5));
        }
        assertEquals("no steps while still", before, sc.steps);
        assertEquals("cadence 0 when idle", 0, sc.cadence());
    }
}
