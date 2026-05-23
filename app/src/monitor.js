'use strict';

// Browser/Capacitor port of the desktop monitor's 1 Hz reporting loop
// (index.js). The pure-JS analysis modules are reused verbatim from ../../src;
// only the I/O differs: instead of fs + a WebSocket server, this emits status /
// point objects through callbacks and persists the baseline to localStorage.
//
// It is transport-agnostic: feed it RR intervals via onRr() (and optional HR via
// onHr()) from any source — native BLE on Android, or a synthetic generator in
// the browser — and subscribe to onStatus / onPoint for the dashboard.
import { RmssdWindow, median } from '../../src/rmssd.js';
import { Baseline, StateClassifier } from '../../src/analysis.js';
import { estimateRespiration } from '../../src/respiration.js';
import { PostureTracker } from '../../src/posture.js';
import { StepCounter } from '../../src/steps.js';
import { BodyStateEstimator } from '../../src/bodystate.js';
import { localIso } from '../../src/time.js';
import { loadBaseline, saveBaseline, loadHistSamples, saveHistSamples,
         loadPostureRef, savePostureRef, loadStepsDay, saveStepsDay,
         loadSupineRef, saveSupineRef } from './store.js';

// Local-midnight epoch ms for a given time (day boundary for the step counter).
function dayStartOf(ms) { const d = new Date(ms); d.setHours(0, 0, 0, 0); return d.getTime(); }

export class Monitor {
  constructor({ windowSec = 30, user = 1, mode = 'hr-rr', adaptive = null,
                onStatus = () => {}, onPoint = () => {} } = {}) {
    this.windowSec = windowSec;
    this.mode = mode;
    this.adaptive = adaptive;
    this.onStatus = onStatus;
    this.onPoint = onPoint;
    this.RESP_WINDOW_MS = 120000; // RSA respiration buffer span

    this.currentUser = user;
    this._lastStatus = null;
    this._timer = null;
    this._initUserState();
  }

  _initUserState() {
    this.rmssdWin = new RmssdWindow({ windowMs: this.windowSec * 1000 });
    this.baseline = new Baseline({ samples: 60, adaptive: this.adaptive });
    this.classifier = new StateClassifier({ minDwellMs: 45000 }); // hysteresis
    this.respBuffer = []; // { tMs, rr } of artifact-cleaned NN for RSA
    this.respHistory = []; // recent { brpm, conf } for temporal smoothing
    this.beats = 0;
    this.lastPeakMs = null; // running session time (sum of observed RR)
    this.lastRr = null;
    this.deviceHr = null; // HR reported directly by the device
    this.connected = false;
    this.baselineSaved = false;
    this.lastAdaptedAt = null;

    // Reuse this user's recent (< 24 h) saved baseline so a session starts
    // calibrated rather than recalibrating from scratch.
    const saved = loadBaseline(this.currentUser);
    if (saved) {
      this.baseline.loadFrozen(saved);
      this.baselineSaved = true;
    }

    // Persisted whole-history (1-min downsampled) samples backing the 全期間
    // re-baseline, so it spans restarts (Baseline.history is memory-only).
    this.persistHist = loadHistSamples(this.currentUser);
    this._minBuf = []; // collects the current minute's smoothed readings

    // Posture from the H10 accelerometer. Reuse a recent upright reference so
    // posture is calibrated immediately on reconnect.
    this.posture = new PostureTracker({
      ref: loadPostureRef(this.currentUser),
      supineRef: loadSupineRef(this.currentUser),
    });
    this._postureSavedAt = this.posture.calibratedAt;

    // Step counter (same ACC stream). Daily total persists within the day.
    this.steps = new StepCounter();
    this._lastStepCount = 0;
    const sd = loadStepsDay(this.currentUser);
    const today = dayStartOf(Date.now());
    this.stepsDay = (sd && sd.day === today) ? sd : { day: today, total: 0 };
    this._stepsSavedAt = 0;

    // Contextual body/activity state (sitting / lying / active / asleep).
    this.bodyState = new BodyStateEstimator();
  }

  start() {
    if (this._timer) return;
    this._timer = setInterval(() => this._tick(), 1000);
  }

  stop() {
    if (this._timer) { clearInterval(this._timer); this._timer = null; }
  }

  // --- data ingestion (called by the BLE adapter or the simulator) ----------
  setConnected(v) {
    this.connected = v;
    if (!v) this.deviceHr = null; // clear stale HR while reconnecting
  }

  onHr(hr) { this.deviceHr = hr; }

  // Accelerometer sample {x,y,z} in mg from the H10 (via the BLE adapter).
  onAcc(s) { this.posture.add(s); this.steps.add(s); }

  onRr(rr) {
    this.lastPeakMs = (this.lastPeakMs ?? 0) + rr;
    this._handleRR(this.lastPeakMs, rr);
  }

