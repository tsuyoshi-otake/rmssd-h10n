package dev.otake.rmssdh10n;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

public class BreathingAlertTest {
    @Test public void matchesOnlyBelowBaselineAndConfidentFastBreathing() {
        assertTrue(BreathingAlert.matches(30.0, 30.0, 24.0, 0.35, false));
        assertFalse(BreathingAlert.matches(30.1, 30.0, 24.0, 0.35, false));
        assertFalse(BreathingAlert.matches(30.0, 30.0, 23.9, 0.35, false));
        assertFalse(BreathingAlert.matches(30.0, 30.0, 24.0, 0.34, false));
        assertFalse(BreathingAlert.matches(30.0, 30.0, 24.0, 0.80, true));
    }

    @Test public void speaksAfterSustainedConditionAndThenCoolsDown() {
        BreathingAlert a = new BreathingAlert();
        a.setEnabled(true);
        assertNull(a.update(1_000, true, 20.0, 30.0, 25.0, 0.8, false));
        assertNull(a.update(30_999, true, 20.0, 30.0, 25.0, 0.8, false));
        assertNotNull(a.update(31_000, true, 20.0, 30.0, 25.0, 0.8, false));
        assertNull(a.update(60_000, true, 20.0, 30.0, 25.0, 0.8, false));
        assertNotNull(a.update(211_000, true, 20.0, 30.0, 25.0, 0.8, false));
    }

    @Test public void conditionClearResetsSustainTimer() {
        BreathingAlert a = new BreathingAlert();
        a.setEnabled(true);
        assertNull(a.update(1_000, true, 20.0, 30.0, 25.0, 0.8, false));
        assertNull(a.update(20_000, true, 31.0, 30.0, 25.0, 0.8, false));
        assertNull(a.update(40_000, true, 20.0, 30.0, 25.0, 0.8, false));
        assertNotNull(a.update(70_000, true, 20.0, 30.0, 25.0, 0.8, false));
    }
}
