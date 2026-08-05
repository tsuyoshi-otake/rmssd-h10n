package dev.otake.rmssdh10n;

import android.content.ContentValues;
import android.content.Context;
import android.database.Cursor;
import android.database.sqlite.SQLiteDatabase;
import android.database.sqlite.SQLiteOpenHelper;

import org.json.JSONObject;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

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
public final class HrvDb extends SQLiteOpenHelper implements RecordingBackfillStore.Db {
    private static final String NAME = "hrv.db";
    private static final int VERSION = 3;

    public HrvDb(Context ctx) {
        super(ctx.getApplicationContext(), NAME, null, VERSION);
        setWriteAheadLoggingEnabled(true);
    }

    @Override public void onCreate(SQLiteDatabase db) {
        db.execSQL("CREATE TABLE points(t_ms INTEGER PRIMARY KEY, json TEXT NOT NULL)");
        db.execSQL("CREATE TABLE status_latest(id INTEGER PRIMARY KEY CHECK(id=1), json TEXT, t_ms INTEGER)");
        db.execSQL("CREATE TABLE kv(k TEXT PRIMARY KEY, v TEXT)");
        createRecordingTables(db);
        createV3Tables(db);
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
        createRecordingQuarantineTable(db);
    }

    private static void createRecordingQuarantineTable(SQLiteDatabase db) {
        db.execSQL("CREATE TABLE IF NOT EXISTS recording_quarantine("
                + "ex_id TEXT PRIMARY KEY, anchor_start_ms INTEGER, rr_blob BLOB NOT NULL,"
                + "rr_count INTEGER NOT NULL, reason TEXT NOT NULL, created_at INTEGER NOT NULL)");
    }

    /** User-scoped source-of-truth tables. The v2 tables remain untouched as legacy_unassigned
     *  data because their rows contain no trustworthy user identity. */
    private static void createV3Tables(SQLiteDatabase db) {
        db.execSQL("CREATE TABLE IF NOT EXISTS points_v3("
                + "user_id INTEGER NOT NULL,t_ms INTEGER NOT NULL,json TEXT NOT NULL,"
                + "rmssd REAL,hr REAL,resp REAL,lean REAL,steps INTEGER,"
                + "tone TEXT,posture TEXT,body TEXT,sleep_pos TEXT,source TEXT NOT NULL,"
                + "baseline_version INTEGER,change_seq INTEGER NOT NULL,"
                + "PRIMARY KEY(user_id,t_ms)) WITHOUT ROWID");
        db.execSQL("CREATE INDEX IF NOT EXISTS idx_points_v3_user_time ON points_v3(user_id,t_ms)");
        db.execSQL("CREATE TABLE IF NOT EXISTS status_latest_v3("
                + "user_id INTEGER PRIMARY KEY,json TEXT,t_ms INTEGER)");
        db.execSQL("CREATE TABLE IF NOT EXISTS backfill_imports_v3("
                + "id INTEGER PRIMARY KEY AUTOINCREMENT,user_id INTEGER NOT NULL,"
                + "from_ms INTEGER,to_ms INTEGER,restored INTEGER,anchor_start_ms INTEGER,"
                + "ex_id TEXT,truncated INTEGER,baseline_version INTEGER,change_seq INTEGER NOT NULL,"
                + "merged_to_ui INTEGER DEFAULT 0,created_at INTEGER)");
        db.execSQL("CREATE UNIQUE INDEX IF NOT EXISTS idx_backfill_v3_artifact "
                + "ON backfill_imports_v3(user_id,ex_id,anchor_start_ms)");
        db.execSQL("CREATE TABLE IF NOT EXISTS sync_meta(id INTEGER PRIMARY KEY CHECK(id=1),change_seq INTEGER NOT NULL)");
        db.execSQL("INSERT OR IGNORE INTO sync_meta(id,change_seq) VALUES(1,0)");
        db.execSQL("CREATE TABLE IF NOT EXISTS migration_journal("
                + "name TEXT PRIMARY KEY,state TEXT NOT NULL,last_t_ms INTEGER DEFAULT 0,updated_at INTEGER NOT NULL)");
        db.execSQL("INSERT OR IGNORE INTO migration_journal(name,state,last_t_ms,updated_at) "
                + "VALUES('v2_points_user_assignment','legacy_unassigned',0,strftime('%s','now')*1000)");
        db.execSQL("CREATE TABLE IF NOT EXISTS aggregate_buckets("
                + "user_id INTEGER NOT NULL,width_ms INTEGER NOT NULL,start_ms INTEGER NOT NULL,"
                + "rmssd_sum REAL,rmssd_count INTEGER NOT NULL,hr_sum REAL,hr_count INTEGER NOT NULL,"
                + "resp_sum REAL,resp_count INTEGER NOT NULL,lean_sum REAL,lean_count INTEGER NOT NULL,"
                + "steps_sum INTEGER NOT NULL,point_count INTEGER NOT NULL,"
                + "tone_counts TEXT,posture_counts TEXT,body_counts TEXT,sleep_counts TEXT,"
                + "change_seq INTEGER NOT NULL,PRIMARY KEY(user_id,width_ms,start_ms)) WITHOUT ROWID");
    }

