package dev.otake.rmssdh10n;

import android.util.Log;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;

import dev.otake.rmssdh10n.hrv.Backfill;

/**
 * DB-backed {@link PolarBle.RecordingStore}: persists the H10 recording lifecycle so a
 * gap recording survives an app/OS restart (the start-anchor + state live in HrvDb, not
 * just process memory), and replays a fetched RR gap into 1 Hz points at their
 * start-anchored timestamps.
 *
 * Split out of HrvEngine so the lifecycle + replay logic is unit-testable behind the
 * narrow {@link Db} surface (no SQLite needed). The order/atomicity contract is enforced
 * by {@link PolarBle}: stop → fetch → recPersistGap (one backfillCommit txn) → durable →
 * remove → start; a failed persist returns false so PolarBle does NOT remove (it retries,
 * never overwriting the single device slot before the gap is safely captured).
 */
final class RecordingBackfillStore implements PolarBle.RecordingStore {
    // Logged under the engine's tag so existing "HrvEngine: [backfill]" log greps still match.
    private static final String TAG = "HrvEngine";
    static final String OWNER = "rmssd-h10n";
    static final int SCHEMA = 2;

    /** Narrow DB surface this store needs — HrvDb implements it; unit tests fake it. */
    interface Db {
        HrvDb.Rec recordingGetOpen(int user, String mac);
        void recordingStarting(String exId, String mac, int user, String owner, int schemaVersion, long startRequestMs);
        void recordingActive(String exId, long startAckMs);
        void recordingSetState(String exId, String state);
        void recordingSetFetched(String exId, long rrCount, long durationMs, int truncated);
        void recordingMarkRemoved(String exId);
        Set<Long> pointTimesIn(int user, long fromMs, long toMs);
        /** Returns the number of points ACTUALLY inserted (CONFLICT_IGNORE may drop a second a
         *  live point already filled), so the ledger/UI count reflects reality. */
        long backfillCommit(int user, List<Object[]> points, long fromMs, long toMs,
                            long anchorStartMs, String exId, int truncated, int baselineVersion);
        boolean backfillIsCommitted(int user, String exId, long anchorStartMs);
        void recordingQuarantine(String exId, long anchorStartMs, double[] rrMs, String reason);
        boolean recordingIsDiscarded(String exId);
    }

    /** Live engine context read at callback time (mac/user/baseline change across a session). */
    interface Host {
        String deviceMac();
        int user();
        BaselineRef baseline();
        void setRestored(int count);                                                // early-out: count only
        void onBackfilled(int restored, long fromMs, long toMs, boolean truncated);  // success: count + emit
    }

    /** Atomic snapshot of the resting baseline + its version (read under the engine lock).
     *  rmssd/hr are 0 when no baseline is frozen yet (matching the pre-extraction default). */
    static final class BaselineRef {
        final double rmssd, hr; final int version;
        BaselineRef(double rmssd, double hr, int version) { this.rmssd = rmssd; this.hr = hr; this.version = version; }
    }

    private final Db db;
    private final Host host;
    RecordingBackfillStore(Db db, Host host) { this.db = db; this.host = host; }

    @Override public OpenRec getOpenRecording() {
        HrvDb.Rec r = db.recordingGetOpen(host.user(), host.deviceMac());
        return (r == null) ? null : new OpenRec(r.exId, r.anchorStartMs, r.state);
    }
    @Override public void recStarting(String exId, long startRequestMs) {
        db.recordingStarting(exId, host.deviceMac(), host.user(), OWNER, SCHEMA, startRequestMs);
    }
    @Override public void recActive(String exId, long startAckMs) { db.recordingActive(exId, startAckMs); }
    @Override public void recFetching(String exId, long rrCount, long durationMs, boolean truncated) {
        db.recordingSetFetched(exId, rrCount, durationMs, truncated ? 1 : 0);
    }
    @Override public PersistResult recPersistGap(double[] rrMs, long anchorStartMs, String exId, boolean truncated) {
        PersistResult result = replayAndPersistGap(rrMs, anchorStartMs, exId, truncated);
        if (result.isDurable()) db.recordingSetState(exId, "persisted");
        return result;
    }
    @Override public void recRemoved(String exId) { db.recordingMarkRemoved(exId); }
    @Override public boolean canRemoveDiscarded(String exId) { return db.recordingIsDiscarded(exId); }

