'use strict';

// Torso posture & activity from the Polar H10's chest-mounted accelerometer.
//
// When the body is still, the measured acceleration is just gravity, so a
// low-pass of the raw samples recovers the gravity direction in the strap's
// frame. The lean angle is the angle between the *current* gravity direction
// and a calibrated UPRIGHT reference captured while sitting/standing straight:
//   0°    = same orientation as the reference (upright)
//   small = slight forward/back lean (slouch)
//   large = reclined / lying down
// The high-frequency residual |sample − gravity| is a movement/activity measure.
//
// Inputs are samples in milli-G; magnitudes are kept in mg (gravity ≈ 1000 mg),
// which doubles as a correctness check: a stationary strap must read |g| ≈ 1000.

const RAD2DEG = 180 / Math.PI;

function mag(v) { return Math.sqrt(v.x * v.x + v.y * v.y + v.z * v.z); }
function dot(a, b) { return a.x * b.x + a.y * b.y + a.z * b.z; }
function sub(a, b) { return { x: a.x - b.x, y: a.y - b.y, z: a.z - b.z }; }
function scale(a, k) { return { x: a.x * k, y: a.y * k, z: a.z * k }; }
function cross(a, b) {
  return { x: a.y * b.z - a.z * b.y, y: a.z * b.x - a.x * b.z, z: a.x * b.y - a.y * b.x };
}
function norm(v) { const m = mag(v); return m < 1e-6 ? null : scale(v, 1 / m); }

// Angle (deg) between two vectors via the normalised dot product.
function angleBetween(a, b) {
  const ma = mag(a), mb = mag(b);
  if (ma < 1e-6 || mb < 1e-6) return 0;
  let c = dot(a, b) / (ma * mb);
  c = Math.max(-1, Math.min(1, c));
  return Math.acos(c) * RAD2DEG;
}

class PostureTracker {
  constructor({ ref = null, supineRef = null, latSign = 1, sampleRate = 25 } = {}) {
    // Gravity low-pass: ~1.5 s time constant at 25 Hz. Activity uses the same
    // residual, EMA-smoothed a bit faster.
    this.aG = 1 - Math.exp(-1 / (1.5 * sampleRate));
    this.aA = 1 - Math.exp(-1 / (0.7 * sampleRate));
    this.g = null; // running gravity estimate {x,y,z} in mg
    this.activity = 0; // EMA of |sample − gravity| in mg
    this.samples = 0;
    this.lastSampleAt = 0;
    // Calibrated upright reference {x,y,z}. Reused across restarts if recent.
    this.ref = ref && ref.x != null ? { x: ref.x, y: ref.y, z: ref.z } : null;
    this.calibratedAt = ref && ref.savedAt ? ref.savedAt : null;
    // Supine reference: gravity captured while lying on the back. Together with
    // the upright ref it fixes the body's posterior + lateral axes, so a lying
    // gravity vector resolves to supine / prone / left / right.
    this.supineRef = supineRef && supineRef.x != null ? { x: supineRef.x, y: supineRef.y, z: supineRef.z } : null;
    // Left/right depends on the sensor-frame handedness and how the strap is
    // worn; if it comes out mirrored, this flips it (set from a UI toggle).
    this.latSign = latSign === -1 ? -1 : 1;
    this._lastAutoCalAttempt = 0;
  }

  // Thresholds (tunable). Lean bins in degrees, rest gate in mg.
  static get LEAN() { return { upright: 12, lean: 35, reclined: 65 }; }
  static get REST_ACTIVITY() { return 45; }   // |residual| below this ≈ still
  static get MOVE_ACTIVITY() { return 130; }   // above this ≈ actively moving
  static get G_MIN() { return 750; }
  static get G_MAX() { return 1250; }

  add(s) {
    if (!s) return;
    this.samples++;
    this.lastSampleAt = Date.now();
    if (!this.g) { this.g = { x: s.x, y: s.y, z: s.z }; return; }
    this.g.x += this.aG * (s.x - this.g.x);
    this.g.y += this.aG * (s.y - this.g.y);
    this.g.z += this.aG * (s.z - this.g.z);
    const res = Math.sqrt(
      (s.x - this.g.x) ** 2 + (s.y - this.g.y) ** 2 + (s.z - this.g.z) ** 2);
    this.activity += this.aA * (res - this.activity);

    // Auto-calibrate once if we have no usable reference and the wearer has been
    // still with a sane gravity magnitude (mirrors the HRV resting-gate idea).
    if (!this.ref && this.samples > 3 * 25 && this.activity < PostureTracker.REST_ACTIVITY) {
      const gm = mag(this.g);
      if (gm > PostureTracker.G_MIN && gm < PostureTracker.G_MAX) this._setRef();
    }
  }

  _setRef() {
    if (!this.g) return null;
    this.ref = { x: this.g.x, y: this.g.y, z: this.g.z };
    this.calibratedAt = Date.now();
    return { ...this.ref, savedAt: this.calibratedAt };
  }

  // Manual recalibration: capture the current gravity direction as upright.
  // Returns the persistable reference, or null if no reading yet.
  setReference() { return this._setRef(); }

