package dev.otake.rmssdh10n;

import org.junit.Test;
import java.lang.reflect.Field;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import static org.junit.Assert.*;

/** Explicit-state model check of the Android BLE link lifecycle. A breadth-first search runs
 *  every interleaving, up to a depth bound, of SDK callbacks (main thread), control-lane tasks,
 *  timers, recording-lane jobs, selection scans, native failures and stop against the real
 *  BleConnectionRetry and BleStageReporter, wired as PolarBle wires them (markLinkDown and the
 *  link generation, the recording-stage gate, rebond hold/reconnect, clean reconnect, adapter
 *  restart). A state is rebuilt by replaying its path and deduplicated by an abstract key
 *  (supervisor and reporter fields read by reflection, session, queues, relative times).
 *
 *  Every state must satisfy the safety properties (see {@link Sys#check}); from every reachable
 *  state a cooperative environment must lead back to a ready link (EF ready) — automatically,
 *  or through a Bluetooth toggle when the supervisor halted and asks for user action.
 *
 *  The SDK model follows Polar BLE SDK 6.16.1 (BDBleApiImpl): connectToDevice acts only on a
 *  closed session; disconnectFromDevice closes OPEN/OPENING/OPEN_PARK and reports it;
 *  Bluetooth power-off parks a session. PolarBle itself needs Android, so its glue is mirrored
 *  here; keep {@link Sys} in step with PolarBle when that glue changes. */
public class BleLinkModelCheckTest {
    enum Act { SDK_CONNECTS, LINK_DROPS, ADAPTER, MAIN, EXEC, TIMER, REC_READY, REC_RETRY, REC_RECONNECT,
               REC_REBOND, BOND_DONE, WATCHDOG, SCAN_BEGIN, SCAN_END, CANCEL_FAILS, CONNECT_FAILS, STOP }
    enum Session { CLOSED, OPENING, OPEN, PARK }
    // Main-thread SDK callbacks and control-lane (exec) tasks.
    static final int CB_CONNECTED = 0, CB_FEATURE = 1, CB_DISCONNECTED = 2, CB_BT_ON = 3;
    static final int T_CONNECTED = 0, T_DISCONNECTED = 1, T_REC_WAIT = 2, T_BT_ON = 3, T_CLEAN = 4,
                     T_HOLD = 5, T_REBOND_RECONNECT = 6;
    static final Act[] ACTS = Act.values();
    private static final Set<String> LINK_UP = new HashSet<>(Arrays.asList(
            "connected/link_ready", "recording_wait/streams_settling", "streaming/recording_ready"));
    private static final int DEPTH = Integer.getInteger("ble.mc.depth", 11);
    private static final int MAX_QUEUE = 3;

    @Test public void everyInterleavingIsSafeAndCanRecover() {
        Result r = explore(DEPTH);
        assertNull(r.counterexample, r.counterexample);
        assertTrue("explored too little to mean anything: " + r.states, r.states > 500_000);
        System.out.println("BLE model check: depth " + DEPTH + ", " + r.states + " states, "
                + r.transitions + " transitions, " + r.recoveryChecks + " recovery checks");
    }

    static final class Result { String counterexample; long states, transitions, recoveryChecks; }

    static Result explore(int depth) {
        Result result = new Result();
        Set<String> seen = new HashSet<>();
        List<byte[]> frontier = new ArrayList<>();
        frontier.add(new byte[0]);
        seen.add(replay(new byte[0]).key());
        for (int level = 0; level <= depth && !frontier.isEmpty(); level++) {
            List<byte[]> next = new ArrayList<>();
            for (byte[] path : frontier) {
                Sys s = replay(path);
                result.states++;
                String bad = s.violation();
                if (bad == null) bad = recover(path);
                result.recoveryChecks++;
                if (bad != null) { result.counterexample = describe(path, bad); return result; }
                if (level == depth) continue;
                for (Act a : ACTS) {
                    if (!s.enabled(a)) continue;
                    byte[] child = Arrays.copyOf(path, path.length + 1);
                    child[path.length] = (byte) a.ordinal();
                    Sys c = replay(child);
                    result.transitions++;
                    if (c.violation() != null) { result.counterexample = describe(child, c.violation()); return result; }
                    if (seen.add(c.key())) next.add(child);
                }
            }
            frontier = next;
        }
        return result;
    }

    static Sys replay(byte[] path) {
        Sys s = new Sys();
        for (byte b : path) s.step(ACTS[b]);
        return s;
    }

    static String describe(byte[] path, String why) {
        StringBuilder sb = new StringBuilder(why).append("\n  path:");
        for (byte b : path) sb.append(' ').append(ACTS[b]);
        return sb.toString();
    }

