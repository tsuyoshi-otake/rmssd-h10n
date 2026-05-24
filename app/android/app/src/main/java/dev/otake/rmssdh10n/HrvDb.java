package dev.otake.rmssdh10n;

import android.content.ContentValues;
import android.content.Context;
import android.database.Cursor;
import android.database.sqlite.SQLiteDatabase;
import android.database.sqlite.SQLiteOpenHelper;

import java.util.ArrayList;
import java.util.List;

/**
 * Durable store for the native engine — the source of truth the WebView reads
 * on resume. The Capacitor plugin can only push live frames while a WebView is
 * attached, so everything is also written here; on resume the dashboard pulls
 * the latest status and the points accumulated since its watermark.
 *
 *   points(t_ms PK, json)   one row per 1 Hz frame
 *   status_latest(id=1)     newest status snapshot
 *   kv(k PK, v)             posture/supine refs, latSign, stepsDay, engine, etc.
 *   recordings(ex_id PK)    H10 onboard-recording state machine (start-anchor +
 *                           lifecycle state). Survives app/OS restart so the
 *                           lingering gap recording is RECOVERED, not discarded.
 *   backfill_imports(id)    ledger of restored gap ranges; merged_to_ui drives a
 *                           WebView-independent catch-up so a service-only restore
 *                           still surfaces past points in history/trend.
 *
 * Writes are batched in a transaction (flush every few seconds or N points) to
 * keep the background loop cheap.
 */
public final class HrvDb extends SQLiteOpenHelper {
    private static final String NAME = "hrv.db";
    private static final int VERSION = 2;

    public HrvDb(Context ctx) { super(ctx.getApplicationContext(), NAME, null, VERSION); }

    @Override public void onCreate(SQLiteDatabase db) {
        db.execSQL("CREATE TABLE points(t_ms INTEGER PRIMARY KEY, json TEXT NOT NULL)");
        db.execSQL("CREATE TABLE status_latest(id INTEGER PRIMARY KEY CHECK(id=1), json TEXT, t_ms INTEGER)");
        db.execSQL("CREATE TABLE kv(k TEXT PRIMARY KEY, v TEXT)");
        createRecordingTables(db);
    }

    private static void createRecordingTables(SQLiteDatabase db) {
        db.execSQL("CREATE TABLE IF NOT EXISTS recordings("
                + "ex_id TEXT PRIMARY KEY, mac TEXT, user INTEGER, owner TEXT, schema_version INTEGER,"
                + "sample_type TEXT, start_request_ms INTEGER, start_ack_ms INTEGER, anchor_start_ms INTEGER,"
                + "state TEXT, rr_count INTEGER, duration_ms INTEGER, truncated INTEGER,"
                + "remove_status TEXT, updated_at INTEGER)");
        db.execSQL("CREATE TABLE IF NOT EXISTS backfill_imports("
                + "id INTEGER PRIMARY KEY AUTOINCREMENT, from_ms INTEGER, to_ms INTEGER, restored INTEGER,"
                + "anchor_start_ms INTEGER, ex_id TEXT, truncated INTEGER, baseline_version INTEGER,"
                + "merged_to_ui INTEGER DEFAULT 0, created_at INTEGER)");
    }

    @Override public void onUpgrade(SQLiteDatabase db, int oldV, int newV) {
        // Additive migration — NEVER drop points/status/kv (that is the user's real
        // HRV history). v1→v2 only adds the recording state-machine + import ledger.
        if (oldV < 2) createRecordingTables(db);
    }

    // --- batched point writes ----------------------------------------------
    private final List<Object[]> pending = new ArrayList<>();    // {Long tMs, String json}

    public synchronized void addPoint(long tMs, String json) {
        pending.add(new Object[]{ tMs, json });
        if (pending.size() >= 60) flush();
    }

    public synchronized void flush() {
        if (pending.isEmpty()) return;
        SQLiteDatabase db = getWritableDatabase();
        db.beginTransaction();
        try {
            for (Object[] p : pending) {
                ContentValues cv = new ContentValues();
                cv.put("t_ms", (Long) p[0]);
                cv.put("json", (String) p[1]);
                db.insertWithOnConflict("points", null, cv, SQLiteDatabase.CONFLICT_REPLACE);
            }
            db.setTransactionSuccessful();
        } finally {
            db.endTransaction();
        }
        pending.clear();
    }

    public void setStatus(String json, long tMs) {
        ContentValues cv = new ContentValues();
        cv.put("id", 1);
        cv.put("json", json);
        cv.put("t_ms", tMs);
        getWritableDatabase().insertWithOnConflict("status_latest", null, cv, SQLiteDatabase.CONFLICT_REPLACE);
    }

    public String getStatus() {
        try (Cursor c = getReadableDatabase().rawQuery("SELECT json FROM status_latest WHERE id=1", null)) {
            return c.moveToFirst() ? c.getString(0) : null;
        }
    }