    @Override public void onUpgrade(SQLiteDatabase db, int oldV, int newV) {
        // Additive migration — NEVER drop points/status/kv (that is the user's real
        // HRV history). v1→v2 only adds the recording state-machine + import ledger.
        if (oldV < 2) createRecordingTables(db);
        if (oldV < 3) {
            createRecordingQuarantineTable(db);
            createV3Tables(db);
        }
    }

    // --- batched point writes ----------------------------------------------
    private final List<Object[]> pending = new ArrayList<>();    // {Integer user, Long tMs, String json}

    public synchronized void addPoint(int user, long tMs, String json) {
        pending.add(new Object[]{ user, tMs, json });
        if (pending.size() >= 60) flush();
    }

    public synchronized void flush() {
        if (pending.isEmpty()) return;
        SQLiteDatabase db = getWritableDatabase();
        db.beginTransaction();
        try {
            long changeSeq = nextChangeSeq(db);
            Set<String> touched = new HashSet<>();
            for (Object[] p : pending) {
                int user = (Integer) p[0];
                long tMs = (Long) p[1];
                String json = (String) p[2];
                ContentValues cv = pointValues(user, tMs, json, "live", null, changeSeq);
                db.insertWithOnConflict("points_v3", null, cv, SQLiteDatabase.CONFLICT_REPLACE);
                markTouched(touched, user, tMs);
            }
            recomputeTouched(db, touched, changeSeq);
            db.setTransactionSuccessful();
        } finally {
            db.endTransaction();
        }
        pending.clear();
    }

    public void setStatus(int user, String json, long tMs) {
        ContentValues cv = new ContentValues();
        cv.put("user_id", user);
        cv.put("json", json);
        cv.put("t_ms", tMs);
        getWritableDatabase().insertWithOnConflict("status_latest_v3", null, cv, SQLiteDatabase.CONFLICT_REPLACE);
    }

    public String getStatus(int user) {
        try (Cursor c = getReadableDatabase().rawQuery(
                "SELECT json FROM status_latest_v3 WHERE user_id=?", new String[]{ String.valueOf(user) })) {
            return c.moveToFirst() ? c.getString(0) : null;
        }
    }

