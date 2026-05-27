package dev.otake.rmssdh10n.hrv;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

import java.util.List;

public class BackfillTest {

    private static final long ANCHOR = 1_700_000_000_000L; // fixed epoch ms

    // -----------------------------------------------------------------------
    // Steady 60 bpm (1000 ms RR, perfectly uniform)
    // -----------------------------------------------------------------------
    @Test
    public void steady60Bpm() {
        int beats = 120;
        double[] rrMs = new double[beats];
        for (int i = 0; i < beats; i++) rrMs[i] = 1000.0;

        List<Backfill.Pt> pts = Backfill.replay(rrMs, ANCHOR, 40.0, 60.0);

        // The replay spans floor(first/1000)*1000 .. floor(last/1000)*1000.
        // 120 beats of 1000 ms spans 119 000 ms → 119 or 120 whole-second slots
        // (depends on alignment).  At least one point must appear.
        assertTrue("should produce points", !pts.isEmpty());
        int count = pts.size();
        assertTrue("count in range [119, 120], got " + count, count >= 119 && count <= 120);

        // Points must be strictly increasing in tMs by exactly 1000 ms.
        for (int i = 1; i < pts.size(); i++) {
            assertEquals("tMs step 1000 ms at i=" + i,
                    1000L, pts.get(i).tMs - pts.get(i - 1).tMs);
        }

        // Start-anchored: the last beat lands at ANCHOR + sum(rr) = ANCHOR + beats*1000.
        long expectedLast = ANCHOR + (long) beats * 1000L;
        assertEquals("last tMs", expectedLast, pts.get(pts.size() - 1).tMs);

        // HR of each point with a reading should be near 60 bpm.
        for (Backfill.Pt pt : pts) {
            if (pt.hr != null) {
                assertTrue("hr near 60, got " + pt.hr, pt.hr >= 59 && pt.hr <= 61);
            }
        }

        // RMSSD for perfectly identical RR intervals is 0 (or null before 2 beats).
        // Accept null OR a very small value (floating-point of exactly-equal diffs = 0).
        for (Backfill.Pt pt : pts) {
            if (pt.rmssd != null) {
                assertTrue("rmssd near-zero for uniform RR, got " + pt.rmssd,
                        pt.rmssd <= 0.5);
            }
        }

        // Resp may be null for steady RR (weak RSA signal) — just assert no crash.
        // If non-null, it must be a finite positive number.
        for (Backfill.Pt pt : pts) {
            if (pt.resp != null) {
                assertTrue("resp finite > 0", pt.resp > 0 && Double.isFinite(pt.resp));
            }
        }
    }

    // -----------------------------------------------------------------------
    // Realistic varying RR (~60 bpm with sinusoidal oscillation)
    // -----------------------------------------------------------------------
    @Test
    public void realisticVaryingRr() {
        int beats = 120;
        double[] rrMs = new double[beats];
        // Deterministic oscillation: 1000 ± 30 ms sin, period ~12.6 beats → ~4-5 cycles
        for (int i = 0; i < beats; i++) {
            rrMs[i] = 1000.0 + 30.0 * Math.sin(i * 0.5);
        }

        List<Backfill.Pt> pts = Backfill.replay(rrMs, ANCHOR, 40.0, 60.0);
        assertNotNull("result must not be null", pts);
        assertTrue("should produce points", !pts.isEmpty());

        // HR must be in a sane range for ~60 bpm input.
        for (Backfill.Pt pt : pts) {
            if (pt.hr != null) {
                assertTrue("hr sane range [50,75], got " + pt.hr,
                        pt.hr >= 50 && pt.hr <= 75);
            }
        }

        // For later points the window should be filled; at least one must have
        // a positive finite RMSSD (the sin oscillation gives non-zero diffs).
        boolean anyRmssd = false;
        for (Backfill.Pt pt : pts) {
            if (pt.rmssd != null) {
                assertTrue("rmssd finite positive, got " + pt.rmssd,
                        pt.rmssd > 0 && Double.isFinite(pt.rmssd));
                anyRmssd = true;
            }
        }
        // Once window has >= 2 beats the RMSSD is non-null; 120 beats is plenty.
        assertTrue("at least one rmssd-bearing point", anyRmssd);

        // Tone (if present) must be a non-empty string.
        for (Backfill.Pt pt : pts) {
            if (pt.tone != null) {
                assertTrue("tone non-empty", !pt.tone.isEmpty());
            }
        }

        // Resp: just assert no crash and null-or-finite (sin RR may not produce
        // a strong enough RSA peak to pass the confidence gate).
        for (Backfill.Pt pt : pts) {
            if (pt.resp != null) {
                assertTrue("resp finite > 0", pt.resp > 0 && Double.isFinite(pt.resp));
            }
        }
    }

