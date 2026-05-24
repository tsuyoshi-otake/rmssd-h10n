package dev.otake.rmssdh10n.hrv;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

/** Golden checks mirror src/posture.js's self-test. */
public class PostureTest {
    private static final double RAD2DEG = 180.0 / Math.PI;

    @Test
    public void autoCalibrateThenLeanThenLying() {
        Posture t = new Posture(null, null, 1, 25);
        for (int i = 0; i < 200; i++) t.add(0, 0, -1000);
        assertNotNull("auto-calibrated while still", t.ref);
        Posture.Result s = t.compute();
        assertEquals("upright", s.state);

        double gx = Math.sin(30 / RAD2DEG) * 1000, gz = -Math.cos(30 / RAD2DEG) * 1000;
        for (int i = 0; i < 200; i++) t.add(gx, 0, gz);
        s = t.compute();
        assertTrue("~30 lean (" + s.leanDeg + ")", s.leanDeg >= 25 && s.leanDeg <= 35);
        assertEquals("lean", s.state);

        for (int i = 0; i < 300; i++) t.add(1000, 0, 0);
        s = t.compute();
        assertTrue("~90 lean (" + s.leanDeg + ")", s.leanDeg >= 80);
        assertEquals("lying", s.state);
    }

    @Test
    public void leanDirectionForwardVsBack() {
        // upright = toward feet (0,-1,0); supine/posterior = toward back (0,0,-1).
        final double c = Math.cos(30 / RAD2DEG) * 1000, t30 = Math.sin(30 / RAD2DEG) * 1000;

        // Reclining back: gravity tilts toward the posterior axis.
        Posture back = new Posture(new Posture.Vec(0, -1000, 0), new Posture.Vec(0, 0, -1000), 1, 25);
        for (int i = 0; i < 300; i++) back.add(0, -c, -t30);
        Posture.Result rb = back.compute();
        assertEquals("lean", rb.state);
        assertEquals("back", rb.leanDir);

        // Slouching forward: gravity tilts toward the anterior axis (opposite sign).
        Posture fwd = new Posture(new Posture.Vec(0, -1000, 0), new Posture.Vec(0, 0, -1000), 1, 25);
        for (int i = 0; i < 300; i++) fwd.add(0, -c, t30);
        Posture.Result rf = fwd.compute();
        assertEquals("lean", rf.state);
        assertEquals("forward", rf.leanDir);
    }

    @Test
    public void sleepPositionResolution() {
        Posture sp = new Posture(null, null, 1, 25);
        sp.ref = new Posture.Vec(0, -1000, 0);       // upright -> toward feet
        sp.supineRef = new Posture.Vec(0, 0, -1000);  // supine -> toward back
        sp.g = new Posture.Vec(0, 0, -1000); assertEquals("supine", sp.sleepPos());
        sp.g = new Posture.Vec(0, 0, 1000);  assertEquals("prone", sp.sleepPos());
        sp.g = new Posture.Vec(1000, 0, 0);  assertEquals("right", sp.sleepPos());
        sp.g = new Posture.Vec(-1000, 0, 0); assertEquals("left", sp.sleepPos());
    }
}
