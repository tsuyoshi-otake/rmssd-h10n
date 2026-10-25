package dev.otake.rmssdh10n;

import org.junit.Test;
import java.util.ArrayList;
import java.util.List;
import static org.junit.Assert.*;

public class BleConnectionRetryTest {
    private static final class Event {
        final long at; final boolean waking; final Runnable action; boolean cancelled;
        Event(long at, boolean waking, Runnable action) { this.at = at; this.waking = waking; this.action = action; }
    }
    /** Every test also checks the standby contract: standby and resume alternate, and while
     *  the link stands by nothing reaches the SDK and only the backoff's own stage is shown. */
    private static final class Harness implements BleConnectionRetry.Link {
        long now; int connects, cancels, attempt; boolean up, throwsConnect, throwsCancel, lossyTimer, standing;
        double jitter; Runnable duringConnect, duringSchedule;
        String state, reason; long nextAt;
        final List<Event> events = new ArrayList<>();
        final List<String> reports = new ArrayList<>();
        final List<String> calls = new ArrayList<>();
        final BleConnectionRetry retry = new BleConnectionRetry((delay, waking, action) -> {
            Event e = new Event(now + delay, waking, action); events.add(e);
            if (duringSchedule != null) duringSchedule.run();
            return () -> { if (!lossyTimer) e.cancelled = true; };
        }, this, () -> now, () -> jitter);
        public boolean connected() { return up; }
        public void connect() {
            assertFalse("connect while standing by", standing);
            connects++; calls.add("connect");
            if (duringConnect != null) duringConnect.run();
            if (throwsConnect) throw new IllegalStateException();
        }
        public void cancel() {
            assertFalse("cancel while standing by", standing);
            cancels++; calls.add("cancel"); if (throwsCancel) throw new IllegalStateException();
        }
        public void report(String s, String r, int n, long next) {
            assertFalse("the next owner's stage while standing by: " + s, standing && !"retry_wait".equals(s));
            state=s; reason=r; attempt=n; nextAt=next; reports.add(s+"/"+r);
        }
        public void standby(boolean watch) {
            assertFalse("standby twice", standing);
            standing = true; calls.add(watch ? "standby+watch" : "standby");
        }
        public void resume() {
            assertTrue("resume without standby", standing);
            standing = false; calls.add("resume");
        }
        void establish() { up=true; retry.connected(); }
        void lose() { up=false; retry.disconnected(); }
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
        Event armed() { return events.stream().filter(e -> !e.cancelled).reduce((a, b) -> b).orElseThrow(); }
        String lastCall() { return calls.get(calls.size() - 1); }
        String lastReport() { return reports.get(reports.size() - 1); }
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
        Harness h=new Harness(); h.establish(); h.lose();
        assertEquals(List.of("connected/link_ready","disconnected/link_lost","connecting/sdk_reconnect"),h.reports);
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

    @Test public void failedCancellationHaltsInsteadOfOverlappingNativeRequests() {
        Harness h=new Harness(); h.throwsCancel=true; h.retry.request("initial");
        h.advance(600_000); assertEquals("connect_cancel_failed",h.reason);
        assertEquals("needs_action",h.state); assertEquals(1,h.connects); assertEquals(0,h.pending());
        h.retry.request("wake"); h.retry.reconnect("stall"); h.retry.restart("stall",3_000); h.advance(600_000);
        assertEquals(1,h.connects); assertEquals("needs_action",h.state);
    }

    @Test public void haltEndsWhenTheAdapterRestarts() {
        Harness h=new Harness(); h.throwsCancel=true; h.retry.request("initial"); h.advance(60_000);
        h.throwsCancel=false; h.retry.adapterRestarted();
        assertEquals(2,h.connects); assertEquals("connecting/bt_on",h.reports.get(h.reports.size()-1));
    }

    @Test public void haltEndsWhenTheSdkConnectsAnyway() {
        Harness h=new Harness(); h.throwsCancel=true; h.retry.request("initial"); h.advance(60_000);
        h.throwsCancel=false; h.establish(); h.lose(); h.advance(60_000);
        assertEquals("retry_wait",h.state); h.advance(30_000); assertEquals(2,h.connects);
    }

