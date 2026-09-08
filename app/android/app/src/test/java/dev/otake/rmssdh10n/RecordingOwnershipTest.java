package dev.otake.rmssdh10n;

import org.junit.Test;
import static org.junit.Assert.*;

public class RecordingOwnershipTest {
    @Test public void uncertaintyNeverAliasesInactiveOrOwned() {
        assertSame(RecordingOwnership.Kind.UNKNOWN, RecordingOwnership.classify(null, null, "rmssd-"));
        assertSame(RecordingOwnership.Kind.UNKNOWN, RecordingOwnership.classify(true, "", "rmssd-"));
        assertSame(RecordingOwnership.Kind.INACTIVE, RecordingOwnership.classify(false, "foreign", "rmssd-"));
    }
    @Test public void activeOwnerMustBeExplicit() {
        assertSame(RecordingOwnership.Kind.OURS, RecordingOwnership.classify(true, "rmssd-123", "rmssd-"));
        assertSame(RecordingOwnership.Kind.FOREIGN, RecordingOwnership.classify(true, "polar-beat", "rmssd-"));
    }
}
