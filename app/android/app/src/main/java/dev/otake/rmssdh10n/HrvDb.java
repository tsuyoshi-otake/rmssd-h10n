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
 *
 * Writes are batched in a transaction (flush every few seconds or N points) to
 * keep the background loop cheap.
 */
public final class HrvDb extends SQLiteOpenHelper {
    private static final String NAME = "hrv.db";
    private static final int VERSION = 1;

    public HrvDb(Context ctx) { super(ctx.getApplicationContext(), NAME, null, VERSION); }

    @Override public void onCreate(SQLiteDatabase db) {
        db.execSQL("CREATE TABLE points(t_ms INTEGER PRIMARY KEY, json TEXT NOT NULL)");
        db.execSQL("CREATE TABLE status_latest(id INTEGER PRIMARY KEY CHECK(id=1), json TEXT, t_ms INTEGER)");
        db.execSQL("CREATE TABLE kv(k TEXT PRIMARY KEY, v TEXT)");
    }

    @Override public void onUpgrade(SQLiteDatabase db, int oldV, int newV) {
        db.execSQL("DROP TABLE IF EXISTS points");
        db.execSQL("DROP TABLE IF EXISTS status_latest");
        db.execSQL("DROP TABLE IF EXISTS kv");
        onCreate(db);
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

    /** Drop points older than the cutoff (keeps the DB bounded ~14 days). */
    public void prune(long cutoffMs) {
        getWritableDatabase().delete("points", "t_ms<?", new String[]{ String.valueOf(cutoffMs) });
    }
}
