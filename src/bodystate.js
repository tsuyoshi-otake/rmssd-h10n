'use strict';

// Contextual body/activity state from signals we already have: posture (torso
// angle + movement), steps (walking/cadence), HR & RMSSD vs the resting
// baseline, and respiration.
//
// A chest accelerometer cannot separate sitting from standing — the torso is
// vertical in both. For desk-work monitoring that distinction barely matters,
// so (per the user's call) an upright subject who is not lying and has not been
// moving recently is treated as SITTING. Lying is read straight from posture.
// "Likely asleep" requires the full quiet-body + autonomic (low HR, high HRV) +
// slow-breathing signature held for several minutes — it is an ESTIMATE, not
// sleep staging and not a medical claim.
//
// States: walking | active | sitting | lying | asleep.

class BodyStateEstimator {
  constructor() {
    this.lastMoveAt = null; // signal/wall time of last movement
    this.sleepSince = null; // when the sleep signature began holding
    this.state = 'sitting';
  }

  static get MOVE_ACT() { return 130; }          // mg → "moving"
  static get LYING_DEG() { return 65; }          // lean angle → lying
  static get ACTIVE_GRACE_MS() { return 20000; } // stay "active" briefly after motion stops
  static get SLEEP_STILL_MS() { return 5 * 60000; } // stillness required before sleep eligible
  static get SLEEP_HOLD_MS() { return 2 * 60000; }  // signature held continuously → asleep
  static get HR_MARGIN() { return 3; }           // bpm below resting baseline
  static get LN_SLEEP() { return 0.10; }         // lnRMSSD above baseline (parasympathetic)
  static get RESP_SLEEP() { return 14; }         // br/min (slow breathing)

  // s: { walking, activity(mg), leanDeg, hr, baseHr, lnDelta, resp, respConf }
  update(s, now) {
    const C = BodyStateEstimator;
    if (this.lastMoveAt == null) this.lastMoveAt = now; // anchor on first tick (no instant sleep)

    const walking = !!s.walking;
    const moving = walking || (s.activity != null && s.activity > C.MOVE_ACT);
    if (moving) this.lastMoveAt = now;
    const stillMs = now - this.lastMoveAt;
    const lying = s.leanDeg != null && s.leanDeg > C.LYING_DEG;

    // Sleep signature: quiet body + low HR + elevated HRV + slow breathing.
    const lowHr = s.hr != null && s.baseHr != null && s.hr < s.baseHr - C.HR_MARGIN;
    const hrvUp = s.lnDelta != null && s.lnDelta > C.LN_SLEEP;
    const slowBreath = s.resp != null && s.resp < C.RESP_SLEEP && (s.respConf == null || s.respConf >= 0.3);
    const sleepCond = !moving && stillMs >= C.SLEEP_STILL_MS && lowHr && hrvUp && slowBreath;
    if (sleepCond) { if (this.sleepSince == null) this.sleepSince = now; }
    else this.sleepSince = null;
    const asleep = this.sleepSince != null && (now - this.sleepSince) >= C.SLEEP_HOLD_MS;

    let state;
    if (asleep) state = 'asleep';
    else if (moving) state = walking ? 'walking' : 'active';
    else if (stillMs < C.ACTIVE_GRACE_MS) state = 'active'; // just stopped moving
    else if (lying) state = 'lying';
    else state = 'sitting'; // upright, still, not walking → sitting (desk-work default)
    this.state = state;
    return { state, asleep };
  }
}

module.exports = { BodyStateEstimator };

// ---- self-test: `node src/bodystate.js` ----------------------------------
if (require.main === module) {
  let fail = 0;
  const ok = (cond, msg) => { if (!cond) { console.error(`FAIL ${msg}`); fail++; } };

  // Walking → walking.
  let e = new BodyStateEstimator();
  ok(e.update({ walking: true, activity: 60, leanDeg: 6, hr: 95, baseHr: 60 }, 1000).state === 'walking', 'walking');

  // Upright, still, not walking, past the grace window → sitting.
  e = new BodyStateEstimator();
  e.update({ walking: false, activity: 10, leanDeg: 5 }, 0);          // anchor
  ok(e.update({ walking: false, activity: 10, leanDeg: 5 }, 30000).state === 'sitting', 'sitting after grace');

  // Just stopped moving → still "active" during the grace window.
  e = new BodyStateEstimator();
  e.update({ walking: true, activity: 200, leanDeg: 5 }, 0);
  ok(e.update({ walking: false, activity: 10, leanDeg: 5 }, 5000).state === 'active', 'active grace');

  // Lying, still, awake (no sleep signature) → lying.
  e = new BodyStateEstimator();
  e.update({ walking: false, activity: 8, leanDeg: 85 }, 0);
  ok(e.update({ walking: false, activity: 8, leanDeg: 85, hr: 70, baseHr: 65 }, 60000).state === 'lying', 'lying awake');

  // Full sleep signature held ≥ 5 min still + 2 min hold → asleep.
  e = new BodyStateEstimator();
  const sig = { walking: false, activity: 5, leanDeg: 85, hr: 50, baseHr: 60, lnDelta: 0.3, resp: 11, respConf: 0.6 };
  e.update(sig, 0);                                   // anchor, stillMs 0
  ok(e.update(sig, 300000).state !== 'asleep', 'not asleep at 5 min (hold not met)');
  ok(e.update(sig, 420000).state === 'asleep', 'asleep after 5 min still + 2 min hold');
  // A movement burst wakes it immediately.
  ok(e.update({ ...sig, walking: true }, 421000).state === 'walking', 'wake on movement');

  console.log(fail === 0 ? 'bodystate.js self-test: OK' : `bodystate.js self-test: ${fail} FAILED`);
  process.exit(fail === 0 ? 0 : 1);
}
