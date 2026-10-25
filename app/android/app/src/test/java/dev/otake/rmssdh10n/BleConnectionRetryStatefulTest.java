package dev.otake.rmssdh10n;

import org.junit.Test;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Deque;
import java.util.List;
import java.util.Random;
import static org.junit.Assert.*;

/** Stateful PBT for BleConnectionRetry. Random interleavings of control-lane calls, SDK
 *  callbacks (posted to the main thread, then forwarded to exec), timers and native failures
 *  run against the real supervisor and an independent reference model of its contract; every
 *  step also checks environment invariants (every native connect is one the SDK acts on, one
 *  timer, a truthful stage at quiescence, someone owning the next step while disconnected, and
 *  the link standing by exactly through a backoff on a timer that wakes the CPU, #31/#32).
 *
 *  The session model follows Polar BLE SDK 6.16.1 (BDBleApiImpl bytecode): connectToDevice
 *  acts only on a closed session and is silently ignored otherwise; disconnectFromDevice
 *  closes an OPEN/OPENING/OPEN_PARK session and the SDK reports deviceDisconnected for it;
 *  Bluetooth power-off parks the session (automatic reconnection on). */
public class BleConnectionRetryStatefulTest {
    enum Op { REQUEST, RECONNECT, RESTART, ADAPTER, SDK_CONNECTS, LINK_DROPS, MAIN, EXEC, ADVANCE,
              SIGHTED, CANCEL_FAILS, CONNECT_FAILS, STOP }
    enum Session { CLOSED, OPENING, OPEN, PARK }
    enum Cb { CONNECTED, DISCONNECTED, BT_ON }

    static final class Cmd {
        final Op op; final long arg;
        Cmd(Op op, long arg) { this.op = op; this.arg = arg; }
        @Override public String toString() { return arg < 0 ? op.name() : op + "(" + arg + ")"; }
    }

    private static final long[] ADVANCES = {1, 999, 2_999, 3_000, 29_999, 30_000, 45_000, 59_999, 60_000, 300_000};
    private static final long[] SETTLES = {0, 3_000, 45_000};
    private static final double JITTER = 0.37;

    static Cmd gen(Random r) {
        int x = r.nextInt(100);
        if (x < 22) return new Cmd(Op.ADVANCE, ADVANCES[r.nextInt(ADVANCES.length)]);
        if (x < 35) return new Cmd(Op.MAIN, -1);
        if (x < 48) return new Cmd(Op.EXEC, -1);
        if (x < 56) return new Cmd(Op.SDK_CONNECTS, -1);
        if (x < 62) return new Cmd(Op.LINK_DROPS, -1);
        if (x < 68) return new Cmd(Op.RESTART, SETTLES[r.nextInt(SETTLES.length)]);
        if (x < 73) return new Cmd(Op.RECONNECT, -1);
        if (x < 77) return new Cmd(Op.ADAPTER, -1);
        if (x < 82) return new Cmd(Op.REQUEST, -1);
        if (x < 88) return new Cmd(Op.SIGHTED, -1);   // late or stray reports included
        if (x < 92) return new Cmd(Op.CANCEL_FAILS, r.nextInt(2));
        if (x < 97) return new Cmd(Op.CONNECT_FAILS, r.nextInt(2));
        return new Cmd(Op.STOP, -1);
    }

    @Test public void supervisorMatchesItsModelUnderRandomInterleavings() {
        StatefulPbt.check("BleConnectionRetry", 0x5EEDB1E5L, 3000, 60,
                BleConnectionRetryStatefulTest::gen, BleConnectionRetryStatefulTest::run);
    }

    static void run(List<Cmd> commands) {
        World world = new World();
        Spec spec = new Spec(world);
        for (int i = 0; i < commands.size(); i++) {
            Cmd cmd = commands.get(i);
            world.apply(cmd, spec);
            String at = "after #" + i + " " + cmd;
            assertEquals(at + ": effects differ from the model", spec.effects, world.effects);
            assertEquals(at + ": environment invariant", Collections.emptyList(), world.violations);
            world.checkQuiescentState(at);
        }
    }

    /** The environment PolarBle gives the supervisor: an SDK session, the main-thread
     *  callback queue, the exec lane, and PolarBle's linkConnected flag. */
    static final class World implements BleConnectionRetry.Link {
        private static final class Timer { final long at; final boolean waking; final Runnable action; boolean done;
            Timer(long at, boolean waking, Runnable action) { this.at = at; this.waking = waking; this.action = action; } }
        long now;
        Session session = Session.CLOSED;
        boolean linkFlag, cancelFails, connectFails, standing;
        final Deque<Cb> main = new ArrayDeque<>(), exec = new ArrayDeque<>();
        final List<Timer> timers = new ArrayList<>();
        final List<String> effects = new ArrayList<>(), violations = new ArrayList<>();
        String lastStage, lastReason; long lastNextAt; boolean stopped;
        final BleConnectionRetry retry = new BleConnectionRetry((delay, waking, action) -> {
            Timer t = new Timer(now + delay, waking, action); timers.add(t);
            return () -> t.done = true;
        }, this, () -> now, () -> JITTER);

