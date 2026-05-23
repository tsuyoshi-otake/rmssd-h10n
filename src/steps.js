'use strict';

// Step / cadence detection from the Polar H10's chest accelerometer.
//
// A chest strap picks up the vertical bounce of each footfall, so a streaming
// pedometer works on the same 25 Hz ACC samples we use for posture:
//   1. acceleration magnitude |a| (mg)
//   2. remove gravity with a slow EMA baseline  -> dynamic component
//   3. light low-pass, then detect local maxima above an adaptive threshold
//      within the walking cadence window (≈ 0.25–1.4 s between steps)
//   4. require a couple of rhythmic peaks in a row before counting, so single
//      jolts (typing, gestures) don't register as steps.
// Chest mounting is less accurate than wrist/hip — good for cadence and
// "walking vs not", rough (±10 %ish) for absolute counts.

function median(arr) {
  if (!arr.length) return 0;
  const a = arr.slice().sort((x, y) => x - y);
  const m = a.length >> 1;
  return a.length % 2 ? a[m] : (a[m - 1] + a[m]) / 2;
}

class StepCounter {
  constructor({ sampleRate = 25 } = {}) {
    this.dt = 1 / sampleRate;
    this.aGrav = 1 - Math.exp(-1 / (1.0 * sampleRate)); // gravity baseline (~1 s)
    this.aLp = 1 - Math.exp(-1 / (0.08 * sampleRate));  // light smoothing
    this.grav = null;
    this.lp = 0; this.prevLp = 0; this.rising = false;
    this.tSec = 0; this.lastPeakSec = -10;
    this.env = 0;            // adaptive peak-amplitude envelope (mg)
    this.run = 0;            // consecutive in-cadence peaks
    this.intervals = [];     // recent step intervals (s) for cadence
    this.steps = 0;          // cumulative steps since construction
    this.lastStepSec = -10;  // signal-clock time of last counted step
  }

  static get MIN_INT() { return 0.25; }  // ≤ 240 spm (reject double-count)
  static get MAX_INT() { return 1.4; }   // ≥ ~43 spm
  static get MIN_AMP() { return 80; }    // mg, floor for the adaptive threshold
  static get IDLE_SEC() { return 2.5; }  // no step within this → not walking

  add(s) {
    if (!s) return;
    const m = Math.sqrt(s.x * s.x + s.y * s.y + s.z * s.z);
    if (this.grav == null) { this.grav = m; this.lp = 0; this.prevLp = 0; this.tSec = 0; return; }
    this.grav += this.aGrav * (m - this.grav);
    const dyn = m - this.grav;
    this.prevLp = this.lp;
    this.lp += this.aLp * (dyn - this.lp);
    this.tSec += this.dt;

    const wasRising = this.rising;
    this.rising = this.lp > this.prevLp;
    if (wasRising && !this.rising) {            // local maximum just passed
      const amp = this.prevLp;
      const thr = Math.max(StepCounter.MIN_AMP, 0.5 * this.env);
      if (amp > thr) {
        this.env += 0.3 * (amp - this.env);
        const interval = this.tSec - this.lastPeakSec;
        if (interval >= StepCounter.MIN_INT && interval <= StepCounter.MAX_INT) {
          this.run++;
          this.intervals.push(interval);
          if (this.intervals.length > 6) this.intervals.shift();
          if (this.run === 2) this.steps += 2;       // confirm rhythm: count the first pair
          else if (this.run > 2) this.steps += 1;
          this.lastStepSec = this.tSec;
        } else {
          this.run = 0; this.intervals.length = 0;    // rhythm broken
        }
        this.lastPeakSec = this.tSec;
      }
    }
  }

  walking() { return (this.tSec - this.lastStepSec) < StepCounter.IDLE_SEC; }

  // Steps per minute from recent intervals, or 0 if not currently walking.
  cadence() {
    if (!this.walking() || this.intervals.length < 2) return 0;
    const med = median(this.intervals);
    return med > 0 ? Math.round(60 / med) : 0;
  }

  snapshot() {
    return { steps: this.steps, cadence: this.cadence(), walking: this.walking() };
  }
}

module.exports = { StepCounter, median };

// ---- self-test: `node src/steps.js` --------------------------------------
if (require.main === module) {
  let fail = 0;
  const ok = (cond, msg) => { if (!cond) { console.error(`FAIL ${msg}`); fail++; } };
  const SR = 25;

  // 10 s of walking at 2 Hz (≈120 spm) → ~20 steps. Vertical bounce ±200 mg on
  // top of 1000 mg gravity; small noise on the other axes.
  const sc = new StepCounter({ sampleRate: SR });
  const N = 10 * SR;
  for (let i = 0; i < N; i++) {
    const t = i / SR;
    const z = -1000 - 200 * Math.sin(2 * Math.PI * 2 * t);
    sc.add({ x: 12 * Math.sin(2 * Math.PI * 2 * t + 1), y: 8 * Math.random(), z });
  }
  ok(sc.steps >= 16 && sc.steps <= 24, `~20 steps walking (got ${sc.steps})`);
  ok(sc.cadence() >= 110 && sc.cadence() <= 130, `~120 spm cadence (got ${sc.cadence()})`);

  // 6 s sitting still (tiny noise) → no new steps.
  const before = sc.steps;
  for (let i = 0; i < 6 * SR; i++) sc.add({ x: 3 * (Math.random() - .5), y: 3 * (Math.random() - .5), z: -1000 + 3 * (Math.random() - .5) });
  ok(sc.steps === before, `no steps while still (added ${sc.steps - before})`);
  ok(sc.cadence() === 0, `cadence 0 when idle (got ${sc.cadence()})`);

  console.log(fail === 0 ? 'steps.js self-test: OK' : `steps.js self-test: ${fail} FAILED`);
  process.exit(fail === 0 ? 0 : 1);
}