  _handleRR(tMs, rr) {
    this.beats++;
    this.lastRr = rr;
    const accepted = this.rmssdWin.add(tMs, rr);
    // Only artifact-accepted (NN) beats feed the respiration buffer, so a single
    // ectopic/missed beat cannot corrupt the RSA spectrum.
    if (accepted) {
      this.respBuffer.push({ tMs, rr });
      const cutoff = tMs - this.RESP_WINDOW_MS;
      while (this.respBuffer.length && this.respBuffer[0].tMs < cutoff) this.respBuffer.shift();
    }
  }

  // --- baseline controls (wired to the dashboard buttons) -------------------
  resetBaseline() {
    this.baseline.reset();
    this.respHistory.length = 0;
    this.baselineSaved = false;
    this.lastAdaptedAt = null;
  }

  // Re-derive the baseline from the resting cluster of the whole session so far
  // and apply it at once. Returns the same shape as the desktop /api/baseline/full.
  refreezeFromHistory() {
    const b = this.baseline.refreezeFromHistory({ extra: this.persistHist });
    if (b) {
      this.respHistory.length = 0;
      this.baselineSaved = true;
      this.lastAdaptedAt = this.baseline.adaptedAt;
      saveBaseline(this.currentUser, this.baseline.toJSON());
      return { ok: true, applied: true,
        baseline: { rmssd: Number(b.rmssd.toFixed(1)), hr: Number(b.hr.toFixed(1)), n: b.n } };
    }
    return { ok: true, applied: false, reason: 'insufficient-data' };
  }

  // Manually override the baseline (RMSSD/HR known from prior data). Persists it
  // like an auto-frozen one so it survives restarts. Returns { ok, baseline? }.
  setBaseline(rmssd, hr) {
    const f = this.baseline.setManual(rmssd, hr);
    if (!f) return { ok: false, reason: 'invalid' };
    this.respHistory.length = 0;
    this.baselineSaved = true;
    this.lastAdaptedAt = this.baseline.adaptedAt;
    saveBaseline(this.currentUser, this.baseline.toJSON());
    return { ok: true, baseline: { rmssd: Number(f.rmssd.toFixed(1)), hr: Number(f.hr.toFixed(1)) } };
  }

  // Capture the current orientation as the upright posture reference (wired to
  // the dashboard's 姿勢の基準を取り直す button). Persists like the HRV baseline.
  setPostureReference() {
    const ref = this.posture.setReference();
    if (!ref) return { ok: false, reason: 'no-signal' };
    this._postureSavedAt = this.posture.calibratedAt;
    savePostureRef(this.currentUser, ref);
    return { ok: true };
  }

  // Capture the current orientation as the supine (on-the-back) reference for
  // sleep-position detection. Only valid while lying. Returns { ok }.
  setSupineReference() {
    const ref = this.posture.setSupineReference();
    if (!ref) return { ok: false, reason: 'not-lying' };
    saveSupineRef(this.currentUser, ref);
    return { ok: true };
  }

  switchUser(n) {
    if (!(Number.isInteger(n) && n >= 1 && n <= 5) || n === this.currentUser) return;
    if (this.baseline.get()) saveBaseline(this.currentUser, this.baseline.toJSON());
    this.currentUser = n;
    this._initUserState(); // fresh windows/classifier/baseline + reused saved baseline
    // The next _tick (≤ 1 s) reports the new user; the UI also highlights optimistically.
  }

  getStatus() { return this._lastStatus; }

