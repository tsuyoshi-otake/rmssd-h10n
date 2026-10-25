package dev.otake.rmssdh10n;

import org.junit.Test;
import java.util.ArrayList;
import java.util.List;
import java.util.Random;
import static org.junit.Assert.*;

/** Stateful PBT for BleStageReporter: random sequences of plain reports, generation-guarded
 *  reports, selection-scan overlays and stop, checked against a reference model of the
 *  publication contract. */
public class BleStageReporterStatefulTest {
    enum Op { REPORT, GUARDED_LIVE, GUARDED_STALE, BEGIN_SCAN, END_SCAN, STOP }
    private static final String[] STAGES = {
            "connecting/initial", "retry_wait/connect_timeout", "connected/link_ready", "streaming/recording_ready"};
    private static final String SCAN = "scanning/device_selection";

    static final class Cmd {
        final Op op; final int stage;
        Cmd(Op op, int stage) { this.op = op; this.stage = stage; }
        @Override public String toString() { return stage < 0 ? op.name() : op + "(" + STAGES[stage] + ")"; }
    }

    static Cmd gen(Random r) {
        int x = r.nextInt(100);
        if (x < 30) return new Cmd(Op.REPORT, r.nextInt(STAGES.length));
        if (x < 40) return new Cmd(Op.GUARDED_LIVE, r.nextInt(STAGES.length));
        if (x < 50) return new Cmd(Op.GUARDED_STALE, r.nextInt(STAGES.length));
        if (x < 73) return new Cmd(Op.BEGIN_SCAN, -1);
        if (x < 97) return new Cmd(Op.END_SCAN, -1);
        return new Cmd(Op.STOP, -1);
    }

    @Test public void publicationMatchesItsModel() {
        StatefulPbt.check("BleStageReporter", 0x57A6E5L, 3000, 40,
                BleStageReporterStatefulTest::gen, BleStageReporterStatefulTest::run);
    }

    static void run(List<Cmd> commands) {
        List<String> out = new ArrayList<>();
        BleStageReporter reporter = new BleStageReporter((s, r, n, next) -> out.add(s + "/" + r));
        Spec spec = new Spec();
        for (int i = 0; i < commands.size(); i++) {
            Cmd c = commands.get(i);
            String stage = c.stage < 0 ? null : STAGES[c.stage];
            switch (c.op) {
                case REPORT: report(reporter, stage); spec.publish(stage); break;
                case GUARDED_LIVE: reporter.reportIf(() -> true, part(stage, 0), part(stage, 1), 0, 0); spec.publish(stage); break;
                case GUARDED_STALE: reporter.reportIf(() -> false, part(stage, 0), part(stage, 1), 0, 0); break;
                case BEGIN_SCAN: reporter.beginOverlay("scanning", "device_selection", 0, 0); spec.beginScan(); break;
                case END_SCAN: reporter.endOverlay(); spec.endScan(); break;
                case STOP: reporter.stop("user_or_service_stop"); spec.stop(); break;
            }
            assertEquals("after #" + i + " " + c, spec.out, out);
        }
    }

    private static void report(BleStageReporter reporter, String stage) {
        reporter.report(part(stage, 0), part(stage, 1), 0, 0);
    }
    private static String part(String stage, int i) { return stage.split("/")[i]; }

    /** Contract: nothing after stop; a scan shows an overlay and, when it ends, the stage it
     *  covered comes back unless something newer replaced the overlay; an overlay is never
     *  itself treated as a stage to restore. */
    static final class Spec {
        final List<String> out = new ArrayList<>();
        private String shown, covered;
        private boolean overlayShown, stopped;

        void publish(String stage) {
            if (stopped) return;
            out.add(stage); shown = stage; overlayShown = false;
        }
        void beginScan() {
            if (stopped) return;
            String under = overlayShown ? covered : shown;
            publish(SCAN);
            overlayShown = true; covered = under;
        }
        void endScan() {
            String restore = overlayShown ? covered : null;
            covered = null;
            if (restore != null) publish(restore);
        }
        void stop() {
            if (stopped) return;
            publish("stopped/user_or_service_stop");
            stopped = true;
        }
    }
}