    @Test public void cancelEchoOfAPendingConnectIsNotALinkLoss() {
        Harness h=new Harness(); h.retry.request("initial"); h.advance(60_000);
        h.retry.disconnected();
        assertEquals("retry_wait",h.state); assertEquals("connect_timeout",h.reason);
        assertFalse(h.reports.contains("disconnected/link_lost")); assertEquals(1,h.pending());
        h.advance(30_000); assertEquals(2,h.connects);
    }

    @Test public void linkReadyIsReportedOncePerEstablishedLink() {
        Harness h=new Harness(); h.retry.request("initial");
        h.establish(); h.retry.connected(); h.retry.connected();
        assertEquals(List.of("connecting/initial","connected/link_ready"),h.reports);
    }

    @Test public void restartReconnectsAfterSettleWithoutWaitingForTheCallback() {
        Harness h=new Harness(); h.establish(); h.up=false;   // PolarBle marks the link down first
        h.retry.restart("stall",3_000);
        assertEquals(1,h.cancels); assertEquals("connecting",h.state); assertEquals("stall",h.reason);
        assertEquals(63_000,h.nextAt);
        h.advance(2_999); assertEquals(0,h.connects);
        h.advance(1); assertEquals(1,h.connects); assertEquals(63_000,h.nextAt);
    }

    @Test public void restartIgnoresTheEchoOfItsOwnDisconnect() {
        Harness h=new Harness(); h.establish(); h.up=false; h.retry.restart("stall",3_000);
        h.retry.disconnected(); h.advance(1_000); h.retry.disconnected();
        assertFalse(h.reports.contains("disconnected/link_lost"));
        assertFalse(h.reports.contains("connecting/sdk_reconnect"));
        h.advance(2_000); assertEquals(1,h.connects); assertEquals(1,h.pending());
    }

    @Test public void restartSupersedesAPendingSdkWait() {
        Harness h=new Harness(); h.establish(); h.lose();
        h.retry.restart("recording_stuck",3_000); h.advance(3_000);
        assertEquals(1,h.connects); h.advance(59_999); assertEquals(1,h.cancels);
    }

    @Test public void adapterRestartSkipsTheBackoff() {
        Harness h=new Harness(); h.retry.request("initial"); h.advance(60_000);
        assertEquals("retry_wait",h.state); h.retry.adapterRestarted();
        assertEquals(2,h.connects); assertEquals(1,h.pending());
    }

    @Test public void adapterRestartOnAnEstablishedLinkDoesNothing() {
        Harness h=new Harness(); h.establish(); h.retry.adapterRestarted();
        assertEquals(0,h.connects); assertEquals(0,h.pending());
    }

    @Test public void stopRejectsEveryLaterEvent() {
        Harness h=new Harness(); h.establish(); h.retry.stop(); int before=h.reports.size();
        h.lose(); h.retry.restart("stall",3_000); h.retry.adapterRestarted(); h.retry.connected(); h.advance(600_000);
        assertEquals(before,h.reports.size()); assertEquals(0,h.connects); assertEquals(0,h.cancels);
    }

    @Test public void connectedFlagWinsAgainstATimeoutBeforeCallbackIsDrained() {
        Harness h=new Harness(); h.retry.request("initial"); h.up=true; h.advance(60_000);
        assertEquals(0,h.cancels); assertEquals(0,h.pending());
    }

    @Test public void intentionalReconnectReplacesPassiveSdkWait() {
        Harness h=new Harness(); h.establish(); h.lose(); h.retry.reconnect("stall");
        assertEquals(1,h.connects); assertEquals(1,h.pending());
    }