    /** Replay RR fetched from the H10 gap recording into 1 Hz points at their start-anchored
     *  timestamps and persist them durably + atomically with a ledger row. Returns true once
     *  durable so PolarBle may remove the device-side exercise; false on failure (PolarBle then
     *  retries and does NOT remove). Idempotent on a re-import (same anchor → same seconds).
     *  Runs on PolarBle's worker thread. */
    private PersistResult replayAndPersistGap(double[] rrMs, long anchorStartMs, String exId, boolean truncated) {
        try {
            if (db.backfillIsCommitted(host.user(), exId, anchorStartMs)) {
                Log.i(TAG, "[backfill] already committed " + exId);
                return PersistResult.ALREADY_COMMITTED;
            }
            long now = System.currentTimeMillis();
            // Clock sanity: a future / absurd anchor would misplace the whole gap. Preserve the
            // fetched RR payload in SQLite before allowing the device slot to be reclaimed.
            if (anchorStartMs <= 0 || anchorStartMs > now + 60_000L) {
                Log.w(TAG, "backfill: implausible anchor " + anchorStartMs + " (now=" + now + ") — quarantining");
                db.recordingQuarantine(exId, anchorStartMs, rrMs, "invalid_anchor");
                host.setRestored(0);
                return PersistResult.QUARANTINED;
            }
            BaselineRef base = host.baseline();
            List<Backfill.Pt> pts = Backfill.replay(rrMs, anchorStartMs, base.rmssd, base.hr);
            if (pts.isEmpty()) {
                db.recordingQuarantine(exId, anchorStartMs, rrMs, "empty_replay");
                host.setRestored(0);
                return PersistResult.QUARANTINED;
            }
            long from = pts.get(0).tMs, to = pts.get(pts.size() - 1).tMs;
            int user = host.user();
            Set<Long> existing = db.pointTimesIn(user, from, to);
            List<Object[]> rows = buildRows(pts, existing, now);
            // One transaction: INSERT OR IGNORE points + ledger row — crash-atomic and idempotent
            // so a removeExercise failure can be retried without double-counting. The returned
            // count is the ACTUAL inserts (a live point may have filled a boundary second between
            // pointTimesIn above and the commit), so 'restored' never over-reports.
            int restored = (int) db.backfillCommit(user, rows, from, to, anchorStartMs, exId,
                    truncated ? 1 : 0, base.version);
            Log.i(TAG, "[backfill] restored " + restored + " pts over " + ((to - from) / 1000) + "s"
                    + (truncated ? " (truncated)" : ""));
            host.onBackfilled(restored, from, to, truncated);
            return PersistResult.COMMITTED;
        } catch (Throwable t) {
            Log.e(TAG, "backfill failed", t);
            try {
                db.recordingQuarantine(exId, anchorStartMs, rrMs, "replay_or_commit_failure");
                host.setRestored(0);
                return PersistResult.QUARANTINED;
            } catch (Throwable quarantineFailure) {
                Log.e(TAG, "backfill quarantine failed", quarantineFailure);
                return PersistResult.FAILED;
            }
        }
    }

    /** Pure: skip points that already exist (a live boundary second) or fall in the future
     *  (clock-skew guard), and build each surviving point's row {tMs, json}. Backfill points
     *  carry null posture/steps so live seconds are never clobbered by INSERT OR IGNORE. */
    static List<Object[]> buildRows(List<Backfill.Pt> pts, Set<Long> existing, long now) throws Exception {
        List<Object[]> rows = new ArrayList<>();
        for (Backfill.Pt pt : pts) {
            if (pt.tMs > now + 60_000L) continue;       // never write future points
            if (existing.contains(pt.tMs)) continue;    // dedup: don't overwrite a live second
            String json = HrvJson.buildPointJson(HrvTime.localIso(pt.tMs), pt.rmssd,
                    pt.hr != null ? (double) pt.hr : null, pt.resp, pt.tone,
                    null, null, null, null, 0, null, null);
            rows.add(new Object[]{ pt.tMs, json });
        }
        return rows;
    }
}
