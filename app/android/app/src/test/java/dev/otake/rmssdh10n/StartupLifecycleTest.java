package dev.otake.rmssdh10n;

import org.junit.Test;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import static org.junit.Assert.*;

public class StartupLifecycleTest {
    @Test public void lateStartupResourceIsClosedAfterStop() throws Exception {
        LifecycleSlot<Object> slot = new LifecycleSlot<>();
        CountDownLatch created = new CountDownLatch(1), release = new CountDownLatch(1);
        AtomicInteger closed = new AtomicInteger();
        Thread startup = new Thread(() -> {
            Object candidate = new Object();
            created.countDown();
            try { release.await(1, TimeUnit.SECONDS); } catch (InterruptedException e) { Thread.currentThread().interrupt(); }
            assertFalse(slot.publish(candidate, ignored -> closed.incrementAndGet()));
        });
        startup.start();
        assertTrue(created.await(500, TimeUnit.MILLISECONDS));
        assertNull(slot.stopAndTake());
        release.countDown(); startup.join(1000);
        assertFalse(startup.isAlive());
        assertNull(slot.get());
        assertEquals(1, closed.get());
    }
}