    // The SDK ignores connectToDevice unless its session is closed (BDBleApiImpl 6.16.1), so
    // every intentional connect over a possibly parked or pending session cancels it first.
    @Test public void reconnectCancelsTheParkedSdkSessionBeforeConnecting() {
        Harness h=new Harness(); h.establish(); h.lose(); h.retry.reconnect("rebond");
        assertEquals(List.of("cancel","connect"),h.calls);
    }

    @Test public void reconnectAfterTheSettleFiredReplacesThatConnectInsteadOfStackingOnIt() {
        Harness h=new Harness(); h.establish(); h.up=false; h.retry.restart("rebond",45_000);
        h.advance(45_000); assertEquals(List.of("cancel","connect"),h.calls);
        h.retry.reconnect("rebond");
        assertEquals(List.of("cancel","connect","cancel","connect"),h.calls);
        assertEquals("connecting/rebond",h.reports.get(h.reports.size()-1)); assertEquals(1,h.pending());
    }

    @Test public void adapterRestartCancelsTheParkedSessionFirst() {
        Harness h=new Harness(); h.establish(); h.lose(); h.retry.adapterRestarted();
        assertEquals(List.of("cancel","connect"),h.calls); assertEquals("connecting/bt_on",h.reports.get(h.reports.size()-1));
    }

    @Test public void adapterRestartStaysHaltedWhileTheCancelStillFails() {
        Harness h=new Harness(); h.throwsCancel=true; h.retry.request("initial"); h.advance(60_000);
        h.retry.adapterRestarted();
        assertEquals(1,h.connects); assertEquals("needs_action",h.state); assertEquals(0,h.pending());
    }

    @Test public void reconnectLeavesAHaltAlone() {
        Harness h=new Harness(); h.throwsCancel=true; h.retry.request("initial"); h.advance(60_000);
        int cancels=h.cancels; h.retry.reconnect("rebond");
        assertEquals(cancels,h.cancels); assertEquals(1,h.connects);
    }

    @Test public void connectedSeenAfterTheLinkWasMarkedDownAgainIsStale() {
        Harness h=new Harness(); h.establish(); h.up=false; h.retry.restart("stall",3_000);
        h.retry.connected();   // queued before the restart's markLinkDown
        assertFalse(h.reports.subList(1,h.reports.size()).contains("connected/link_ready"));
        assertEquals("connecting/stall",h.reports.get(h.reports.size()-1)); assertEquals(63_000,h.nextAt);
        h.advance(3_000); assertEquals(1,h.connects); assertEquals("connecting",h.state);
    }

    @Test public void aStaleConnectedShowsTheOwnersStageAgain() {
        Harness h=new Harness(); h.retry.request("initial"); int shown=h.reports.size();
        h.up=true; h.up=false;   // the recording lane may have shown this short-lived link
        h.retry.connected();
        assertEquals(shown+1,h.reports.size()); assertEquals("connecting/initial",h.reports.get(shown));
        assertEquals(60_000,h.nextAt); assertEquals(1,h.pending()); assertEquals(1,h.connects);
    }

    @Test public void aStaleConnectedLeavesAnUpLinkToItsOwnLossReport() {
        Harness h=new Harness(); h.establish(); int shown=h.reports.size();
        h.up=false; h.retry.connected();
        assertEquals(shown,h.reports.size());
        h.retry.disconnected(); assertEquals("connecting/sdk_reconnect",h.reports.get(h.reports.size()-1));
    }

    @Test public void requestOwnsALinkTheSdkOpenedBeforeItsCallbackWasDrained() {
        Harness h=new Harness(); h.retry.request("initial"); h.advance(60_000);
        h.up=true; h.advance(40_000);   // backoff fires while deviceConnected is still queued
        assertEquals(1,h.connects); assertEquals("connected/link_ready",h.reports.get(h.reports.size()-1));
        h.lose(); assertEquals("connecting/sdk_reconnect",h.reports.get(h.reports.size()-1)); assertEquals(1,h.pending());
    }

