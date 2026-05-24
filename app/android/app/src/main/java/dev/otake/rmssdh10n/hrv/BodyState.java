package dev.otake.rmssdh10n.hrv;

/**
 * Contextual body/activity state from posture + steps + autonomic + breathing.
 * Java port of src/bodystate.js. States: walking|active|sitting|lying|asleep.
 * A chest accelerometer can't separate sitting from standing, so an upright,
 * still, not-walking subject reads as SITTING; only a clearly horizontal torso
 * (lean > 65°) reads as lying. "asleep" needs the quiet+low-HR+high-HRV+slow-
 * breathing signature held for minutes — an estimate, not staging.
 */
public final class BodyState {
    private static final int MOVE_ACT = 160;
    private static final long MOVE_ENTER_MS = 4000;
    private static final long ACTIVE_EXIT_MS = 30000;
    private static final long MIN_DWELL_MS = 12000;
    private static final double LYING_DEG = 65;
    private static final long SLEEP_STILL_MS = 5 * 60000;
    private static final long SLEEP_HOLD_MS = 2 * 60000;
    private static final double HR_MARGIN = 3;
    private static final double LN_SLEEP = 0.10;
    private static final double RESP_SLEEP = 14;

    private Long lastMoveAt = null;
    private Long moveSince = null;
    private Long sleepSince = null;
    private String state = "sitting";
    private long changedAt = -1_000_000_000_000L;

    public static final class Result {
        public final String state;
        public final boolean asleep;
        Result(String state, boolean asleep) { this.state = state; this.asleep = asleep; }
    }

    public Result update(boolean walking, Integer activity, Integer leanDeg,
                         Double hr, Double baseHr, Double lnDelta, Double resp, Double respConf, long now) {
        if (lastMoveAt == null) lastMoveAt = now;

        boolean inst = walking || (activity != null && activity > MOVE_ACT);
        if (inst) { lastMoveAt = now; if (moveSince == null) moveSince = now; }
        else moveSince = null;
        long stillMs = now - lastMoveAt;
        boolean movingSustained = walking || (moveSince != null && now - moveSince >= MOVE_ENTER_MS);
        boolean lying = leanDeg != null && leanDeg > LYING_DEG;

        boolean lowHr = hr != null && baseHr != null && hr < baseHr - HR_MARGIN;
        boolean hrvUp = lnDelta != null && lnDelta > LN_SLEEP;
        boolean slowBreath = resp != null && resp < RESP_SLEEP && (respConf == null || respConf >= 0.3);
        boolean sleepCond = !inst && stillMs >= SLEEP_STILL_MS && lowHr && hrvUp && slowBreath;
        if (sleepCond) { if (sleepSince == null) sleepSince = now; }
        else sleepSince = null;
        boolean asleep = sleepSince != null && now - sleepSince >= SLEEP_HOLD_MS;

        String target;
        if (asleep) target = "asleep";
        else if (movingSustained) target = walking ? "walking" : "active";
        else if (stillMs < ACTIVE_EXIT_MS && (state.equals("active") || state.equals("walking"))) target = "active";
        else if (lying) target = "lying";
        else target = "sitting";

        if (!target.equals(state)) {
            boolean fast = target.equals("active") || target.equals("walking") || target.equals("asleep");
            if (fast || now - changedAt >= MIN_DWELL_MS) { state = target; changedAt = now; }
        }
        return new Result(state, asleep);
    }

    public String state() { return state; }
}