    /** EF ready: a cooperative environment (native calls succeed, callbacks and timers are
     *  delivered, the H10 accepts the connection) reaches a ready link. Only a halted
     *  supervisor may need the user's Bluetooth toggle it asks for. */
    static String recover(byte[] path) {
        Sys s = replay(path);
        if (s.stopping) return null;
        for (int i = 0; i < 80; i++) {
            if (s.ready()) return null;
            Act a = s.cooperativeStep();
            if (a == null) return "stuck: no cooperative step reaches a ready link (shown " + s.shown + ")";
            s.step(a);
            String bad = s.violation();
            if (bad != null) return "during recovery after " + a + ": " + bad;
        }
        return "no ready link within 80 cooperative steps (shown " + s.shown + ")";
    }

    /** The H10 link as PolarBle drives it, with the real supervisor and stage reporter. */
    static final class Sys implements BleConnectionRetry.Link {
        private static final class Timer { final long at; final Runnable action; boolean done;
            Timer(long at, Runnable action) { this.at = at; this.action = action; } }
        long now;
        Session session = Session.CLOSED;
        boolean linkFlag, handled, stopping, cancelFails, connectFails, scanActive, rebonding, stopPublished;
        int connGen;
        Integer recordingGen;   // set while the recording lane publishes through reportIf
        String shown; long shownNextAt;
        final ArrayDeque<Integer> main = new ArrayDeque<>(), rec = new ArrayDeque<>();
        final ArrayDeque<int[]> exec = new ArrayDeque<>();
        final List<Timer> timers = new ArrayList<>();
        final List<String> violations = new ArrayList<>();
        final BleStageReporter stages = new BleStageReporter(this::published);
        final BleConnectionRetry retry = new BleConnectionRetry((delay, action) -> {
            Timer t = new Timer(now + delay, action); timers.add(t);
            return () -> t.done = true;
        }, this, () -> now, () -> 0.37);

        Sys() { retry.request("initial"); }   // PolarBle.start()

        // --- BleConnectionRetry.Link as PolarBle implements it -------------------------
        public boolean connected() { return linkFlag; }
        public void connect() {
            if (stopping) { violations.add("native connect after stop"); return; }
            if (connectFails) throw new IllegalStateException("connect failed");
            if (session != Session.CLOSED) violations.add("connect ignored by the SDK: session " + session);
            else session = Session.OPENING;
        }
        public void cancel() {
            if (stopping) { violations.add("native cancel after stop"); return; }
            if (cancelFails) throw new IllegalStateException("cancel failed");
            if (session != Session.CLOSED) { session = Session.CLOSED; post(CB_DISCONNECTED); }
        }
        public void report(String stage, String reason, int attempt, long nextAt) {
            stages.report(stage, reason, attempt, nextAt);
        }

        private void published(String stage, String reason, int attempt, long nextAt) {
            if (stopPublished) violations.add("published after stop: " + stage + "/" + reason);
            if (recordingGen != null && (stopping || !linkFlag || recordingGen != connGen))
                violations.add("recording stage of a superseded link: " + stage + "/" + reason);
            shown = stage + "/" + reason; shownNextAt = nextAt;
            if (stage.equals("stopped")) stopPublished = true;
        }

        // --- PolarBle glue -------------------------------------------------------------
        private void post(int cb) { main.add(cb); }
        private void execSafe(int task, int gen) { if (!stopping) exec.add(new int[]{task, gen}); }
        private void markLinkDown() { linkFlag = false; connGen++; handled = false; }
        private boolean currentRecordingJob(int gen) { return !stopping && linkFlag && gen == connGen; }
        private void recordingStage(int gen, String stage, String reason) {
            recordingGen = gen;
            try { stages.reportIf(() -> currentRecordingJob(gen), stage, reason, 0, 0); }
            finally { recordingGen = null; }
        }

        boolean enabled(Act a) {
            switch (a) {
                case SDK_CONNECTS: return (session == Session.OPENING || session == Session.PARK) && main.size() < MAX_QUEUE;
                case LINK_DROPS: return session == Session.OPEN && main.size() < MAX_QUEUE;
                case ADAPTER: return main.size() < MAX_QUEUE;
                case MAIN: return !main.isEmpty();
                case EXEC: return !exec.isEmpty();
                case TIMER: return armed() != null;
                case REC_READY: case REC_RETRY: case REC_RECONNECT: return !rec.isEmpty() && !rebonding;
                case REC_REBOND: return !rec.isEmpty() && !rebonding;
                case BOND_DONE: return rebonding;
                case WATCHDOG: return !stopping && exec.size() < MAX_QUEUE;
                case SCAN_BEGIN: return !stopping && !scanActive;
                case SCAN_END: return scanActive;
                case CANCEL_FAILS: case CONNECT_FAILS: return true;
                case STOP: return !stopping;
                default: throw new AssertionError(a);
            }
        }

