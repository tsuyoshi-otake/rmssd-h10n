package dev.otake.rmssdh10n;

import org.junit.Test;
import java.util.ArrayList;
import java.util.List;
import static org.junit.Assert.*;

public class BleStageReporterTest {
    private final List<String> out = new ArrayList<>();
    private final BleStageReporter stages = new BleStageReporter((s, r, n, next) -> out.add(s + "/" + r));

    @Test public void guardedReportOfASupersededJobIsDropped() {
        stages.report("connected", "link_ready", 0, 0);
        stages.reportIf(() -> false, "streaming", "recording_ready", 0, 0);
        stages.reportIf(() -> true, "recording_wait", "streams_settling", 0, 1);
        assertEquals(List.of("connected/link_ready", "recording_wait/streams_settling"), out);
    }

    @Test public void stopIsTerminalAndPublishedOnce() {
        stages.report("connected", "link_ready", 0, 0);
        stages.stop("user_or_service_stop");
        stages.stop("again");
        stages.report("disconnected", "link_lost", 0, 0);
        stages.reportIf(() -> true, "streaming", "recording_ready", 0, 0);
        stages.beginOverlay("scanning", "device_selection", 0, 1);
        stages.endOverlay();
        assertEquals(List.of("connected/link_ready", "stopped/user_or_service_stop"), out);
    }

    @Test public void scanOverlayRestoresTheStageItCovered() {
        stages.report("retry_wait", "connect_timeout", 1, 90_000);
        stages.beginOverlay("scanning", "device_selection", 0, 1);
        stages.endOverlay();
        assertEquals(List.of("retry_wait/connect_timeout", "scanning/device_selection",
                "retry_wait/connect_timeout"), out);
    }

    @Test public void overlayReplacedByANewerStageIsNotRestored() {
        stages.report("retry_wait", "connect_timeout", 1, 90_000);
        stages.beginOverlay("scanning", "device_selection", 0, 1);
        stages.report("connected", "link_ready", 0, 0);
        stages.endOverlay();
        assertEquals(List.of("retry_wait/connect_timeout", "scanning/device_selection",
                "connected/link_ready"), out);
    }

    @Test public void backToBackScansRestoreTheOriginalStageNotTheFirstScan() {
        stages.report("connected", "link_ready", 0, 0);
        stages.beginOverlay("scanning", "device_selection", 0, 1);
        stages.beginOverlay("scanning", "device_selection", 0, 2);
        stages.endOverlay();
        assertEquals("connected/link_ready", out.get(out.size() - 1));
        stages.endOverlay();
        assertEquals(4, out.size());
    }

    @Test public void overlayWithNothingBeneathRestoresNothing() {
        stages.beginOverlay("scanning", "device_selection", 0, 1);
        stages.endOverlay();
        stages.endOverlay();
        assertEquals(List.of("scanning/device_selection"), out);
    }

    @Test public void aThrowingConsumerDoesNotBreakLaterReports() {
        List<String> seen = new ArrayList<>();
        BleStageReporter r = new BleStageReporter((s, reason, n, next) -> {
            seen.add(s);
            if (seen.size() == 1) throw new IllegalStateException();
        });
        r.report("connecting", "initial", 1, 0);
        r.report("connected", "link_ready", 0, 0);
        assertEquals(List.of("connecting", "connected"), seen);
    }

    @Test public void anOverlayLeftOnScreenIsNeverRestoredByALaterScan() {
        stages.beginOverlay("scanning", "device_selection", 0, 0); stages.endOverlay();
        stages.beginOverlay("scanning", "device_selection", 0, 0); stages.endOverlay();
        assertEquals(List.of("scanning/device_selection", "scanning/device_selection"), out);
    }

    @Test public void stopStageCarriesNoAttemptOrRetryTime() {
        List<String> full = new ArrayList<>();
        BleStageReporter reporter = new BleStageReporter((s, r, n, next) -> full.add(s + "/" + r + "#" + n + "@" + next));
        reporter.report("retry_wait", "connect_timeout", 3, 90_000);
        reporter.stop("user_or_service_stop");
        assertEquals(List.of("retry_wait/connect_timeout#3@90000", "stopped/user_or_service_stop#0@0"), full);
    }
}
