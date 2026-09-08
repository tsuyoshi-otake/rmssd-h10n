package dev.otake.rmssdh10n;

import org.junit.Test;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import static org.junit.Assert.*;

public class BleControlQueueTest {
    @Test public void blockedPftpDoesNotDelayControlAndPftpRemainsSerial() throws Exception {
        BleControlQueue queue = new BleControlQueue();
        CountDownLatch release = new CountDownLatch(1), recordingStarted = new CountDownLatch(1);
        CountDownLatch controlRan = new CountDownLatch(3), allRecording = new CountDownLatch(3);
        AtomicInteger active = new AtomicInteger(), max = new AtomicInteger();
        Runnable serial = () -> {
            int now = active.incrementAndGet(); max.accumulateAndGet(now, Math::max);
            allRecording.countDown(); active.decrementAndGet();
        };
        try {
            queue.recording().execute(() -> {
                recordingStarted.countDown();
                try { release.await(2, TimeUnit.SECONDS); } catch (InterruptedException e) { Thread.currentThread().interrupt(); }
                serial.run();
            });
            assertTrue(recordingStarted.await(500, TimeUnit.MILLISECONDS));
            queue.recording().execute(serial);
            queue.recording().execute(serial);
            // Model the independent watchdog, Bluetooth-on, and user ACC controls that used
            // to queue behind one long PFTP blockingGet on the shared executor.
            queue.control().execute(controlRan::countDown);
            queue.control().execute(controlRan::countDown);
            queue.control().execute(controlRan::countDown);
            assertTrue("all live controls must run while PFTP is blocked", controlRan.await(250, TimeUnit.MILLISECONDS));
            release.countDown();
            assertTrue(allRecording.await(1, TimeUnit.SECONDS));
            assertEquals(1, max.get());
        } finally { release.countDown(); queue.shutdownNow(); }
    }
}