        void step(Act a) {
            switch (a) {
                case SDK_CONNECTS: session = Session.OPEN; post(CB_CONNECTED); post(CB_FEATURE); break;
                case LINK_DROPS: session = Session.PARK; post(CB_DISCONNECTED); break;
                case ADAPTER:
                    if (session != Session.CLOSED) { session = Session.PARK; post(CB_DISCONNECTED); }
                    post(CB_BT_ON); break;
                case MAIN: mainThread(main.poll()); break;
                case EXEC: { int[] t = exec.poll(); controlLane(t[0], t[1]); break; }
                case TIMER: { Timer t = armed(); t.done = true; now = t.at; t.action.run(); break; }
                case REC_READY: { int gen = rec.poll(); if (currentRecordingJob(gen)) recordingStage(gen, "streaming", "recording_ready"); break; }
                case REC_RETRY: {
                    int gen = rec.poll();
                    if (currentRecordingJob(gen)) { recordingStage(gen, "retry_wait", "pftp_retry"); if (!stopping) rec.add(gen); }
                    break;
                }
                case REC_RECONNECT: { int gen = rec.poll(); if (currentRecordingJob(gen)) execSafe(T_CLEAN, 0); break; }
                case REC_REBOND: { // holdLinkForRebond on exec, then PolarBonding.rebond blocks this lane
                    int gen = rec.poll();
                    if (currentRecordingJob(gen)) { execSafe(T_HOLD, 0); rebonding = true; }
                    break;
                }
                case BOND_DONE: rebonding = false; execSafe(T_REBOND_RECONNECT, 0); break;
                case WATCHDOG: execSafe(T_CLEAN, 0); break;   // forceReconnect with stale RR
                case SCAN_BEGIN: scanActive = true; stages.beginOverlay("scanning", "device_selection", 0, 0); break;
                case SCAN_END: stages.endOverlay(); scanActive = false; break;
                case CANCEL_FAILS: cancelFails = !cancelFails; break;
                case CONNECT_FAILS: connectFails = !connectFails; break;
                case STOP:
                    stopping = true; retry.stop(); stages.stop("user_or_service_stop");
                    if (scanActive) { stages.endOverlay(); scanActive = false; }   // dispose → doFinally
                    break;
            }
        }

        private void mainThread(int cb) {
            if (cb == CB_BT_ON) { execSafe(T_BT_ON, 0); return; }   // MonitorService → reconnectIfDown
            if (stopping) return;
            if (cb == CB_CONNECTED) { connGen++; handled = false; linkFlag = true; execSafe(T_CONNECTED, 0); }
            else if (cb == CB_FEATURE) {
                if (!handled) { handled = true; execSafe(T_REC_WAIT, connGen); if (!stopping) rec.add(connGen); }
            } else { markLinkDown(); execSafe(T_DISCONNECTED, 0); }
        }

        private void controlLane(int task, int gen) {
            switch (task) {
                case T_CONNECTED: retry.connected(); break;
                case T_DISCONNECTED: retry.disconnected(); break;
                case T_REC_WAIT: recordingStage(gen, "recording_wait", "streams_settling"); break;
                case T_BT_ON: if (!stopping && !linkFlag) retry.adapterRestarted(); break;
                case T_CLEAN: if (!stopping) { markLinkDown(); retry.restart("stall", 3_000); } break;
                case T_HOLD: if (!stopping) { markLinkDown(); retry.restart("rebond", 45_000); } break;
                case T_REBOND_RECONNECT: retry.reconnect("rebond"); break;
            }
        }

        private Timer armed() {
            Timer first = null;
            for (Timer t : timers) if (!t.done && (first == null || t.at < first.at)) first = t;
            return first;
        }
        private int armedCount() { int n = 0; for (Timer t : timers) if (!t.done) n++; return n; }
        boolean quiescent() { return main.isEmpty() && exec.isEmpty() && rec.isEmpty() && !scanActive && !rebonding; }

        boolean ready() { return quiescent() && linkFlag && field(UP, retry) && LINK_UP.contains(shown); }

        Act cooperativeStep() {
            if (cancelFails) return Act.CANCEL_FAILS;
            if (connectFails) return Act.CONNECT_FAILS;
            if (scanActive) return Act.SCAN_END;
            if (!main.isEmpty()) return Act.MAIN;
            if (!exec.isEmpty()) return Act.EXEC;
            if (rebonding) return Act.BOND_DONE;
            if (!rec.isEmpty()) return Act.REC_READY;
            if (session == Session.OPENING || session == Session.PARK) return Act.SDK_CONNECTS;
            if (armed() != null) return Act.TIMER;
            if (field(HALTED, retry)) return Act.ADAPTER;   // the user acts on needs_action
            return null;
        }