    @Test public void backoffDoublesFromThirtySecondsUpToFourMinutes() {
        Harness h=new Harness(); h.retry.request("initial");
        for (long backoff : new long[]{30_000,60_000,120_000,240_000,240_000}) {
            h.advance(60_000); assertEquals("retry_wait",h.state); assertEquals(h.now+backoff,h.nextAt);
            h.advance(backoff); assertEquals("connecting",h.state);
        }
    }

    @Test public void jitterAddsAtMostAQuarterOfTheBackoff() {
        Harness h=new Harness(); h.jitter=7; h.retry.request("initial");
        h.advance(60_000); assertEquals(60_000+37_500,h.nextAt);
        h.jitter=-3; h.advance(37_500); h.advance(60_000); assertEquals(h.now+60_000,h.nextAt);
    }

    @Test public void attemptCountSaturatesAtTwenty() {
        Harness h=new Harness(); h.retry.request("initial");
        for (int i=0;i<30;i++) h.advance(300_000);   // a deadline plus the longest backoff per round
        assertEquals(20,h.attempt);
    }

    @Test public void adapterRestartLeavesALinkTheSdkAlreadyOpenedToItsQueuedCallback() {
        Harness h=new Harness(); h.retry.request("initial"); int shown=h.reports.size();
        h.up=true;   // deviceConnected is still queued behind the adapter broadcast
        h.retry.adapterRestarted();
        assertEquals(List.of("connect"),h.calls); assertEquals(shown,h.reports.size());
        h.retry.connected(); assertEquals("connected/link_ready",h.reports.get(h.reports.size()-1));
    }

    @Test public void nothingReachesTheSdkOrTheDisplayAfterStop() {
        Harness h=new Harness(); h.retry.request("initial"); h.retry.stop(); int shown=h.reports.size();
        h.retry.request("again"); h.retry.reconnect("rebond"); h.retry.adapterRestarted(); h.retry.restart("stall",3_000);
        h.up=true; h.retry.connected(); h.up=false; h.retry.disconnected(); h.advance(1_000_000);
        assertEquals(List.of("connect"),h.calls); assertEquals(shown,h.reports.size()); assertEquals(0,h.pending());
    }

    @Test public void stopDuringAFailingConnectLeavesTheSdkAlone() {
        Harness h=new Harness(); h.throwsConnect=true; h.duringConnect=h.retry::stop;   // stop runs off the control lane
        h.retry.request("initial");
        assertEquals(List.of("connect"),h.calls); assertEquals(0,h.pending());
    }

    @Test public void aTimerArmedWhileStopRunsIsCancelled() {
        Harness h=new Harness(); h.duringSchedule=h.retry::stop;
        h.retry.request("initial");
        assertEquals(0,h.pending()); assertEquals(0,h.connects);
    }

    // #31/#32: a backoff leaves nothing pending, so the link stands by — the CPU may sleep and
    // the device is looked for — and a sighting connects at once instead of after the backoff.
    @Test public void aBackoffStandsByAndWatchesOnAWakingTimer() {
        Harness h=new Harness(); h.retry.request("initial");
        assertFalse(h.armed().waking);                       // the deadline runs while awake
        h.advance(60_000);
        assertEquals(List.of("connect","cancel","standby+watch"),h.calls);
        assertTrue(h.standing); assertEquals("retry_wait",h.state);
        assertEquals(1,h.pending()); assertTrue(h.armed().waking);
        h.advance(30_000);
        assertEquals(List.of("connect","cancel","standby+watch","resume","connect"),h.calls);
        assertFalse(h.armed().waking); assertEquals("connecting/deadline_retry",h.lastReport());
    }

    @Test public void aSightingConnectsAtOnceInsteadOfWaitingTheBackoffOut() {
        Harness h=new Harness(); h.retry.request("initial"); h.advance(60_000); h.advance(10_000);
        h.retry.sighted();
        assertEquals(List.of("connect","cancel","standby+watch","resume","connect"),h.calls);
        assertEquals("connecting/sighted",h.lastReport()); assertEquals(2,h.attempt);
        assertEquals(h.now+BleConnectionRetry.DEADLINE_MS,h.nextAt);
        assertEquals(1,h.pending()); assertFalse(h.armed().waking);   // the backoff timer is gone
        h.establish(); assertEquals("connected/link_ready",h.lastReport()); assertEquals(0,h.pending());
    }