    /** JSON array string of point jsons with t_ms > since (ascending), up to limit. */
    public PointsPage getPointsSince(long since, int limit) {
        StringBuilder sb = new StringBuilder("[");
        int n = 0;
        long lastT = since;
        try (Cursor c = getReadableDatabase().rawQuery(
                "SELECT t_ms, json FROM points WHERE t_ms>? ORDER BY t_ms ASC LIMIT ?",
                new String[]{ String.valueOf(since), String.valueOf(limit + 1) })) {
            while (c.moveToNext()) {
                if (n == limit) { return new PointsPage(sb.append("]").toString(), n, true, lastT); }
                if (n > 0) sb.append(",");
                lastT = c.getLong(0);
                sb.append(c.getString(1));
                n++;
            }
        }
        return new PointsPage(sb.append("]").toString(), n, false, lastT);
    }

    public static final class PointsPage {
        public final String jsonArray;
        public final int count;
        public final boolean hasMore;
        public final long lastT;
        PointsPage(String jsonArray, int count, boolean hasMore, long lastT) {
            this.jsonArray = jsonArray; this.count = count; this.hasMore = hasMore; this.lastT = lastT;
        }
    }

    public void kvPut(String k, String v) {
        ContentValues cv = new ContentValues();
        cv.put("k", k);
        cv.put("v", v);
        getWritableDatabase().insertWithOnConflict("kv", null, cv, SQLiteDatabase.CONFLICT_REPLACE);
    }

    public String kvGet(String k) {
        try (Cursor c = getReadableDatabase().rawQuery("SELECT v FROM kv WHERE k=?", new String[]{ k })) {
            return c.moveToFirst() ? c.getString(0) : null;
        }
    }

    /** Whole-second t_ms values present in [fromMs, toMs] inclusive — used by the
     *  offline backfill to skip seconds already covered by live points. */
    public synchronized java.util.Set<Long> pointTimesIn(long fromMs, long toMs) {
        flush(); // make any pending writes visible to this read
        java.util.Set<Long> s = new java.util.HashSet<>();
        try (Cursor c = getReadableDatabase().rawQuery(
                "SELECT t_ms FROM points WHERE t_ms>=? AND t_ms<=?",
                new String[]{ String.valueOf(fromMs), String.valueOf(toMs) })) {
            while (c.moveToNext()) s.add(c.getLong(0));
        }
        return s;
    }

    /** Drop points older than the cutoff (keeps the DB bounded ~14 days). */
    public void prune(long cutoffMs) {
        getWritableDatabase().delete("points", "t_ms<?", new String[]{ String.valueOf(cutoffMs) });
    }

    // --- H10 recording state machine (survives app/OS restart) ---------------

    /** A non-terminal recording row (state ∈ starting|active|fetching|persisted). */
    public static final class Rec {
        public final String exId, mac, owner, sampleType, state, removeStatus;
        public final int user, schemaVersion, truncated;
        public final long startRequestMs, startAckMs, anchorStartMs, rrCount, durationMs;
        Rec(String exId, String mac, int user, String owner, int schemaVersion, String sampleType,
            long startRequestMs, long startAckMs, long anchorStartMs, String state,
            long rrCount, long durationMs, int truncated, String removeStatus) {
            this.exId = exId; this.mac = mac; this.user = user; this.owner = owner;
            this.schemaVersion = schemaVersion; this.sampleType = sampleType;
            this.startRequestMs = startRequestMs; this.startAckMs = startAckMs;
            this.anchorStartMs = anchorStartMs; this.state = state; this.rrCount = rrCount;
            this.durationMs = durationMs; this.truncated = truncated; this.removeStatus = removeStatus;
        }
    }

    /** Persist a recording as 'starting' BEFORE issuing startRecording, so the
     *  start-anchor survives an OS kill between the request and its ack. */
    public synchronized void recordingStarting(String exId, String mac, int user, String owner,
                                               int schemaVersion, long startRequestMs) {
        ContentValues cv = new ContentValues();
        cv.put("ex_id", exId); cv.put("mac", mac); cv.put("user", user); cv.put("owner", owner);
        cv.put("schema_version", schemaVersion); cv.put("sample_type", "RR");
        cv.put("start_request_ms", startRequestMs); cv.put("anchor_start_ms", startRequestMs);
        cv.put("state", "starting"); cv.put("remove_status", "pending");
        cv.put("updated_at", System.currentTimeMillis());
        getWritableDatabase().insertWithOnConflict("recordings", null, cv, SQLiteDatabase.CONFLICT_REPLACE);
    }

    public synchronized void recordingActive(String exId, long startAckMs) {
        ContentValues cv = new ContentValues();
        cv.put("state", "active"); cv.put("start_ack_ms", startAckMs);
        cv.put("updated_at", System.currentTimeMillis());
        getWritableDatabase().update("recordings", cv, "ex_id=?", new String[]{ exId });
    }

    public synchronized void recordingSetState(String exId, String state) {
        ContentValues cv = new ContentValues();
        cv.put("state", state); cv.put("updated_at", System.currentTimeMillis());
        getWritableDatabase().update("recordings", cv, "ex_id=?", new String[]{ exId });
    }