        public boolean connected() { return linkFlag; }
        public void connect() {
            effects.add("connect");
            if (standing) violations.add("connect while standing by");
            if (connectFails) throw new IllegalStateException("connect failed");
            if (session != Session.CLOSED) violations.add("connect ignored by the SDK: session " + session);
            else session = Session.OPENING;
        }
        public void cancel() {
            effects.add("cancel");
            if (standing) violations.add("cancel while standing by");
            if (cancelFails) throw new IllegalStateException("cancel failed");
            if (session != Session.CLOSED) main.add(Cb.DISCONNECTED);   // the SDK echoes every closed session
            session = Session.CLOSED;
        }
        public void report(String stage, String reason, int attempt, long nextAt) {
            effects.add(stage + "/" + reason + "#" + attempt + "@" + nextAt);
            if (standing && !stage.equals("retry_wait")) violations.add("the next owner's stage while standing by: " + stage);
            lastStage = stage; lastReason = reason; lastNextAt = nextAt;
        }
        public void standby(boolean watch) {
            effects.add(watch ? "standby+watch" : "standby");
            if (standing) violations.add("standby twice");
            if (session != Session.CLOSED) violations.add("standby with the session " + session);
            standing = true;
        }
        public void resume() {
            effects.add("resume");
            if (!standing) violations.add("resume without standby");
            standing = false;
        }

        void apply(Cmd c, Spec spec) {
            switch (c.op) {
                case REQUEST: retry.request("initial"); spec.request("initial"); break;
                case RECONNECT: retry.reconnect("rebond"); spec.reconnect("rebond"); break;
                case RESTART: // PolarBle.cleanReconnect: markLinkDown() first, then restart
                    linkFlag = false; retry.restart("stall", c.arg); spec.restart("stall", c.arg); break;
                case ADAPTER: // power-off parks the session; it may or may not come back by itself
                    if (session != Session.CLOSED) { session = Session.PARK; main.add(Cb.DISCONNECTED); }
                    main.add(Cb.BT_ON); break;
                case SDK_CONNECTS:
                    if (session == Session.OPENING || session == Session.PARK) { session = Session.OPEN; main.add(Cb.CONNECTED); }
                    break;
                case LINK_DROPS:
                    if (session == Session.OPEN) { session = Session.PARK; main.add(Cb.DISCONNECTED); }
                    break;
                case MAIN: {
                    Cb cb = main.poll();
                    if (cb == Cb.CONNECTED) { linkFlag = true; exec.add(cb); }
                    else if (cb == Cb.DISCONNECTED) { linkFlag = false; exec.add(cb); }
                    else if (cb == Cb.BT_ON) exec.add(cb);
                    break;
                }
                case EXEC: {
                    Cb task = exec.poll();
                    if (task == Cb.CONNECTED) { retry.connected(); spec.connected(); }
                    else if (task == Cb.DISCONNECTED) { retry.disconnected(); spec.disconnected(); }
                    else if (task == Cb.BT_ON && !linkFlag) { retry.adapterRestarted(); spec.adapterRestarted(); }
                    break;
                }
                case ADVANCE: advance(c.arg); spec.advance(c.arg); break;
                case SIGHTED: retry.sighted(); spec.sighted(); break;
                case CANCEL_FAILS: cancelFails = c.arg == 1; break;
                case CONNECT_FAILS: connectFails = c.arg == 1; break;
                case STOP: stopped = true; retry.stop(); spec.stop(); break;
            }
        }

        private void advance(long duration) {
            long end = now + duration;
            for (;;) {
                Timer first = null;
                for (Timer t : timers) if (!t.done && t.at <= end && (first == null || t.at < first.at)) first = t;
                if (first == null) break;
                first.done = true; now = first.at; first.action.run();
            }
            now = end;
        }

        void checkQuiescentState(String at) {
            Timer armed = null; int count = 0;
            for (Timer t : timers) if (!t.done) { armed = t; count++; }
            assertTrue(at + ": more than one supervisor timer armed", count <= 1);
            if (stopped) { assertEquals(at + ": timer armed after stop", 0, count); return; }
            assertEquals(at + ": stands by exactly through a backoff", "retry_wait".equals(lastStage), standing);
            if (armed != null) assertEquals(at + ": only the standby's timer wakes the CPU", standing, armed.waking);
            if (lastStage == null || !main.isEmpty() || !exec.isEmpty()) return;
            String shown = lastStage + "/" + lastReason;
            if (linkFlag) {
                assertEquals(at + ": an up link must show link_ready", "connected/link_ready", shown);
                assertEquals(at + ": deadline left on an up link", 0, count);
                return;
            }
            assertNotEquals(at + ": shows connected while the link is down", "connected/link_ready", shown);
            boolean halted = "needs_action/connect_cancel_failed".equals(shown);
            assertTrue(at + ": nobody owns the next step while disconnected", halted || count == 1);
            if (armed == null) return;
            if (lastStage.equals("retry_wait")) assertEquals(at + ": countdown is not the timer", lastNextAt, armed.at);
            else assertTrue(at + ": countdown ends before the timer fires", armed.at <= lastNextAt);
        }
    }