    /** JSON array string of point jsons with t_ms > since (ascending), up to limit. */
    public PointsPage getPointsSince(int user, long since, int limit) {
        StringBuilder sb = new StringBuilder("[");
        int n = 0;
        long lastT = since;
        try (Cursor c = getReadableDatabase().rawQuery(
                "SELECT t_ms,json FROM points_v3 WHERE user_id=? AND t_ms>? ORDER BY t_ms ASC LIMIT ?",
                new String[]{ String.valueOf(user), String.valueOf(since), String.valueOf(limit + 1) })) {
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

    /** Latest points, returned in chronological order. Used to paint "now" before an older
     *  catch-up backlog is processed. */
    public PointsPage getLatestPoints(int user, int limit) {
        StringBuilder sb = new StringBuilder("[");
        int n = 0;
        long lastT = 0;
        try (Cursor c = getReadableDatabase().rawQuery(
                "SELECT t_ms,json FROM (SELECT t_ms,json FROM points_v3 WHERE user_id=? "
              + "ORDER BY t_ms DESC LIMIT ?) ORDER BY t_ms ASC",
                new String[]{ String.valueOf(user), String.valueOf(limit) })) {
            while (c.moveToNext()) {
                if (n > 0) sb.append(",");
                lastT = c.getLong(0);
                sb.append(c.getString(1));
                n++;
            }
        }
        return new PointsPage(sb.append("]").toString(), n, false, lastT);
    }

    /** Bounded keyset page: afterExclusive < t_ms < toExclusive. A fixed upper bound keeps
     *  concurrently arriving live rows out of the current catch-up generation. */
    public PointsPage getPointsRange(int user, long afterExclusive, long toExclusive, int limit) {
        StringBuilder sb = new StringBuilder("[");
        int n = 0;
        long lastT = afterExclusive;
        try (Cursor c = getReadableDatabase().rawQuery(
                "SELECT t_ms,json FROM points_v3 WHERE user_id=? AND t_ms>? AND t_ms<? ORDER BY t_ms ASC LIMIT ?",
                new String[]{ String.valueOf(user), String.valueOf(afterExclusive), String.valueOf(toExclusive),
                        String.valueOf(limit + 1) })) {
            while (c.moveToNext()) {
                if (n == limit) return new PointsPage(sb.append("]").toString(), n, true, lastT);
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

    public String aggregatesJson(int user, long widthMs, long fromMs, long toMs, int limit) {
        if (widthMs != AGG_WIDTHS[0] && widthMs != AGG_WIDTHS[1] && widthMs != AGG_WIDTHS[2]) return "[]";
        StringBuilder sb = new StringBuilder("[");
        boolean first = true;
        try (Cursor c = getReadableDatabase().rawQuery(
                "SELECT start_ms,rmssd_sum,rmssd_count,hr_sum,hr_count,resp_sum,resp_count,"
              + "lean_sum,lean_count,steps_sum,point_count,tone_counts,posture_counts,body_counts,sleep_counts "
              + "FROM aggregate_buckets WHERE user_id=? AND width_ms=? AND start_ms>=? AND start_ms<? "
              + "ORDER BY start_ms ASC LIMIT ?",
                new String[]{ String.valueOf(user), String.valueOf(widthMs), String.valueOf(fromMs),
                        String.valueOf(toMs), String.valueOf(Math.max(1, Math.min(4000, limit))) })) {
            while (c.moveToNext()) {
                JSONObject o = new JSONObject();
                o.put("t", c.getLong(0));
                o.put("rmssd", averageOrNull(c, 1, 2));
                o.put("hr", averageOrNull(c, 3, 4));
                o.put("resp", averageOrNull(c, 5, 6));
                o.put("lean", averageOrNull(c, 7, 8));
                o.put("steps", c.getLong(9));
                o.put("n", c.getLong(10));
                o.put("tone", dominant(c.getString(11)));
                o.put("posture", dominant(c.getString(12)));
                o.put("body", dominant(c.getString(13)));
                o.put("sleepPos", dominant(c.getString(14)));
                if (!first) sb.append(',');
                first = false;
                sb.append(o);
            }
        } catch (Exception ignored) {}
        return sb.append(']').toString();
    }

    public String diagnosticsJson(int user) {
        JSONObject out = new JSONObject();
        try {
            out.put("user", user);
            putCountRange(out, "points", "SELECT COUNT(*),MIN(t_ms),MAX(t_ms) FROM points_v3 WHERE user_id=?",
                    new String[]{ String.valueOf(user) });
            putCountRange(out, "legacyUnassigned", "SELECT COUNT(*),MIN(t_ms),MAX(t_ms) FROM points", null);
            out.put("unmergedImports", scalarLong(
                    "SELECT COUNT(*) FROM backfill_imports_v3 WHERE user_id=? AND merged_to_ui=0",
                    new String[]{ String.valueOf(user) }));
            out.put("quarantinedRecordings", scalarLong("SELECT COUNT(*) FROM recording_quarantine", null));
        } catch (Exception ignored) {}
        return out.toString();
    }

    private void putCountRange(JSONObject out, String key, String sql, String[] args) throws Exception {
        try (Cursor c = getReadableDatabase().rawQuery(sql, args)) {
            JSONObject v = new JSONObject();
            if (c.moveToFirst()) {
                v.put("count", c.getLong(0));
                v.put("fromMs", c.isNull(1) ? JSONObject.NULL : c.getLong(1));
                v.put("toMs", c.isNull(2) ? JSONObject.NULL : c.getLong(2));
            }
            out.put(key, v);
        }
    }

    private long scalarLong(String sql, String[] args) {
        try (Cursor c = getReadableDatabase().rawQuery(sql, args)) { return c.moveToFirst() ? c.getLong(0) : 0; }
    }

    private static Object averageOrNull(Cursor c, int sumCol, int countCol) {
        long n = c.getLong(countCol);
        return n > 0 && !c.isNull(sumCol) ? Math.round((c.getDouble(sumCol) / n) * 10.0) / 10.0 : JSONObject.NULL;
    }

    private static Object dominant(String countsJson) {
        if (countsJson == null) return JSONObject.NULL;
        try {
            JSONObject counts = new JSONObject(countsJson);
            String best = null;
            long bestN = Long.MIN_VALUE;
            java.util.Iterator<String> keys = counts.keys();
            while (keys.hasNext()) {
                String key = keys.next(); long n = counts.optLong(key, 0);
                if (n > bestN) { best = key; bestN = n; }
            }
            return best != null ? best : JSONObject.NULL;
        } catch (Exception ignored) { return JSONObject.NULL; }
    }

    private static long nextChangeSeq(SQLiteDatabase db) {
        db.execSQL("UPDATE sync_meta SET change_seq=change_seq+1 WHERE id=1");
        try (Cursor c = db.rawQuery("SELECT change_seq FROM sync_meta WHERE id=1", null)) {
            if (!c.moveToFirst()) throw new IllegalStateException("sync_meta row missing");
            return c.getLong(0);
        }
    }

    private static ContentValues pointValues(int user, long tMs, String json, String source,
                                             Integer baselineVersion, long changeSeq) {
        ContentValues cv = new ContentValues();
        cv.put("user_id", user);
        cv.put("t_ms", tMs);
        cv.put("json", json);
        cv.put("source", source);
        if (baselineVersion != null) cv.put("baseline_version", baselineVersion);
        cv.put("change_seq", changeSeq);
        try {
            JSONObject p = new JSONObject(json);
            putJsonNumber(cv, "rmssd", p.opt("rmssd"));
            putJsonNumber(cv, "hr", p.opt("hr"));
            putJsonNumber(cv, "resp", p.opt("resp"));
            putJsonNumber(cv, "lean", p.opt("lean"));
            Object step = p.opt("step");
            if (step instanceof Number) cv.put("steps", ((Number) step).intValue());
            Object tone = p.opt("tone");
            if (tone instanceof String) cv.put("tone", (String) tone);
            putJsonString(cv, "posture", p.opt("posture"));
            putJsonString(cv, "body", p.opt("body"));
            putJsonString(cv, "sleep_pos", p.opt("sleepPos"));
        } catch (Exception ignored) {
            // json remains the compatibility payload; typed fields may be null for a rejected
            // legacy frame and are never allowed to abort durable point persistence.
        }
        return cv;
    }

    private static void putJsonNumber(ContentValues cv, String key, Object value) {
        if (value instanceof Number) cv.put(key, ((Number) value).doubleValue());
    }

    private static void putJsonString(ContentValues cv, String key, Object value) {
        if (value instanceof String) cv.put(key, (String) value);
    }

    private static final long[] AGG_WIDTHS = { 5 * 60_000L, 15 * 60_000L, 30 * 60_000L };

    private static void markTouched(Set<String> touched, int user, long tMs) {
        for (long width : AGG_WIDTHS) {
            long start = Math.floorDiv(tMs, width) * width;
            touched.add(user + ":" + width + ":" + start);
        }
    }

    private static void recomputeTouched(SQLiteDatabase db, Set<String> touched, long changeSeq) {
        for (String key : touched) {
            String[] parts = key.split(":", 3);
            recomputeBucket(db, Integer.parseInt(parts[0]), Long.parseLong(parts[1]),
                    Long.parseLong(parts[2]), changeSeq);
        }
    }

    private static void recomputeBucket(SQLiteDatabase db, int user, long width, long start, long changeSeq) {
        long end = start + width;
        String[] args = { String.valueOf(user), String.valueOf(start), String.valueOf(end) };
        try (Cursor c = db.rawQuery(
                "SELECT SUM(rmssd),COUNT(rmssd),SUM(hr),COUNT(hr),SUM(resp),COUNT(resp),"
              + "SUM(lean),COUNT(lean),COALESCE(SUM(steps),0),COUNT(*) FROM points_v3 "
              + "WHERE user_id=? AND t_ms>=? AND t_ms<?", args)) {
            if (!c.moveToFirst() || c.getLong(9) == 0) {
                db.delete("aggregate_buckets", "user_id=? AND width_ms=? AND start_ms=?",
                        new String[]{ String.valueOf(user), String.valueOf(width), String.valueOf(start) });
                return;
            }
            ContentValues cv = new ContentValues();
            cv.put("user_id", user); cv.put("width_ms", width); cv.put("start_ms", start);
            if (!c.isNull(0)) cv.put("rmssd_sum", c.getDouble(0));
            cv.put("rmssd_count", c.getLong(1));
            if (!c.isNull(2)) cv.put("hr_sum", c.getDouble(2));
            cv.put("hr_count", c.getLong(3));
            if (!c.isNull(4)) cv.put("resp_sum", c.getDouble(4));
            cv.put("resp_count", c.getLong(5));
            if (!c.isNull(6)) cv.put("lean_sum", c.getDouble(6));
            cv.put("lean_count", c.getLong(7));
            cv.put("steps_sum", c.getLong(8)); cv.put("point_count", c.getLong(9));
            cv.put("tone_counts", categoryCounts(db, "tone", args));
            cv.put("posture_counts", categoryCounts(db, "posture", args));
            cv.put("body_counts", categoryCounts(db, "body", args));
            cv.put("sleep_counts", categoryCounts(db, "sleep_pos", args));
            cv.put("change_seq", changeSeq);
            db.insertWithOnConflict("aggregate_buckets", null, cv, SQLiteDatabase.CONFLICT_REPLACE);
        }
    }

    private static String categoryCounts(SQLiteDatabase db, String column, String[] args) {
        JSONObject out = new JSONObject();
        try (Cursor c = db.rawQuery("SELECT " + column + ",COUNT(*) FROM points_v3 "
                + "WHERE user_id=? AND t_ms>=? AND t_ms<? AND " + column + " IS NOT NULL GROUP BY " + column, args)) {
            while (c.moveToNext()) out.put(c.getString(0), c.getLong(1));
        } catch (Exception ignored) {}
        return out.toString();
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
    @Override public synchronized java.util.Set<Long> pointTimesIn(int user, long fromMs, long toMs) {
        flush(); // make any pending writes visible to this read
        java.util.Set<Long> s = new java.util.HashSet<>();
        try (Cursor c = getReadableDatabase().rawQuery(
                "SELECT t_ms FROM points_v3 WHERE user_id=? AND t_ms>=? AND t_ms<=?",
                new String[]{ String.valueOf(user), String.valueOf(fromMs), String.valueOf(toMs) })) {
            while (c.moveToNext()) s.add(c.getLong(0));
        }
        return s;
    }

    /** Drop at most one bounded chunk per maintenance tick. Avoid a single long writer lock
     *  when a device has accumulated hundreds of thousands of rows. */
    public void prune(long cutoffMs) {
        SQLiteDatabase db = getWritableDatabase();
        db.execSQL(
                "DELETE FROM points_v3 WHERE (user_id,t_ms) IN "
              + "(SELECT user_id,t_ms FROM points_v3 WHERE t_ms<? ORDER BY t_ms LIMIT 5000)",
                new Object[]{ cutoffMs });
        db.delete("aggregate_buckets", "width_ms=? AND start_ms<?",
                new String[]{ String.valueOf(AGG_WIDTHS[0]), String.valueOf(cutoffMs) });
        db.delete("aggregate_buckets", "width_ms=? AND start_ms<?",
                new String[]{ String.valueOf(AGG_WIDTHS[1]), String.valueOf(cutoffMs - 17L * 24 * 3600 * 1000) });
        db.delete("aggregate_buckets", "width_ms=? AND start_ms<?",
                new String[]{ String.valueOf(AGG_WIDTHS[2]), String.valueOf(cutoffMs - 49L * 24 * 3600 * 1000) });
    }

    /** Destructive full reset requested from the dashboard. */
    public synchronized void clearAllData() {
        pending.clear();
        SQLiteDatabase db = getWritableDatabase();
        db.beginTransaction();
        try {
            db.delete("points", null, null);
            db.delete("status_latest", null, null);
            db.delete("kv", null, null);
            db.delete("recordings", null, null);
            db.delete("backfill_imports", null, null);
            db.delete("recording_quarantine", null, null);
            db.delete("points_v3", null, null);
            db.delete("status_latest_v3", null, null);
            db.delete("backfill_imports_v3", null, null);
            db.delete("aggregate_buckets", null, null);
            db.execSQL("UPDATE sync_meta SET change_seq=0 WHERE id=1");
            db.setTransactionSuccessful();
        } finally {
            db.endTransaction();
        }
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
        getWritableDatabase().update("recordings", cv,
                "ex_id=? AND state != 'discarded_by_user'", new String[]{ exId });
    }

    public synchronized void recordingSetState(String exId, String state) {
        ContentValues cv = new ContentValues();
        cv.put("state", state); cv.put("updated_at", System.currentTimeMillis());
        getWritableDatabase().update("recordings", cv,
                "ex_id=? AND state != 'discarded_by_user'", new String[]{ exId });
    }

    public synchronized void recordingSetFetched(String exId, long rrCount, long durationMs, int truncated) {
        ContentValues cv = new ContentValues();
        cv.put("rr_count", rrCount); cv.put("duration_ms", durationMs); cv.put("truncated", truncated);
        cv.put("state", "fetching"); cv.put("updated_at", System.currentTimeMillis());
        getWritableDatabase().update("recordings", cv,
                "ex_id=? AND state != 'discarded_by_user'", new String[]{ exId });
    }

    public synchronized void recordingMarkRemoved(String exId) {
        ContentValues cv = new ContentValues();
        cv.put("state", "removed"); cv.put("remove_status", "done");
        cv.put("updated_at", System.currentTimeMillis());
        getWritableDatabase().update("recordings", cv,
                "ex_id=? AND state != 'discarded_by_user'", new String[]{ exId });
    }

    /** Mark every still-open recording as user-discarded so it is NOT auto-recovered
     *  after an explicit (user-initiated) stop — distinct from an OS kill. */
    public synchronized void recordingMarkDiscardedByUser(int user) {
        ContentValues cv = new ContentValues();
        cv.put("state", "discarded_by_user"); cv.put("updated_at", System.currentTimeMillis());
        getWritableDatabase().update("recordings", cv,
                "user=? AND state IN ('starting','active','fetching','persisted')",
                new String[]{ String.valueOf(user) });
    }

    /** The most recent non-terminal recording, or null. */
    @Override public synchronized Rec recordingGetOpen(int user, String mac) {
        try (Cursor c = getReadableDatabase().rawQuery(
                "SELECT ex_id,mac,user,owner,schema_version,sample_type,start_request_ms,start_ack_ms,"
              + "anchor_start_ms,state,rr_count,duration_ms,truncated,remove_status FROM recordings "
              + "WHERE user=? AND (mac=? OR mac IS NULL) "
              + "AND state IN ('starting','active','fetching','persisted') "
              + "ORDER BY start_request_ms DESC LIMIT 1",
                new String[]{ String.valueOf(user), mac })) {
            if (!c.moveToFirst()) return null;
            return new Rec(c.getString(0), c.getString(1), c.getInt(2), c.getString(3), c.getInt(4),
                    c.getString(5), c.getLong(6), c.getLong(7), c.getLong(8), c.getString(9),
                    c.getLong(10), c.getLong(11), c.getInt(12), c.getString(13));
        }
    }

    @Override public synchronized boolean recordingIsDiscarded(String exId) {
        try (Cursor c = getReadableDatabase().rawQuery(
                "SELECT 1 FROM recordings WHERE ex_id=? AND state='discarded_by_user' LIMIT 1",
                new String[]{ exId })) {
            return c.moveToFirst();
        }
    }

    // --- backfill import ledger + atomic point+ledger commit -----------------

    /** Atomically INSERT OR IGNORE the backfill points (so a live point already at a
     *  boundary second is never overwritten by a null-posture backfill point) AND
     *  record the import range in the ledger — one transaction, so a crash can't leave
     *  points without their ledger entry (or vice-versa). {@code points} = list of
     *  {Long tMs, String json}. Returns the number of points ACTUALLY inserted (rows a
     *  live second already occupied are ignored), which is what the ledger/UI report. */
    @Override public synchronized long backfillCommit(int user, List<Object[]> points, long fromMs, long toMs,
                                            long anchorStartMs, String exId, int truncated, int baselineVersion) {
        flush(); // make pending live points visible/ordered before this txn
        SQLiteDatabase db = getWritableDatabase();
        int inserted = 0;
        db.beginTransaction();
        try {
            long changeSeq = nextChangeSeq(db);
            Set<String> touched = new HashSet<>();
            for (Object[] p : points) {
                ContentValues cv = pointValues(user, (Long) p[0], (String) p[1],
                        "backfill", baselineVersion, changeSeq);
                // CONFLICT_IGNORE returns -1 when a live point already holds this second
                // (it landed between pointTimesIn and this txn) — count only real inserts.
                if (db.insertWithOnConflict("points_v3", null, cv, SQLiteDatabase.CONFLICT_IGNORE) != -1) {
                    inserted++;
                    markTouched(touched, user, (Long) p[0]);
                }
            }
            recomputeTouched(db, touched, changeSeq);
            ContentValues iv = new ContentValues();
            iv.put("user_id", user);
            iv.put("from_ms", fromMs); iv.put("to_ms", toMs); iv.put("restored", inserted);
            iv.put("anchor_start_ms", anchorStartMs); iv.put("ex_id", exId);
            iv.put("truncated", truncated); iv.put("baseline_version", baselineVersion);
            iv.put("change_seq", changeSeq); iv.put("merged_to_ui", 0);
            iv.put("created_at", System.currentTimeMillis());
            db.insertWithOnConflict("backfill_imports_v3", null, iv, SQLiteDatabase.CONFLICT_IGNORE);
            db.setTransactionSuccessful();
        } finally {
            db.endTransaction();
        }
        return inserted;
    }

    /** True when this exact device artifact was already committed. This closes the crash
     *  window between the point+ledger transaction and the recording state update. */
    @Override public synchronized boolean backfillIsCommitted(int user, String exId, long anchorStartMs) {
        try (Cursor c = getReadableDatabase().rawQuery(
                "SELECT 1 FROM backfill_imports_v3 WHERE user_id=? AND ex_id=? AND anchor_start_ms=? LIMIT 1",
                new String[]{ String.valueOf(user), exId, String.valueOf(anchorStartMs) })) {
            return c.moveToFirst();
        }
    }

    /** Durably retain the fetched raw RR payload when it cannot be replayed safely. The H10
     *  slot may be reclaimed only after this write succeeds; the payload remains available
     *  for diagnostics/manual recovery instead of being silently discarded. */
    @Override public synchronized void recordingQuarantine(String exId, long anchorStartMs,
                                                            double[] rrMs, String reason) {
        double[] samples = rrMs != null ? rrMs : new double[0];
        ByteBuffer buf = ByteBuffer.allocate(samples.length * 8).order(ByteOrder.LITTLE_ENDIAN);
        for (double rr : samples) buf.putDouble(rr);
        ContentValues cv = new ContentValues();
        cv.put("ex_id", exId);
        cv.put("anchor_start_ms", anchorStartMs);
        cv.put("rr_blob", buf.array());
        cv.put("rr_count", samples.length);
        cv.put("reason", reason != null ? reason : "unknown");
        cv.put("created_at", System.currentTimeMillis());
        if (getWritableDatabase().insertWithOnConflict("recording_quarantine", null, cv,
                SQLiteDatabase.CONFLICT_REPLACE) == -1) {
            throw new IllegalStateException("recording quarantine insert failed for " + exId);
        }
    }

    /** Unmerged import ranges as a JSON array string for the WebView catch-up. */
    public synchronized String unmergedImportsJson(int user) {
        StringBuilder sb = new StringBuilder("[");
        boolean first = true;
        try (Cursor c = getReadableDatabase().rawQuery(
                "SELECT id,from_ms,to_ms,restored,truncated FROM backfill_imports_v3 "
              + "WHERE user_id=? AND merged_to_ui=0 ORDER BY id ASC",
                new String[]{ String.valueOf(user) })) {
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
    public synchronized void markImportsMerged(int user, String csvIds) {
        if (csvIds == null) return;
        String safe = csvIds.replaceAll("[^0-9,]", ""); // ids are native — sanitize defensively
        if (safe.isEmpty()) return;
        getWritableDatabase().execSQL("UPDATE backfill_imports_v3 SET merged_to_ui=1 WHERE user_id=? AND id IN (" + safe + ")",
                new Object[]{ user });
    }
}
