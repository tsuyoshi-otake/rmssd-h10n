package dev.otake.rmssdh10n.hrv;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

import java.util.ArrayList;
import java.util.List;

/** Mirrors src/respiration.js self-test: a known-rate RSA series resolves to that rate. */
public class RespirationTest {
    private static double[][] synth(double brPerMin, int seconds, double amp, double baseRr, double noise) {
        double fHz = brPerMin / 60;
        List<Double> xs = new ArrayList<>(), ys = new ArrayList<>();
        double t = 0;
        while (t < seconds * 1000) {
            double resp = amp * Math.sin((2 * Math.PI * fHz * t) / 1000);
            double rr = baseRr + resp + (Math.random() - 0.5) * noise;
            t += rr;
            xs.add(t); ys.add(rr);
        }
        double[] x = new double[xs.size()], y = new double[ys.size()];
        for (int i = 0; i < x.length; i++) { x[i] = xs.get(i); y[i] = ys.get(i); }
        return new double[][]{ x, y };
    }

    @Test
    public void strongRsaResolvesRate() {
        for (double target : new double[]{ 12, 15, 18 }) {
            double[][] s = synth(target, 120, 40, 1000, 5);
            Respiration.Result r = Respiration.estimate(s[0], s[1]);
            assertTrue("valid @" + target, r.valid);
            assertEquals("rate @" + target + " got " + r.breathsPerMin, target, r.breathsPerMin, 1.5);
        }
    }

    @Test
    public void shortWindowInsufficient() {
        double[][] s = synth(15, 15, 30, 1000, 5);
        Respiration.Result r = Respiration.estimate(s[0], s[1]);
        assertTrue(!r.valid);
    }

    /** Slow breathing (8/min ≈ 0.133 Hz) with a strong, sharp peak is now admitted
     *  via the gated slow sub-band — previously rejected as out-of-band. */
    @Test
    public void slowBreathingAdmittedUnderGate() {
        double[][] s = synth(8, 150, 40, 1000, 5);
        Respiration.Result r = Respiration.estimate(s[0], s[1]);
        assertTrue("valid slow", r.valid);
        assertTrue("flagged slow", r.slow);
        assertEquals("rate", 8.0, r.breathsPerMin, 1.5);
    }

}