    /** Reference model of the supervisor contract, written from its documentation (phases
     *  instead of flags) and kept independent of the implementation's structure. */
    static final class Spec {
        enum Phase { IDLE, CONNECTING, BACKOFF, SETTLE, SDK_WAIT, UP, HALTED, STOPPED }
        private static final long DEADLINE = 60_000;
        private static final long[] BACKOFF_BASE = {30_000, 60_000, 120_000, 240_000};
        private final World env;
        final List<String> effects = new ArrayList<>();
        private Phase phase = Phase.IDLE;
        private int attempt;
        private boolean sightedAttempt;   // the current or last attempt was started by a sighting
        private long now, due = -1;
        private String settleReason, last;

        Spec(World env) { this.env = env; }

        void request(String reason) { request(reason, false); }
        /** A link already up is owned at once rather than left to a queued callback. */
        private void request(String reason, boolean bySighting) {
            if (phase != Phase.IDLE) return;
            if (env.linkFlag) { connected(); return; }
            sightedAttempt = bySighting;
            attempt = Math.min(20, attempt + 1);
            phase = Phase.CONNECTING; due = now + DEADLINE;
            report("connecting", reason, attempt, due);
            effects.add("connect");
            if (env.connectFails) fail("connect_error");
        }
        /** Connect afresh: whatever may be pending is cancelled first. A halt is left alone. */
        void reconnect(String reason) {
            if (phase == Phase.STOPPED || phase == Phase.UP || phase == Phase.HALTED || env.linkFlag) return;
            cancelThenRequest(reason);
        }
        /** Connect afresh, ending a halt unless the cancel fails again. */
        void adapterRestarted() {
            if (phase == Phase.STOPPED || phase == Phase.UP || env.linkFlag) return;
            cancelThenRequest("bt_on");
        }
        private void cancelThenRequest(String reason) {
            due = -1;
            leave(); phase = Phase.IDLE;
            effects.add("cancel");
            if (env.cancelFails) { halt(); return; }
            phase = Phase.IDLE;
            request(reason);
        }
        /** Stale once the link is no longer up: whoever owns the next step keeps it, and its
         *  stage is shown again unless an up link is about to report its own loss. */
        void connected() {
            if (phase == Phase.STOPPED) return;
            if (!env.linkFlag) {
                if (phase != Phase.UP && last != null) effects.add(last);
                return;
            }
            boolean wasUp = phase == Phase.UP;
            leave();
            phase = Phase.UP; attempt = 0; due = -1;
            if (!wasUp) report("connected", "link_ready", 0, 0);
        }
        void disconnected() {
            if (phase != Phase.UP) return;
            sightedAttempt = false;
            report("disconnected", "link_lost", attempt, 0);
            phase = Phase.SDK_WAIT; due = now + DEADLINE;
            report("connecting", "sdk_reconnect", attempt, due);
        }
        void restart(String reason, long settle) {
            if (phase == Phase.STOPPED || phase == Phase.HALTED) return;
            due = -1;
            leave(); phase = Phase.SETTLE;
            report("connecting", reason, attempt, now + settle + DEADLINE);
            effects.add("cancel");
            if (env.cancelFails) { halt(); return; }
            phase = Phase.SETTLE; settleReason = reason; due = now + settle;
        }
        /** Seeing the device cuts a watching backoff short; any other sighting is ignored. */
        void sighted() {
            if (phase != Phase.BACKOFF || sightedAttempt) return;
            due = -1;
            leave(); phase = Phase.IDLE;
            request("sighted", true);
        }
        void stop() { phase = Phase.STOPPED; due = -1; }
        void advance(long duration) {
            long end = now + duration;
            while (due >= 0 && due <= end) {
                now = due; due = -1;
                Phase fired = phase;
                if (fired == Phase.CONNECTING || fired == Phase.SDK_WAIT) fail("connect_timeout");
                else if (fired == Phase.BACKOFF) { leave(); phase = Phase.IDLE; request("deadline_retry"); }
                else if (fired == Phase.SETTLE) { phase = Phase.IDLE; request(settleReason); }
            }
            now = end;
        }
        private void fail(String reason) {
            if (env.linkFlag) { connected(); return; }
            due = -1;
            effects.add("cancel");
            if (env.cancelFails) { halt(); return; }
            long base = BACKOFF_BASE[Math.min(3, Math.max(0, attempt - 1))];
            phase = Phase.BACKOFF; due = now + (long) (base * (1 + JITTER * 0.25));
            report("retry_wait", reason, attempt, due);
            effects.add(sightedAttempt ? "standby" : "standby+watch");   // one blind backoff after a failed sighting
        }
        /** The backoff's standby ends before whatever comes next. */
        private void leave() { if (phase == Phase.BACKOFF) effects.add("resume"); }
        private void halt() {
            phase = Phase.HALTED; due = -1;
            report("needs_action", "connect_cancel_failed", attempt, 0);
        }
        private void report(String stage, String reason, int n, long nextAt) {
            last = stage + "/" + reason + "#" + n + "@" + nextAt;
            effects.add(last);
        }
    }
}