    @Test public void aSightingOutsideAWatchingBackoffIsIgnored() {
        Harness h=new Harness(); h.retry.sighted();                     // idle
        h.retry.request("initial"); h.retry.sighted();                  // connect window
        h.establish(); h.retry.sighted();                               // up
        h.lose(); h.retry.sighted();                                    // SDK reconnect window
        assertEquals(List.of("connect"),h.calls);
        h.advance(60_000); h.retry.stop(); h.retry.sighted();           // stopped in a backoff
        assertEquals(List.of("connect","cancel","standby+watch"),h.calls);
    }

    @Test public void aSightedAttemptThatFailsBacksOffOnceWithoutWatching() {
        Harness h=new Harness(); h.retry.request("initial"); h.advance(60_000);
        h.retry.sighted(); h.advance(60_000);
        assertEquals("standby",h.lastCall()); assertEquals(h.now+60_000,h.nextAt);
        h.retry.sighted();                                              // a late result: still waits
        assertEquals(2,h.connects); assertEquals(1,h.pending());
        h.advance(60_000); assertEquals(List.of("resume","connect"),h.calls.subList(h.calls.size()-2,h.calls.size()));
        h.advance(60_000); assertEquals("standby+watch",h.lastCall());   // the timer's attempt watches again
    }

    @Test public void theSdkReconnectWindowWatchesAgainAfterASightedLink() {
        Harness h=new Harness(); h.retry.request("initial"); h.advance(60_000);
        h.retry.sighted(); h.establish(); h.lose(); h.advance(60_000);
        assertEquals("standby+watch",h.lastCall());
    }

    @Test public void onlyTheBackoffTimerWakesTheCpu() {
        Harness h=new Harness(); h.establish(); h.up=false; h.retry.restart("stall",3_000);
        assertFalse(h.armed().waking);                                  // settle
        h.advance(3_000); assertFalse(h.armed().waking);                // deadline
        h.advance(60_000); assertTrue(h.armed().waking);                // backoff
        h.retry.stop(); assertEquals(0,h.pending());
    }

    @Test public void everyWayOutOfABackoffResumesFirst() {
        List<java.util.function.Consumer<Harness>> exits = List.of(
                x -> x.advance(30_000), x -> x.retry.sighted(), x -> x.retry.adapterRestarted(),
                x -> x.retry.reconnect("rebond"), x -> x.retry.restart("stall",3_000),
                x -> { x.up=true; x.retry.connected(); });
        for (java.util.function.Consumer<Harness> exit : exits) {
            Harness h=new Harness(); h.retry.request("initial"); h.advance(60_000);
            int before=h.calls.size(); exit.accept(h);
            assertFalse(h.standing); assertEquals("resume",h.calls.get(before));
        }
    }

    @Test public void aStaleConnectedInABackoffKeepsStandingBy() {
        Harness h=new Harness(); h.retry.request("initial"); h.advance(60_000);
        h.retry.connected();                                            // a link already gone again
        assertTrue(h.standing); assertEquals("retry_wait/connect_timeout",h.lastReport());
    }

    @Test public void aBackoffThatCannotCancelHaltsWithoutStandingBy() {
        Harness h=new Harness(); h.throwsCancel=true; h.retry.request("initial"); h.advance(60_000);
        assertFalse(h.calls.contains("standby+watch")); assertFalse(h.standing);
    }

    @Test public void aSupersededTimerThatFiresAnywayDoesNothing() {
        Harness h=new Harness(); h.lossyTimer=true; h.retry.request("initial");   // deadline at 60 s
        h.advance(10_000); h.retry.restart("stall",3_000);                         // its cancel is lost
        h.advance(53_000);                                                         // past the old deadline
        assertEquals("connecting",h.state); assertEquals(1,h.cancels); assertEquals(2,h.connects);
    }
}
