package dev.otake.rmssdh10n;

import org.junit.Test;
import java.util.ArrayList;
import java.util.List;
import static org.junit.Assert.*;

public class BleConnectionRetryTest {
    private static final class Event {
        long at; Runnable action; boolean cancelled;
        Event(long at, Runnable action) { this.at = at; this.action = action; }
    }
    private static final class Harness implements BleConnectionRetry.Link {
        long now; int connects, cancels; boolean up, throwsConnect, throwsCancel;
        String state, reason; long nextAt;
        final List<Event> events = new ArrayList<>();
        final BleConnectionRetry retry = new BleConnectionRetry((delay, action) -> {
            Event e = new Event(now + delay, action); events.add(e);
            return () -> e.cancelled = true;
        }, this, () -> now, () -> 0);
        public boolean connected() { return up; }
        public void connect() { connects++; if (throwsConnect) throw new IllegalStateException(); }
        public void cancel() { cancels++; if (throwsCancel) throw new IllegalStateException(); }
        public void report(String s, String r, int n, long next) { state=s; reason=r; nextAt=next; }
        void advance(long duration) {
            long end = now + duration;
            for (;;) {
                Event first = null;
                for (Event e : events) if (!e.cancelled && e.at <= end && (first == null || e.at < first.at)) first=e;
                if (first == null) break;
                first.cancelled=true; now=first.at; first.action.run();
            }
            now=end;
        }
        long pending() { return events.stream().filter(e -> !e.cancelled).count(); }
    }

    @Test public void acceptedButSilentRequestTimesOutAndRetriesAfterBackoff() {
        Harness h=new Harness(); h.retry.request("initial");
        h.advance(59_999); assertEquals(0,h.cancels);
        h.advance(1); assertEquals("connect_timeout",h.reason); assertEquals(1,h.cancels);
        assertEquals(90_000,h.nextAt); assertEquals(1,h.pending());
        h.advance(29_999); assertEquals(1,h.connects);
        h.advance(1); assertEquals(2,h.connects);
    }

    @Test public void repeatedWakeSignalsDoNotDuplicateAnInFlightRequestOrTimer() {
        Harness h=new Harness();
        for (int i=0;i<100;i++) { h.retry.request("wake"); h.retry.disconnected(); }
        assertEquals(1,h.connects); assertEquals(1,h.pending());
    }

    @Test public void lateConnectionCancelsRetryAndResetsFailureBudget() {
        Harness h=new Harness(); h.retry.request("initial"); h.advance(60_000);
        h.up=true; h.retry.connected(); h.advance(300_000);
        assertEquals(1,h.connects); assertEquals(0,h.pending());
        h.up=false; h.retry.disconnected(); h.advance(60_000);
        assertEquals(h.now+30_000,h.nextAt);
    }

    @Test public void sdkAutoReconnectGetsADeadlineWithoutDuplicateConnect() {
        Harness h=new Harness(); h.retry.disconnected();
        assertEquals(0,h.connects); h.advance(60_000); assertEquals(1,h.cancels);
        h.advance(30_000); assertEquals(1,h.connects);
    }

    @Test public void thrownConnectBacksOffAndNeverSpins() {
        Harness h=new Harness(); h.throwsConnect=true; h.retry.request("initial");
        assertEquals("connect_error",h.reason); h.advance(29_999); assertEquals(1,h.connects);
        h.advance(1); assertEquals(2,h.connects); assertEquals(h.now+60_000,h.nextAt);
        h.advance(2_000_000); assertTrue(h.connects<15); assertEquals(1,h.pending());
    }

    @Test public void stopInvalidatesEvenAnAlreadyDequeuedTimeout() {
        Harness h=new Harness(); h.retry.request("initial"); Runnable late=h.events.get(0).action;
        h.retry.stop(); late.run(); h.advance(600_000); h.retry.request("wake");
        assertEquals(1,h.connects); assertEquals(0,h.cancels); assertEquals(0,h.pending());
    }

    @Test public void failedCancellationStopsInsteadOfOverlappingNativeRequests() {
        Harness h=new Harness(); h.throwsCancel=true; h.retry.request("initial");
        h.advance(600_000); assertEquals("connect_cancel_failed",h.reason);
        assertEquals("needs_action",h.state); assertEquals(1,h.connects); assertEquals(0,h.pending());
    }

    @Test public void connectedFlagWinsAgainstATimeoutBeforeCallbackIsDrained() {
        Harness h=new Harness(); h.retry.request("initial"); h.up=true; h.advance(60_000);
        assertEquals(0,h.cancels); assertEquals(0,h.pending());
    }

    @Test public void intentionalReconnectReplacesPassiveSdkWait() {
        Harness h=new Harness(); h.retry.disconnected(); h.retry.reconnect("stall");
        assertEquals(1,h.connects); assertEquals(1,h.pending());
    }
}
