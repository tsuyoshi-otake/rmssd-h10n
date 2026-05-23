'use strict';

// Contextual body/activity state from signals we already have: posture (torso
// angle + movement), steps (walking/cadence), HR & RMSSD vs the resting
// baseline, and respiration.
//
// A chest accelerometer cannot separate sitting from standing — the torso is
// vertical in both — so for desk-work an upright, still subject who has not been
// moving is treated as SITTING. Leaning forward or reclining onto a backrest are
// things you only do while seated, so anything that is not near-horizontal stays
// SITTING; only a clearly horizontal torso (lean > 70°) reads as lying. "Likely
// asleep" needs the quiet-body + low-HR + high-HRV + slow-breathing signature
// held for minutes — an ESTIMATE, not staging or a medical claim.
//
// To avoid flicker, "active" is entered only after movement is sustained for a
// few seconds (not a single jolt / typing spike), kept through a stillness
// grace window, and the reported state has a minimum dwell time so it cannot
// flip back and forth quickly. Real walking (rhythmic cadence) switches at once.
//
// States: walking | active | sitting | lying | asleep.

class BodyStateEstimator {
  constructor() {
    this.lastMoveAt = null; // last time movement was seen
    this.moveSince = null;  // start of the current continuous movement
    this.sleepSince = null; // start of a continuously-held sleep signature
    this.state = 'sitting';
    this.changedAt = -Infinity; // when the reported state last changed
  }

  static get MOVE_ACT() { return 160; }          // mg → instantaneous movement
  static get MOVE_ENTER_MS() { return 4000; }    // sustained movement before "active"
  static get ACTIVE_EXIT_MS() { return 30000; }  // stillness before active → sitting/lying
  static get MIN_DWELL_MS() { return 12000; }    // min time between (slow) state changes
  static get LYING_DEG() { return 65; }          // matches posture's lying boundary (reclining < 65° stays sitting)
  static get SLEEP_STILL_MS() { return 5 * 60000; } // stillness required before sleep eligible
  static get SLEEP_HOLD_MS() { return 2 * 60000; }  // signature held continuously → asleep
  static get HR_MARGIN() { return 3; }           // bpm below resting baseline
  static get LN_SLEEP() { return 0.10; }         // lnRMSSD above baseline (parasympathetic)
  static get RESP_SLEEP() { return 14; }         // br/min (slow breathing)

  // s: { walking, activity(mg), leanDeg, hr, baseHr, lnDelta, resp, respConf }
  update(s, now) {
    const C = BodyStateEstimator;
    if (this.lastMoveAt == null) this.lastMoveAt = now; // anchor on first tick

    const walking = !!s.walking;
    const inst = walking || (s.activity != null && s.activity > C.MOVE_ACT);
    if (inst) { this.lastMoveAt = now; if (this.moveSince == null) this.moveSince = now; }
    else this.moveSince = null;
    const stillMs = now - this.lastMoveAt;
    // "active" requires either walking, or movement sustained past the entry
    // window — a brief jolt (typing, a gesture) never qualifies.
    const movingSustained = walking || (this.moveSince != null && now - this.moveSince >= C.MOVE_ENTER_MS);
    const lying = s.leanDeg != null && s.leanDeg > C.LYING_DEG;

    // Sleep signature: quiet body + low HR + elevated HRV + slow breathing.
    const lowHr = s.hr != null && s.baseHr != null && s.hr < s.baseHr - C.HR_MARGIN;
    const hrvUp = s.lnDelta != null && s.lnDelta > C.LN_SLEEP;
    const slowBreath = s.resp != null && s.resp < C.RESP_SLEEP && (s.respConf == null || s.respConf >= 0.3);
    const sleepCond = !inst && stillMs >= C.SLEEP_STILL_MS && lowHr && hrvUp && slowBreath;
    if (sleepCond) { if (this.sleepSince == null) this.sleepSince = now; }
    else this.sleepSince = null;
    const asleep = this.sleepSince != null && now - this.sleepSince >= C.SLEEP_HOLD_MS;

    let target;
    if (asleep) target = 'asleep';
    else if (movingSustained) target = walking ? 'walking' : 'active';
    else if (stillMs < C.ACTIVE_EXIT_MS && (this.state === 'active' || this.state === 'walking')) target = 'active';
    else if (lying) target = 'lying';
    else target = 'sitting'; // upright/leaning/reclined, still → seated

    if (target !== this.state) {
      // Movement and waking commit immediately; everything else must respect the
      // minimum dwell so the label does not twitch.
      const fast = target === 'active' || target === 'walking' || target === 'asleep';
      if (fast || now - this.changedAt >= C.MIN_DWELL_MS) { this.state = target; this.changedAt = now; }
    }
    return { state: this.state, asleep };
  }
}

