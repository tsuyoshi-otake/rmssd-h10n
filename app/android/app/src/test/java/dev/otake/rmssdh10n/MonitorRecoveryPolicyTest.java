package dev.otake.rmssdh10n;

import org.junit.Test;
import static org.junit.Assert.*;
import static dev.otake.rmssdh10n.MonitorRecoveryPolicy.Decision.*;

public class MonitorRecoveryPolicyTest {
    private static final String DEVICE = "AA:BB:CC:DD:EE:FF";

    @Test public void bootAndJobRespectExplicitStopAndMissingSelection() {
        for (String engine : new String[]{null, "js", "selection_required"})
            assertEquals(STOPPED, MonitorRecoveryPolicy.decide(engine, DEVICE, true, true, false, 1000, 0));
        assertEquals(STOPPED, MonitorRecoveryPolicy.decide("native", null, true, true, false, 1000, 0));
        assertEquals(STOPPED, MonitorRecoveryPolicy.decide("native", "?", true, true, false, 1000, 0));
    }

    @Test public void enabledMissingServiceRestartsWithoutTheDashboard() {
        assertEquals(START, MonitorRecoveryPolicy.decide("native", DEVICE, true, true, false, 1000, 0));
    }

    @Test public void runningEngineIsNotRestartedEvenAfterCooldownExpires() {
        assertEquals(RUNNING, MonitorRecoveryPolicy.decide("native", DEVICE, true, true, true, 1000, 0));
    }

    @Test public void lockedStorageAndRevokedPermissionDoNotStartBle() {
        assertEquals(LOCKED, MonitorRecoveryPolicy.decide("native", DEVICE, false, true, false, 1000, 0));
        assertEquals(PERMISSION_REQUIRED, MonitorRecoveryPolicy.decide("native", DEVICE, true, false, false, 1000, 0));
    }

    @Test public void failedStartupWaitsUntilDeadlineAndSurvivesClockRollback() {
        assertEquals(COOLDOWN, MonitorRecoveryPolicy.decide("native", DEVICE, true, true, false, 1000, 31000));
        assertEquals(START, MonitorRecoveryPolicy.decide("native", DEVICE, true, true, false, 31000, 31000));
        assertEquals(START, MonitorRecoveryPolicy.decide("native", DEVICE, true, true, false, 1000, 10000000));
    }

    @Test public void retriesBackOffWithBoundedJitterRatherThanAnImmediateLoop() {
        assertEquals(30000, MonitorRecoveryPolicy.retryDelay(1, 0));
        assertEquals(60000, MonitorRecoveryPolicy.retryDelay(2, 0));
        assertEquals(120000, MonitorRecoveryPolicy.retryDelay(3, 0));
        for (int attempt : new int[]{0, 1, 3, 6, 50, Integer.MAX_VALUE}) {
            long low = MonitorRecoveryPolicy.retryDelay(attempt, 0);
            long high = MonitorRecoveryPolicy.retryDelay(attempt, 1);
            assertTrue(low >= 30000);
            assertTrue(high > low);
            assertTrue(high <= 15 * 60000);
        }
    }
}
