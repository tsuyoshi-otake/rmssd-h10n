package dev.otake.rmssdh10n;

import org.junit.Test;
import static org.junit.Assert.*;

public class RecordingRecoveryPolicyTest {
    @Test public void storageFailureNeverReconnectsOrRebonds() {
        assertSame(RecordingRecoveryPolicy.Action.RETRY,
                RecordingRecoveryPolicy.decide(RecordingRecoveryPolicy.Failure.STORAGE, 0, 6, 0, 2, false, true, false));
        assertSame(RecordingRecoveryPolicy.Action.PAUSE_STORAGE,
                RecordingRecoveryPolicy.decide(RecordingRecoveryPolicy.Failure.STORAGE, 6, 6, 2, 2, true, true, false));
    }
    @Test public void pftpEscalationRequiresAuthorizationEvidenceForRebond() {
        assertSame(RecordingRecoveryPolicy.Action.RECONNECT,
                RecordingRecoveryPolicy.decide(RecordingRecoveryPolicy.Failure.PFTP, 6, 6, 0, 2, false, true, false));
        assertSame(RecordingRecoveryPolicy.Action.NEEDS_ACTION,
                RecordingRecoveryPolicy.decide(RecordingRecoveryPolicy.Failure.PFTP, 6, 6, 2, 2, false, true, false));
        assertSame(RecordingRecoveryPolicy.Action.REBOND,
                RecordingRecoveryPolicy.decide(RecordingRecoveryPolicy.Failure.PFTP, 6, 6, 2, 2, true, true, false));
    }
}