        /** Safety: S1 every native connect is one the SDK acts on; S2 at most one supervisor
         *  timer; S3 nothing is published or sent natively after stop; S4 a recording-lane
         *  stage is published only for the live link; S5 at quiescence the shown stage tells
         *  the truth about the link, and a down link is owned by a timer or an explicit halt;
         *  S6 a shown countdown matches the timer that ends it. */
        String violation() {
            if (!violations.isEmpty()) return violations.get(0);
            int timersArmed = armedCount();
            if (timersArmed > 1) return "more than one supervisor timer armed";
            if (stopping && timersArmed > 0) return "timer armed after stop";
            if (!quiescent()) return null;
            if (stopping) return "stopped/user_or_service_stop".equals(shown) ? null : "stop not shown: " + shown;
            boolean up = field(UP, retry), halted = field(HALTED, retry);
            if (linkFlag) {
                if (!up) return "link up but supervisor not up";
                if (!LINK_UP.contains(shown)) return "link up but shown " + shown;
                return timersArmed == 0 ? null : "deadline left armed on an up link";
            }
            if (up) return "supervisor up while the link is down";
            if (LINK_UP.contains(shown)) return "shows " + shown + " while the link is down";
            if (halted) return "needs_action/connect_cancel_failed".equals(shown) ? null : "halted but shown " + shown;
            if (timersArmed != 1) return "nobody owns the next step while disconnected (shown " + shown + ")";
            long due = armed().at;
            if (shown.startsWith("retry_wait/") && shownNextAt != due) return "countdown " + shownNextAt + " is not the retry at " + due;
            if (shown.startsWith("connecting/") && due > shownNextAt) return "countdown ends before the timer fires";
            return null;
        }

        String key() {
            StringBuilder k = new StringBuilder();
            k.append(session).append(linkFlag ? 'L' : 'l').append(handled ? 'H' : 'h').append(stopping ? 'S' : 's')
             .append(cancelFails ? 'C' : 'c').append(connectFails ? 'F' : 'f').append(scanActive ? 'N' : 'n')
             .append(rebonding ? 'R' : 'r').append(stopPublished ? 'P' : 'p');
            k.append("|r").append(field(UP, retry) ? 1 : 0).append(field(WAITING, retry) ? 1 : 0)
             .append(field(HALTED, retry) ? 1 : 0).append(field(STOPPED, retry) ? 1 : 0).append(',').append(intField(ATTEMPT, retry));
            Timer t = armed();
            k.append("|t").append(t == null ? "-" : String.valueOf(t.at - now)).append('/').append(armedCount());
            k.append("|m").append(main).append("|e");
            for (int[] task : exec) k.append(task[0]).append(task[0] == T_REC_WAIT ? ":" + (connGen - task[1]) : "").append(',');
            k.append("|q");
            for (int gen : rec) k.append(connGen - gen).append(',');
            k.append("|s").append(shown).append('@').append(shownNextAt == 0 ? 0 : shownNextAt - now);
            Object beneath = get(BENEATH, stages), current = get(CURRENT, stages);
            k.append("|b").append(beneath == null ? "-" : get(STAGE, beneath) + "/" + get(REASON, beneath));
            k.append(current != null && (Boolean) get(OVERLAY, current) ? "|o" : "|-");
            return k.toString();
        }
    }

    // --- reflection on the real classes' state ------------------------------------------
    private static final Field UP = field(BleConnectionRetry.class, "up"), WAITING = field(BleConnectionRetry.class, "waiting"),
            HALTED = field(BleConnectionRetry.class, "halted"), STOPPED = field(BleConnectionRetry.class, "stopped"),
            ATTEMPT = field(BleConnectionRetry.class, "attempt"),
            CURRENT = field(BleStageReporter.class, "current"), BENEATH = field(BleStageReporter.class, "beneath");
    private static final Class<?> STAGE_CLASS = nested(BleStageReporter.class, "Stage");
    private static final Field STAGE = field(STAGE_CLASS, "stage"), REASON = field(STAGE_CLASS, "reason"),
            OVERLAY = field(STAGE_CLASS, "overlay");

    private static Class<?> nested(Class<?> c, String name) {
        for (Class<?> n : c.getDeclaredClasses()) if (n.getSimpleName().equals(name)) return n;
        throw new AssertionError("model check needs " + c.getSimpleName() + "." + name);
    }
    private static Field field(Class<?> c, String name) {
        try { Field f = c.getDeclaredField(name); f.setAccessible(true); return f; }
        catch (NoSuchFieldException e) { throw new AssertionError("model check needs " + c.getSimpleName() + "." + name, e); }
    }
    private static Object get(Field f, Object o) {
        try { return f.get(o); } catch (IllegalAccessException e) { throw new AssertionError(e); }
    }
    static boolean field(Field f, Object o) { return (Boolean) get(f, o); }
    static int intField(Field f, Object o) { return (Integer) get(f, o); }
}
