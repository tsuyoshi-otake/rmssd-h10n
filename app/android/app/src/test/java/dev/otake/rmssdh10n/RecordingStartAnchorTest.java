package dev.otake.rmssdh10n;

import org.junit.Test;
import java.util.concurrent.atomic.AtomicLong;
import java.util.Arrays;
import java.util.List;

import dev.otake.rmssdh10n.hrv.Backfill;
import static org.junit.Assert.*;

public class RecordingStartAnchorTest {
    @Test public void eachRetryGetsItsOwnIdentityAndAnchor() {
        AtomicLong clock = new AtomicLong(1_000);
        RecordingStartAttempt first = RecordingStartAttempt.create(clock::get);
        clock.addAndGet(5_000);
        RecordingStartAttempt second = RecordingStartAttempt.create(clock::get);
        assertEquals(1_000, first.anchorStartMs);
        assertEquals(6_000, second.anchorStartMs);
        assertNotEquals(first.exId, second.exId);
        assertTrue(second.exId.endsWith("6000"));

        double[] rr = new double[20]; Arrays.fill(rr, 1000);
        List<Backfill.Pt> placed = Backfill.replay(rr, second.anchorStartMs, 40, 60);
        List<Backfill.Pt> stale = Backfill.replay(rr, first.anchorStartMs, 40, 60);
        assertEquals(5_000, placed.get(0).tMs - stale.get(0).tMs);
        assertEquals(7_000, placed.get(0).tMs); // first interval ends 1 s after the successful request
    }

    @Test public void timeoutOutcomeIsReconciledWithoutDestructiveSecondStart() {
        assertEquals(RecordingStartAttempt.Resolution.ACTIVE_UNCERTAIN,
                RecordingStartAttempt.resolveFailure(false, RecordingOwnership.Kind.OURS,
                        "rmssd-1", "rmssd-1"));
        assertEquals(RecordingStartAttempt.Resolution.PRESERVE_UNCERTAIN,
                RecordingStartAttempt.resolveFailure(false, RecordingOwnership.Kind.UNKNOWN,
                        "rmssd-1", null));
        assertEquals(RecordingStartAttempt.Resolution.PRESERVE_UNCERTAIN,
                RecordingStartAttempt.resolveFailure(false, RecordingOwnership.Kind.FOREIGN,
                        "rmssd-1", "beat-1"));
        assertEquals(RecordingStartAttempt.Resolution.RETRY_NEW_ATTEMPT,
                RecordingStartAttempt.resolveFailure(false, RecordingOwnership.Kind.INACTIVE,
                        "rmssd-1", null));
    }

    @Test public void definiteFailuresUseFreshAnchorsAndRestartWithoutAckIsQuarantined() {
        AtomicLong clock = new AtomicLong(1_000);
        String previous = null;
        for (int i = 0; i < 3; i++) {
            RecordingStartAttempt attempt = RecordingStartAttempt.create(clock::get);
            assertNotEquals(previous, attempt.exId);
            assertEquals(RecordingStartAttempt.Resolution.RETRY_NEW_ATTEMPT,
                    RecordingStartAttempt.resolveFailure(true, RecordingOwnership.Kind.UNKNOWN,
                            attempt.exId, null));
            previous = attempt.exId;
            clock.addAndGet(5_000);
        }
        assertTrue(RecordingStartAttempt.requiresQuarantine("starting"));
        assertTrue(RecordingStartAttempt.requiresQuarantine("active_uncertain"));
        assertFalse(RecordingStartAttempt.requiresQuarantine("active"));
    }
}
