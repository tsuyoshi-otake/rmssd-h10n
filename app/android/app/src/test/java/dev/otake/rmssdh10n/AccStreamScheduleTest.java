package dev.otake.rmssdh10n;

import org.junit.Test;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.atomic.AtomicInteger;
import static org.junit.Assert.*;

public class AccStreamScheduleTest {
    @Test public void oldBurstEndCannotStopNewContinuousStream() throws Exception {
        ScheduledExecutorService executor = Executors.newSingleThreadScheduledExecutor();
        AccStreamSchedule schedule = new AccStreamSchedule(executor);
        AtomicInteger starts = new AtomicInteger(), stops = new AtomicInteger();
        try {
            schedule.begin(true, 80, 5_000, () -> true, starts::incrementAndGet, stops::incrementAndGet);
            long until = System.currentTimeMillis() + 500;
            while (starts.get() < 1 && System.currentTimeMillis() < until) Thread.sleep(5);
            assertEquals(1, starts.get());
            schedule.begin(false, 80, 5_000, () -> true, starts::incrementAndGet, stops::incrementAndGet);
            Thread.sleep(140);
            assertEquals(2, starts.get());
            assertEquals("cancelled duty timer must not dispose continuous stream", 0, stops.get());
        } finally { schedule.cancel(); executor.shutdownNow(); }
    }
}
