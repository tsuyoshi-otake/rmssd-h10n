package dev.otake.rmssdh10n.hrv;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * Step / cadence detection from the H10's chest accelerometer. Java port of
 * src/steps.js: gravity-removed dynamic magnitude, light low-pass, adaptive
 * peak detection within the walking cadence window, and a rhythm gate so single
 * jolts don't count. Liveness uses the signal clock (tSec from ACC samples),
 * not wall time, so it behaves identically off-screen.
 */
public final class Steps {
    static final double MIN_INT = 0.25; // <= 240 spm (reject double-count)
    static final double MAX_INT = 1.4;  // >= ~43 spm
    static final double MIN_AMP = 80;   // mg, floor for adaptive threshold
    static final double IDLE_SEC = 2.5; // no step within this -> not walking

    private final double dt, aGrav, aLp;
    private boolean hasGrav = false;
    private double grav = 0;
    private double lp = 0, prevLp = 0;
    private boolean rising = false;
    private double tSec = 0, lastPeakSec = -10;
    private double env = 0;
    private int run = 0;
    private final List<Double> intervals = new ArrayList<>();
    public int steps = 0;
    private double lastStepSec = -10;

    public Steps() { this(25); }

    public Steps(int sampleRate) {
        this.dt = 1.0 / sampleRate;
        this.aGrav = 1 - Math.exp(-1.0 / (1.0 * sampleRate));
        this.aLp = 1 - Math.exp(-1.0 / (0.08 * sampleRate));
    }

    public void add(double sx, double sy, double sz) {
        double m = Math.sqrt(sx * sx + sy * sy + sz * sz);
        if (!hasGrav) { grav = m; lp = 0; prevLp = 0; tSec = 0; hasGrav = true; return; }
        grav += aGrav * (m - grav);
        double dyn = m - grav;
        prevLp = lp;
        lp += aLp * (dyn - lp);
        tSec += dt;

        boolean wasRising = rising;
        rising = lp > prevLp;
        if (wasRising && !rising) { // local maximum just passed
            double amp = prevLp;
            double thr = Math.max(MIN_AMP, 0.5 * env);
            if (amp > thr) {
                env += 0.3 * (amp - env);
                double interval = tSec - lastPeakSec;
                if (interval >= MIN_INT && interval <= MAX_INT) {
                    run++;
                    intervals.add(interval);
                    if (intervals.size() > 6) intervals.remove(0);
                    if (run == 2) steps += 2;       // confirm rhythm: count the first pair
                    else if (run > 2) steps += 1;
                    lastStepSec = tSec;
                } else {
                    run = 0; intervals.clear();      // rhythm broken
                }
                lastPeakSec = tSec;
            }
        }
    }

    public boolean walking() { return (tSec - lastStepSec) < IDLE_SEC; }

    public int cadence() {
        if (!walking() || intervals.size() < 2) return 0;
        List<Double> s = new ArrayList<>(intervals);
        Collections.sort(s);
        int mid = s.size() >> 1;
        double med = (s.size() % 2 == 1) ? s.get(mid) : (s.get(mid - 1) + s.get(mid)) / 2.0;
        return med > 0 ? (int) Math.round(60 / med) : 0;
    }
}
