package dev.otake.rmssdh10n;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import dev.otake.rmssdh10n.hrv.Backfill;

/** Locks the recording lifecycle + gap-replay contract behind a fake DB (no SQLite):
 *  state delegation, dedup/future-skip in buildRows, persist-failure → false (so PolarBle
 *  never removes the un-captured slot), and the implausible-anchor no-op. */
public class RecordingBackfillStoreTest {

    static class FakeDb implements RecordingBackfillStore.Db {
        String startedExId, startedMac, startedOwner; int startedUser, startedSchema; long startedReq;
        long activeAck;
        int fetchTrunc;
        final List<String> states = new ArrayList<>();
        String removedExId;
        Set<Long> existing = new HashSet<>();
        boolean committed; int committedRestored; long committedBlv = -1;
        boolean commitThrows;

        @Override public HrvDb.Rec recordingGetOpen() { return null; }
        @Override public void recordingStarting(String exId, String mac, int user, String owner, int schemaVersion, long startRequestMs) {
            startedExId = exId; startedMac = mac; startedUser = user; startedOwner = owner; startedSchema = schemaVersion; startedReq = startRequestMs;
        }
        @Override public void recordingActive(String exId, long startAckMs) { activeAck = startAckMs; }
        @Override public void recordingSetState(String exId, String state) { states.add(state); }
        @Override public void recordingSetFetched(String exId, long rrCount, long durationMs, int truncated) { fetchTrunc = truncated; }
        @Override public void recordingMarkRemoved(String exId) { removedExId = exId; }
        @Override public Set<Long> pointTimesIn(long fromMs, long toMs) { return existing; }
        @Override public long backfillCommit(List<Object[]> points, long fromMs, long toMs, int restored,
                                             long anchorStartMs, String exId, int truncated, int baselineVersion) {
            if (commitThrows) throw new RuntimeException("commit boom");
            committed = true; committedRestored = restored; committedBlv = baselineVersion;
            return 1L;
        }
    }

    static class FakeHost implements RecordingBackfillStore.Host {
        String mac = "AA:BB"; int user = 2;
        RecordingBackfillStore.BaselineRef base = new RecordingBackfillStore.BaselineRef(40, 60, 7);
        Integer setRestored; Integer backfilledRestored; boolean backfilledCalled;
        @Override public String deviceMac() { return mac; }
        @Override public int user() { return user; }
        @Override public RecordingBackfillStore.BaselineRef baseline() { return base; }
        @Override public void setRestored(int count) { setRestored = count; }
        @Override public void onBackfilled(int restored, long fromMs, long toMs, boolean truncated) {
            backfilledCalled = true; backfilledRestored = restored;
        }
    }

    private static double[] beats(int n, double rrMs) { double[] a = new double[n]; Arrays.fill(a, rrMs); return a; }

    @Test public void buildRowsSkipsExistingAndFutureSeconds() throws Exception {
        long now = 1_700_000_000_000L;
        long t0 = now - 2000, t1 = now - 1000, future = now + 120_000L;
        List<Backfill.Pt> pts = Arrays.asList(
            new Backfill.Pt(t0, 40.0, 60, 14.0, "calm"),
            new Backfill.Pt(t1, 41.0, 61, 14.0, "calm"),
            new Backfill.Pt(future, 42.0, 62, 14.0, "calm"));
        Set<Long> existing = new HashSet<>(Arrays.asList(t1));
        List<Object[]> rows = RecordingBackfillStore.buildRows(pts, existing, now);
        assertEquals(1, rows.size());                       // t1 deduped, future skipped
        assertEquals(t0, (long) (Long) rows.get(0)[0]);
        assertTrue(((String) rows.get(0)[1]).contains("\"sleepPos\":null")); // backfill carries null posture
    }

    @Test public void lifecycleDelegatesInOrderAndPersists() {
        FakeDb db = new FakeDb(); FakeHost host = new FakeHost();
        RecordingBackfillStore store = new RecordingBackfillStore(db, host);
        store.recStarting("rmssd-1", 111L);
        assertEquals("rmssd-1", db.startedExId);
        assertEquals("AA:BB", db.startedMac);
        assertEquals(2, db.startedUser);
        assertEquals(RecordingBackfillStore.OWNER, db.startedOwner);
        assertEquals(RecordingBackfillStore.SCHEMA, db.startedSchema);
        store.recActive("rmssd-1", 222L);
        assertEquals(222L, db.activeAck);
        store.recFetching("rmssd-1", 100, 90_000, true);
        assertEquals(1, db.fetchTrunc);

        long now = System.currentTimeMillis();
        boolean ok = store.recPersistGap(beats(40, 800), now - 32_000L, "rmssd-1", false);
        assertTrue(ok);
        assertTrue(db.committed);
        assertEquals(7, db.committedBlv);                 // baseline version threaded through to the ledger
        assertTrue(db.states.contains("persisted"));      // success marks the recording persisted
        assertTrue(host.backfilledCalled);
        store.recRemoved("rmssd-1");
        assertEquals("rmssd-1", db.removedExId);
    }

    @Test public void persistFailureReturnsFalseAndDoesNotMarkPersisted() {
        FakeDb db = new FakeDb(); db.commitThrows = true;
        RecordingBackfillStore store = new RecordingBackfillStore(db, new FakeHost());
        long now = System.currentTimeMillis();
        boolean ok = store.recPersistGap(beats(40, 800), now - 32_000L, "rmssd-1", false);
        assertFalse(ok);                              // a failed commit must NOT report durable
        assertFalse(db.states.contains("persisted")); // so the device slot is retried, not removed
    }

    @Test public void implausibleAnchorIsNoOpButRemovable() {
        FakeDb db = new FakeDb(); FakeHost host = new FakeHost();
        RecordingBackfillStore store = new RecordingBackfillStore(db, host);
        long now = System.currentTimeMillis();
        boolean ok = store.recPersistGap(beats(40, 800), now + 5_000_000_000L, "rmssd-1", false);
        assertTrue(ok);                               // true so the device exercise is still removed
        assertFalse(db.committed);                    // but nothing is written
        assertEquals(Integer.valueOf(0), host.setRestored);
    }
}
