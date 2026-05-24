package dev.otake.rmssdh10n.hrv;

import static org.junit.Assert.assertEquals;

import org.junit.Test;

/** Golden checks mirror src/bodystate.js self-test. */
public class BodyStateTest {
    private static final Double N = null;

    @Test
    public void walkingImmediate() {
        BodyState e = new BodyState();
        assertEquals("walking", e.update(true, 60, 6, N, N, N, N, N, 1000).state);
    }

    @Test
    public void shortSpikeStaysSittingSustainedActive() {
        BodyState e = new BodyState();
        e.update(false, 10, 5, N, N, N, N, N, 0);
        assertEquals("sitting", e.update(false, 300, 5, N, N, N, N, N, 1000).state);
        assertEquals("active", e.update(false, 300, 5, N, N, N, N, N, 5000).state);
    }

    @Test
    public void reclinedStaysSitting() {
        BodyState e = new BodyState();
        e.update(false, 8, 50, N, N, N, N, N, 0);
        assertEquals("sitting", e.update(false, 8, 50, N, N, N, N, N, 40000).state);
    }

    @Test
    public void horizontalLying() {
        BodyState e = new BodyState();
        e.update(false, 6, 85, N, N, N, N, N, 0);
        assertEquals("lying", e.update(false, 6, 85, N, N, N, N, N, 60000).state);
    }

    @Test
    public void graceThenSettle() {
        BodyState e = new BodyState();
        e.update(true, 200, 5, N, N, N, N, N, 0);
        e.update(false, 5, 5, N, N, N, N, N, 1000);
        assertEquals("active", e.update(false, 5, 5, N, N, N, N, N, 20000).state);
        assertEquals("sitting", e.update(false, 5, 5, N, N, N, N, N, 45000).state);
    }

    @Test
    public void asleepSignatureAndWake() {
        BodyState e = new BodyState();
        e.update(false, 5, 85, 50.0, 60.0, 0.3, 11.0, 0.6, 0);
        assertEquals("not asleep at 5min", false, "asleep".equals(e.update(false, 5, 85, 50.0, 60.0, 0.3, 11.0, 0.6, 300000).state));
        assertEquals("asleep", e.update(false, 5, 85, 50.0, 60.0, 0.3, 11.0, 0.6, 420000).state);
        assertEquals("walking", e.update(true, 5, 85, 50.0, 60.0, 0.3, 11.0, 0.6, 421000).state);
    }
}