    public synchronized void recordingSetFetched(String exId, long rrCount, long durationMs, int truncated) {
        ContentValues cv = new ContentValues();
        cv.put("rr_count", rrCount); cv.put("duration_ms", durationMs); cv.put("truncated", truncated);
        cv.put("state", "fetching"); cv.put("updated_at", System.currentTimeMillis());
        getWritableDatabase().update("recordings", cv, "ex_id=?", new String[]{ exId });
    }

    public synchronized void recordingMarkRemoved(String exId) {
        ContentValues cv = new ContentValues();
        cv.put("state", "removed"); cv.put("remove_status", "done");
        cv.put("updated_at", System.currentTimeMillis());
        getWritableDatabase().update("recordings", cv, "ex_id=?", new String[]{ exId });
    }

    /** Mark every still-open recording as user-discarded so it is NOT auto-recovered
     *  after an explicit (user-initiated) stop — distinct from an OS kill. */
    public synchronized void recordingMarkDiscardedByUser() {
        ContentValues cv = new ContentValues();
        cv.put("state", "discarded_by_user"); cv.put("updated_at", System.currentTimeMillis());
        getWritableDatabase().update("recordings", cv,
                "state IN ('starting','active','fetching','persisted')", null);
    }

    /** The most recent non-terminal recording, or null. */
    public synchronized Rec recordingGetOpen() {
        try (Cursor c = getReadableDatabase().rawQuery(
                "SELECT ex_id,mac,user,owner,schema_version,sample_type,start_request_ms,start_ack_ms,"
              + "anchor_start_ms,state,rr_count,duration_ms,truncated,remove_status FROM recordings "
              + "WHERE state IN ('starting','active','fetching','persisted') "
              + "ORDER BY start_request_ms DESC LIMIT 1", null)) {
            if (!c.moveToFirst()) return null;
            return new Rec(c.getString(0), c.getString(1), c.getInt(2), c.getString(3), c.getInt(4),
                    c.getString(5), c.getLong(6), c.getLong(7), c.getLong(8), c.getString(9),
                    c.getLong(10), c.getLong(11), c.getInt(12), c.getString(13));
        }
    }

    // --- backfill import ledger + atomic point+ledger commit -----------------

    /** Atomically INSERT OR IGNORE the backfill points (so a live point already at a
     *  boundary second is never overwritten by a null-posture backfill point) AND
     *  record the import range in the ledger — one transaction, so a crash can't leave
     *  points without their ledger entry (or vice-versa). {@code points} = list of
     *  {Long tMs, String json}. Returns the new import row id. */
    public synchronized long backfillCommit(List<Object[]> points, long fromMs, long toMs, int restored,
                                            long anchorStartMs, String exId, int truncated, int baselineVersion) {
        flush(); // make pending live points visible/ordered before this txn
        SQLiteDatabase db = getWritableDatabase();
        long importId;
        db.beginTransaction();
        try {
            for (Object[] p : points) {
                ContentValues cv = new ContentValues();
                cv.put("t_ms", (Long) p[0]);
                cv.put("json", (String) p[1]);
                db.insertWithOnConflict("points", null, cv, SQLiteDatabase.CONFLICT_IGNORE);
            }
            ContentValues iv = new ContentValues();
            iv.put("from_ms", fromMs); iv.put("to_ms", toMs); iv.put("restored", restored);
            iv.put("anchor_start_ms", anchorStartMs); iv.put("ex_id", exId);
            iv.put("truncated", truncated); iv.put("baseline_version", baselineVersion);
            iv.put("merged_to_ui", 0); iv.put("created_at", System.currentTimeMillis());
            importId = db.insert("backfill_imports", null, iv);
            db.setTransactionSuccessful();
        } finally {
            db.endTransaction();
        }
        return importId;
    }

    /** Unmerged import ranges as a JSON array string for the WebView catch-up. */
    public synchronized String unmergedImportsJson() {
        StringBuilder sb = new StringBuilder("[");
        boolean first = true;
        try (Cursor c = getReadableDatabase().rawQuery(
                "SELECT id,from_ms,to_ms,restored,truncated FROM backfill_imports "
              + "WHERE merged_to_ui=0 ORDER BY id ASC", null)) {
            while (c.moveToNext()) {
                if (!first) sb.append(",");
                first = false;
                sb.append("{\"id\":").append(c.getLong(0))
                  .append(",\"fromMs\":").append(c.getLong(1))
                  .append(",\"toMs\":").append(c.getLong(2))
                  .append(",\"restored\":").append(c.getLong(3))
                  .append(",\"truncated\":").append(c.getInt(4))
                  .append("}");
            }
        }
        return sb.append("]").toString();
    }

    /** Flag the given import ids (CSV of integers) as merged into the WebView. */
    public synchronized void markImportsMerged(String csvIds) {
        if (csvIds == null) return;
        String safe = csvIds.replaceAll("[^0-9,]", ""); // ids are native — sanitize defensively
        if (safe.isEmpty()) return;
        getWritableDatabase().execSQL("UPDATE backfill_imports SET merged_to_ui=1 WHERE id IN (" + safe + ")");
    }
}