    // -----------------------------------------------------------------------
    // Edge cases: empty and single-beat input
    // -----------------------------------------------------------------------
    @Test
    public void emptyInputReturnsEmpty() {
        List<Backfill.Pt> pts = Backfill.replay(new double[]{}, ANCHOR, 40.0, 60.0);
        assertNotNull(pts);
        assertTrue("empty input yields empty list", pts.isEmpty());
    }

    @Test
    public void singleBeatDoesNotThrow() {
        List<Backfill.Pt> pts = Backfill.replay(new double[]{ 1000.0 }, ANCHOR, 40.0, 60.0);
        // A single beat produces at most one second-slot but no RMSSD.
        // The main requirement is no exception and the list is not null.
        assertNotNull(pts);
        // If a point was emitted, it must have no rmssd (only one beat: hr possible).
        for (Backfill.Pt pt : pts) {
            assertTrue("single beat: rmssd must be null", pt.rmssd == null);
        }
    }

    // -----------------------------------------------------------------------
    // No baseline: tone should be null or "wait" (classifyRaw with null base
    // returns "wait"; hysteresis may hold a prior — but with no prior at all
    // the first result is always "wait").
    // -----------------------------------------------------------------------
    @Test
    public void noBaselineGivesNullOrWaitTone() {
        int beats = 60;
        double[] rrMs = new double[beats];
        for (int i = 0; i < beats; i++) rrMs[i] = 1000.0;

        // Pass invalid baseRmssd/Hr to force null base.
        List<Backfill.Pt> pts = Backfill.replay(rrMs, ANCHOR, 0.0, 0.0);
        assertNotNull(pts);
        for (Backfill.Pt pt : pts) {
            // Without a base the classifier emits "wait" tone.
            assertTrue("tone null or wait with no baseline",
                    pt.tone == null || "wait".equals(pt.tone));
        }
    }

    // -----------------------------------------------------------------------
    // Monotone ordering: tMs must increase by exactly 1000 ms per step.
    // -----------------------------------------------------------------------
    @Test
    public void pointsAreMonotonicallyIncreasing() {
        int beats = 60;
        double[] rrMs = new double[beats];
        for (int i = 0; i < beats; i++) rrMs[i] = 800.0 + (i % 5) * 50.0; // varied

        List<Backfill.Pt> pts = Backfill.replay(rrMs, ANCHOR, 35.0, 65.0);
        for (int i = 1; i < pts.size(); i++) {
            assertTrue("tMs monotone at i=" + i, pts.get(i).tMs > pts.get(i - 1).tMs);
            assertEquals("tMs step=1000 at i=" + i,
                    1000L, pts.get(i).tMs - pts.get(i - 1).tMs);
        }
    }

    // -----------------------------------------------------------------------
    // Tail off-by-one: a last beat that does NOT fall on a whole-second boundary
    // must still be swept (the closing second emitted), not dropped ~1 s early.
    // 50 beats of 950 ms ⇒ last beat at ANCHOR + 47 500 ms (off boundary); the
    // final emitted second must be its ceil-second (ANCHOR + 48 000), not floor.
    // -----------------------------------------------------------------------
    @Test
    public void lastPartialSecondIsCovered() {
        int beats = 50;
        double[] rrMs = new double[beats];
        for (int i = 0; i < beats; i++) rrMs[i] = 950.0;

        List<Backfill.Pt> pts = Backfill.replay(rrMs, ANCHOR, 40.0, 60.0);
        assertTrue("should produce points", !pts.isEmpty());

        long lastBeat = ANCHOR + (long) beats * 950L;                    // ANCHOR + 47 500
        long expectedLast = (long) Math.ceil(lastBeat / 1000.0) * 1000L; // ANCHOR + 48 000
        assertEquals("final (ceil) second of the last beat must be emitted",
                expectedLast, pts.get(pts.size() - 1).tMs);
        for (int i = 1; i < pts.size(); i++) {
            assertEquals("tMs step=1000 at i=" + i, 1000L, pts.get(i).tMs - pts.get(i - 1).tMs);
        }
    }
}