  // --- 1 Hz reporting loop (mirrors index.js) -------------------------------
  _tick() {
    const { rmssd, rmssdEma, hr, sdnn, count, corrected } = this.rmssdWin.compute(this.lastPeakMs ?? undefined);
    const wall = localIso();
    const effHr = this.deviceHr != null ? this.deviceHr : hr;

    const rmssdVal = rmssd != null ? Number(rmssd.toFixed(1)) : null;
    const rmssdSmoothed = rmssdEma != null ? Number(rmssdEma.toFixed(1)) : null;
    const hrVal = effHr != null ? Number(effHr.toFixed(1)) : null;

    // Baseline (settled readings) reads the SMOOTHED RMSSD so the label does not
    // chase per-second noise; persist on first freeze and on adaptive nudges.
    if (this.connected) {
      this.baseline.add(rmssdSmoothed, hrVal);
      if (this.baseline.get() && !this.baselineSaved) {
        saveBaseline(this.currentUser, this.baseline.toJSON());
        this.baselineSaved = true;
      }
      if (this.baseline.adaptedAt && this.baseline.adaptedAt !== this.lastAdaptedAt) {
        this.lastAdaptedAt = this.baseline.adaptedAt;
        saveBaseline(this.currentUser, this.baseline.toJSON());
      }
    }
    const base = this.baseline.get();
    const state = this.classifier.update(rmssdSmoothed, hrVal, base, Date.now());

    // Respiration via RSA, median-smoothed across recent estimates.
    const resp = estimateRespiration(this.respBuffer);
    let respOut = null, respConf = null, respPreview = false;
    if (resp && (resp.valid || resp.preview)) {
      this.respHistory.push({ brpm: resp.breathsPerMin, conf: resp.confidence });
      if (this.respHistory.length > 5) this.respHistory.shift();
      respOut = Number(median(this.respHistory.map((e) => e.brpm)).toFixed(1));
      respConf = Number(median(this.respHistory.map((e) => e.conf)).toFixed(2));
      respPreview = resp.preview;
    } else {
      this.respHistory.length = 0;
    }

    // Posture from the accelerometer. Persist the reference when the tracker
    // auto-calibrates (resting gate), so it survives a restart like the baseline.
    const posture = this.posture.compute();
    if (this.posture.calibratedAt && this.posture.calibratedAt !== this._postureSavedAt) {
      this._postureSavedAt = this.posture.calibratedAt;
      savePostureRef(this.currentUser, { ...this.posture.ref, savedAt: this.posture.calibratedAt });
    }

    // Steps: fold this tick's increment into the persisted daily total (rolling
    // over at local midnight). `stepDelta` rides on the point for trend/CSV.
    const stepNow = this.steps.steps;
    let stepDelta = stepNow - this._lastStepCount;
    if (stepDelta < 0) stepDelta = 0; // counter reset (shouldn't happen)
    this._lastStepCount = stepNow;
    const today = dayStartOf(Date.now());
    if (this.stepsDay.day !== today) this.stepsDay = { day: today, total: 0 };
    if (stepDelta > 0) {
      this.stepsDay.total += stepDelta;
      if (Date.now() - this._stepsSavedAt > 5000) {
        this._stepsSavedAt = Date.now();
        saveStepsDay(this.currentUser, this.stepsDay);
      }
    }
    const stepInfo = { today: this.stepsDay.total, cadence: this.steps.cadence(), walking: this.steps.walking() };

    // Contextual body/activity state from posture + steps + autonomic + breathing.
    const lnDelta = (base && rmssdSmoothed != null && base.rmssd > 0)
      ? Math.log(rmssdSmoothed / base.rmssd) : null;
    const body = this.bodyState.update({
      walking: stepInfo.walking, activity: posture.activity, leanDeg: posture.leanDeg,
      hr: hrVal, baseHr: base ? base.hr : null, lnDelta, resp: respOut, respConf,
    }, Date.now());

    const status = {
      connected: this.connected,
      user: this.currentUser,
      mode: this.mode,
      hr: hrVal,
      rmssd: rmssdVal,
      rmssdSmoothed,
      sdnn: sdnn != null ? Number(sdnn.toFixed(1)) : null,
      rrCount: count,
      beatsTotal: this.beats,
      rejected: this.rmssdWin.rejected,
      corrected,
      baseline: base ? { rmssd: Number(base.rmssd.toFixed(1)), hr: Number(base.hr.toFixed(1)) } : null,
      calibration: Number(this.baseline.progress().toFixed(2)),
      state,
      respiration: respOut,
      respirationConfidence: respConf,
      respirationPreview: respPreview,
      posture,
      steps: stepInfo,
      body: body.state,
      updatedAt: wall,
    };
    this._lastStatus = status;
    this.onStatus(status);
    // Only push a chart point when there is an actual reading. Emitting nulls
    // while disconnected floods the history and graph with empty points and
    // flattens the lines — a long unconnected stretch would otherwise dominate
    // the time axis and bury the real data.
    if (hrVal != null || rmssdVal != null) {
      this.onPoint({ t: wall, rmssd: status.rmssd, hr: status.hr, resp: status.respiration, tone: state.tone,
        lean: (posture.calibrated && posture.receiving) ? posture.leanDeg : null,
        posture: posture.state, activity: posture.activity, step: stepDelta, body: body.state,
        sleepPos: posture.sleepPos || null });
    }

    // Persist a 1-min downsampled {rmssd, hr} so 全期間 re-baseline survives
    // restarts. Uses the smoothed RMSSD (same signal the baseline tracks).
    if (this.connected && rmssdSmoothed != null && hrVal != null) {
      this._minBuf.push({ rmssd: rmssdSmoothed, hr: hrVal });
      if (this._minBuf.length >= 60) {
        this.persistHist.push({
          rmssd: Number(median(this._minBuf.map((s) => s.rmssd)).toFixed(1)),
          hr: Number(median(this._minBuf.map((s) => s.hr)).toFixed(1)),
          t: Date.now(),
        });
        if (this.persistHist.length > 14 * 24 * 60) this.persistHist.shift();
        saveHistSamples(this.currentUser, this.persistHist);
        this._minBuf = [];
      }
    }
  }
}
