package dev.otake.rmssdh10n;

import android.app.AlarmManager;
import android.app.PendingIntent;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.os.PowerManager;
import android.os.SystemClock;

import androidx.core.content.ContextCompat;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * A delay that elapses in wall time even while the CPU sleeps (#32). A
 * {@link ScheduledExecutorService} measures delays on the monotonic clock, which stops in
 * suspend, so once the wake lock is released in a backoff a retry armed on it alone could wait
 * for hours. Each delay arms both that executor task (on time whenever the CPU is awake anyway)
 * and an inexact allow-while-idle alarm that wakes the CPU; whichever comes first runs the
 * action on the executor, once. A short wake lock keeps the CPU up from the alarm until the
 * action has reached the executor.
 */
final class WakeupTimer {
    private static final String ACTION = "dev.otake.rmssdh10n.WAKEUP";
    private static final String EXTRA_ID = "id";
    private static final long HOLD_MS = 10_000;
    private final Context ctx;
    private final ScheduledExecutorService exec;
    private final AlarmManager alarms;
    private final PowerManager.WakeLock hold;
    private final Map<Integer, Pending> pending = new ConcurrentHashMap<>();
    private final AtomicInteger ids = new AtomicInteger();
    private volatile boolean closed;

    private static final class Pending {
        final Runnable fire; final PendingIntent alarm;
        Pending(Runnable fire, PendingIntent alarm) { this.fire = fire; this.alarm = alarm; }
    }

    private final BroadcastReceiver receiver = new BroadcastReceiver() {
        @Override public void onReceive(Context c, Intent i) {
            Pending p = pending.get(i.getIntExtra(EXTRA_ID, -1));
            if (p == null || closed) return;
            if (hold != null) hold.acquire(HOLD_MS);
            try { exec.execute(p.fire); } catch (RejectedExecutionException ignored) {}
        }
    };

    WakeupTimer(Context ctx, ScheduledExecutorService exec) {
        this.ctx = ctx;
        this.exec = exec;
        alarms = ctx.getSystemService(AlarmManager.class);
        PowerManager pm = ctx.getSystemService(PowerManager.class);
        hold = pm != null ? pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "rmssd:wakeup") : null;
        if (hold != null) hold.setReferenceCounted(false);
        // The alarm's broadcast comes from this app's own PendingIntent, which reaches a
        // NOT_EXPORTED receiver.
        ContextCompat.registerReceiver(ctx, receiver, new IntentFilter(ACTION),
                ContextCompat.RECEIVER_NOT_EXPORTED);
    }

    /** Runs {@code action} on the executor after {@code delayMs} of wall time; the returned
     *  runnable cancels it. */
    Runnable after(long delayMs, Runnable action) {
        int id = ids.incrementAndGet();
        PendingIntent alarm = PendingIntent.getBroadcast(ctx, id,
                new Intent(ACTION).setPackage(ctx.getPackageName()).putExtra(EXTRA_ID, id),
                PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT);
        AtomicBoolean done = new AtomicBoolean();
        Runnable cancel = () -> {
            done.set(true);
            pending.remove(id);
            release(alarm);
        };
        Runnable fire = () -> {
            if (done.get()) return;
            cancel.run();
            action.run();
        };
        ScheduledFuture<?> task = exec.schedule(fire, delayMs, TimeUnit.MILLISECONDS);
        pending.put(id, new Pending(fire, alarm));
        try {
            if (alarms != null && !closed) alarms.setAndAllowWhileIdle(AlarmManager.ELAPSED_REALTIME_WAKEUP,
                    SystemClock.elapsedRealtime() + delayMs, alarm);
        } catch (Throwable ignored) {}   // the executor task still fires while the CPU is awake
        return () -> { task.cancel(false); cancel.run(); };
    }

    /** Cancels every pending alarm and stops listening. */
    void close() {
        closed = true;
        for (Pending p : pending.values()) release(p.alarm);
        pending.clear();
        try { ctx.unregisterReceiver(receiver); } catch (Exception ignored) {}
    }

    /** Drops the alarm and its PendingIntent, so an always-on monitor that backs off for days
     *  leaves no intent records behind in the system. */
    private void release(PendingIntent alarm) {
        try { if (alarms != null) alarms.cancel(alarm); } catch (Throwable ignored) {}
        alarm.cancel();
    }
}