module.exports = { BodyStateEstimator };

// ---- self-test: `node src/bodystate.js` ----------------------------------
if (require.main === module) {
  let fail = 0;
  const ok = (cond, msg) => { if (!cond) { console.error(`FAIL ${msg}`); fail++; } };

  // Walking → walking immediately.
  let e = new BodyStateEstimator();
  ok(e.update({ walking: true, activity: 60, leanDeg: 6 }, 1000).state === 'walking', 'walking');

  // A brief activity spike (< entry window) must NOT become active.
  e = new BodyStateEstimator();
  e.update({ walking: false, activity: 10, leanDeg: 5 }, 0);
  ok(e.update({ walking: false, activity: 300, leanDeg: 5 }, 1000).state === 'sitting', 'short spike stays sitting');
  // …but sustained movement past the entry window does.
  ok(e.update({ walking: false, activity: 300, leanDeg: 5 }, 5000).state === 'active', 'sustained movement → active');

  // Reclining onto a backrest (moderate angle) stays sitting, not lying.
  e = new BodyStateEstimator();
  e.update({ walking: false, activity: 8, leanDeg: 50 }, 0);
  ok(e.update({ walking: false, activity: 8, leanDeg: 50 }, 40000).state === 'sitting', 'reclined (50°) = sitting');

  // Clearly horizontal → lying.
  e = new BodyStateEstimator();
  e.update({ walking: false, activity: 6, leanDeg: 85 }, 0);
  ok(e.update({ walking: false, activity: 6, leanDeg: 85 }, 60000).state === 'lying', 'horizontal = lying');

  // State does not flip faster than the min dwell (sitting↔active stability).
  e = new BodyStateEstimator();
  e.update({ walking: true, activity: 200, leanDeg: 5 }, 0);          // walking
  e.update({ walking: false, activity: 5, leanDeg: 5 }, 1000);        // movement stopped
  ok(e.update({ walking: false, activity: 5, leanDeg: 5 }, 20000).state === 'active', 'grace keeps active ~20s');
  ok(e.update({ walking: false, activity: 5, leanDeg: 5 }, 45000).state === 'sitting', 'settles to sitting after grace');

  // Full sleep signature held ≥ 5 min still + 2 min hold → asleep; wakes on move.
  e = new BodyStateEstimator();
  const sig = { walking: false, activity: 5, leanDeg: 85, hr: 50, baseHr: 60, lnDelta: 0.3, resp: 11, respConf: 0.6 };
  e.update(sig, 0);
  ok(e.update(sig, 300000).state !== 'asleep', 'not asleep at 5 min (hold not met)');
  ok(e.update(sig, 420000).state === 'asleep', 'asleep after still + hold');
  ok(e.update({ ...sig, walking: true }, 421000).state === 'walking', 'wake on movement');

  console.log(fail === 0 ? 'bodystate.js self-test: OK' : `bodystate.js self-test: ${fail} FAILED`);
  process.exit(fail === 0 ? 0 : 1);
}