  // Capture the current gravity as the SUPINE (on-the-back) reference. Only
  // valid while actually lying (torso roughly horizontal vs the upright ref),
  // so a press while seated is rejected. Returns the persistable vector or null.
  setSupineReference() {
    if (!this.g || !this.ref) return null;
    if (angleBetween(this.g, this.ref) < 55) return null; // not lying down
    this.supineRef = { x: this.g.x, y: this.g.y, z: this.g.z };
    return { ...this.supineRef, savedAt: Date.now() };
  }

  // Resolve the current lying orientation to supine/prone/left/right using the
  // upright (longitudinal) + supine (posterior) references. Returns null if not
  // calibrated. Right/left follow the right-handed sensor frame; if the strap is
  // worn flipped they swap, but supine/prone stay correct.
  _sleepPos() {
    if (!this.g || !this.ref || !this.supineRef) return null;
    const Lh = norm(this.ref);                       // longitudinal (down-body)
    if (!Lh) return null;
    const Ph = norm(sub(this.supineRef, scale(Lh, dot(this.supineRef, Lh)))); // posterior
    if (!Ph) return null;
    const Lat = norm(cross(Lh, Ph));                 // body-right
    if (!Lat) return null;
    const gp = sub(this.g, scale(Lh, dot(this.g, Lh)));
    const aP = dot(gp, Ph), aLat = this.latSign * dot(gp, Lat);
    if (Math.abs(aP) >= Math.abs(aLat)) return aP > 0 ? 'supine' : 'prone';
    return aLat > 0 ? 'right' : 'left';
  }

  // Snapshot for the 1 Hz status. `state` is the orientation class; `moving`
  // flags active movement; `receiving` says ACC frames are currently arriving.
  compute(nowMs = Date.now()) {
    const receiving = this.g != null && (nowMs - this.lastSampleAt) < 3000;
    if (!this.g || !receiving) {
      return { receiving: false, calibrated: !!this.ref, state: 'nosignal',
        leanDeg: null, activity: null, moving: false };
    }
    const activity = Math.round(this.activity);
    const moving = activity > PostureTracker.MOVE_ACTIVITY;
    if (!this.ref) {
      return { receiving: true, calibrated: false, state: 'uncal',
        leanDeg: null, activity, moving };
    }
    const leanDeg = Math.round(angleBetween(this.g, this.ref));
    const L = PostureTracker.LEAN;
    let state;
    if (leanDeg <= L.upright) state = 'upright';
    else if (leanDeg <= L.lean) state = 'lean';
    else if (leanDeg <= L.reclined) state = 'reclined';
    else state = 'lying';
    const sleepPos = state === 'lying' ? this._sleepPos() : null;
    return { receiving: true, calibrated: true, state, leanDeg, activity, moving, sleepPos };
  }
}

module.exports = { PostureTracker, angleBetween, mag };

// ---- self-test: `node src/posture.js` ------------------------------------
if (require.main === module) {
  let fail = 0;
  const ok = (cond, msg) => { if (!cond) { console.error(`FAIL ${msg}`); fail++; } };

  // Feed a steady upright gravity (z = -1000 mg), calibrate, then tilt forward.
  const t = new PostureTracker({ sampleRate: 25 });
  for (let i = 0; i < 200; i++) t.add({ x: 0, y: 0, z: -1000 });
  ok(t.ref != null, 'auto-calibrated while still');
  let s = t.compute();
  ok(s.state === 'upright', `upright after cal (got ${s.state}, lean ${s.leanDeg})`);

  // Tilt ~30° forward: gravity now has an x component.
  const gx = Math.sin(30 / RAD2DEG) * 1000, gz = -Math.cos(30 / RAD2DEG) * 1000;
  for (let i = 0; i < 200; i++) t.add({ x: gx, y: 0, z: gz });
  s = t.compute();
  ok(s.leanDeg >= 25 && s.leanDeg <= 35, `~30° lean (got ${s.leanDeg})`);
  ok(s.state === 'lean', `lean class (got ${s.state})`);

  // Lying: gravity orthogonal to the upright reference (~90°).
  for (let i = 0; i < 300; i++) t.add({ x: 1000, y: 0, z: 0 });
  s = t.compute();
  ok(s.leanDeg >= 80, `~90° lean (got ${s.leanDeg})`);
  ok(s.state === 'lying', `lying class (got ${s.state})`);

  // Sleep position: body axes x=right, y=head(up), z=anterior(front).
  // Upright gravity → toward feet (-y); supine gravity → toward back (-z).
  const sp = new PostureTracker({ sampleRate: 25 });
  sp.ref = { x: 0, y: -1000, z: 0 };
  sp.supineRef = { x: 0, y: 0, z: -1000 };
  sp.g = { x: 0, y: 0, z: -1000 }; ok(sp._sleepPos() === 'supine', `supine (got ${sp._sleepPos()})`);
  sp.g = { x: 0, y: 0, z: 1000 };  ok(sp._sleepPos() === 'prone', `prone (got ${sp._sleepPos()})`);
  sp.g = { x: 1000, y: 0, z: 0 };  ok(sp._sleepPos() === 'right', `right side (got ${sp._sleepPos()})`);
  sp.g = { x: -1000, y: 0, z: 0 }; ok(sp._sleepPos() === 'left', `left side (got ${sp._sleepPos()})`);

  console.log(fail === 0 ? 'posture.js self-test: OK' : `posture.js self-test: ${fail} FAILED`);
  process.exit(fail === 0 ? 0 : 1);
}
