'use strict';

// Baseline tracking + autonomic-state ("mood") estimation from HR / RMSSD.
//
// NOTE: this is a coarse autonomic-arousal heuristic for self-tracking, not a
// medical or psychological diagnosis. It reads the balance of heart rate and
// short-term HRV (RMSSD) relative to a per-session baseline.
//
// Two robustness ideas (per HRV literature + Codex review):
//   1. Classification works on ln(RMSSD) differences, not the raw RMSSD ratio.
//      At a low baseline (e.g. 5 ms) a 1 ms wobble is a 20 % ratio swing, which
//      makes a ratio rule hyper-sensitive; the log scale is the standard way to
//      normalise RMSSD (lnRMSSD) and behaves symmetrically up/down.
//   2. A hysteresis state machine (minimum dwell time + HR dead-band) stops the
//      label flickering on second-to-second noise.

function median(arr) {
  if (!arr.length) return null;
  const s = [...arr].sort((a, b) => a - b);
  const m = s.length >> 1;
  return s.length % 2 ? s[m] : (s[m - 1] + s[m]) / 2;
}

/** Linear-interpolated quantile (q in 0..1) over a numeric array. */
function quantile(arr, q) {
  if (!arr.length) return null;
  const s = [...arr].sort((a, b) => a - b);
  if (s.length === 1) return s[0];
  const pos = (s.length - 1) * q;
  const lo = Math.floor(pos);
  const hi = Math.ceil(pos);
  if (lo === hi) return s[lo];
  return s[lo] + (s[hi] - s[lo]) * (pos - lo);
}

/**
 * One EMA step toward `target`, capped to a relative move of `maxStep` so a
 * single update can never yank the baseline by more than e.g. 20 %.
 */
function emaStep(current, target, alpha, maxStep) {
  if (current == null) return target;
  let next = current + alpha * (target - current);
  const maxDelta = Math.abs(current) * maxStep;
  if (next - current > maxDelta) next = current + maxDelta;
  else if (current - next > maxDelta) next = current - maxDelta;
  return next;
}

/**
 * Estimate a *resting* baseline from a whole session's accumulated samples.
 *
 * Averaging everything is wrong: the reference must represent rest, not the mean
 * of all states, or the classifier loses its ability to flag stress. Rest, for
 * HRV, is where RMSSD is high and HR is low — so we take the low-HR slice of the
 * data (HR <= the chosen quantile) and report the median RMSSD/HR of that
 * cluster. This generalises the per-session rest-gate to the full history.
 *
 * @param {{rmssd:number, hr:number}[]} samples accumulated readings
 * @returns {{rmssd:number, hr:number, n:number}|null} null if no solid cluster
 */
function restClusterBaseline(samples, { hrQuantile = 0.25, minCluster = 60 } = {}) {
  const valid = samples.filter((s) => s && s.rmssd != null && s.hr != null);
  if (!valid.length) return null;
  const hrThreshold = quantile(valid.map((s) => s.hr), hrQuantile);
  if (hrThreshold == null) return null;
  const cluster = valid.filter((s) => s.hr <= hrThreshold);
  if (cluster.length < minCluster) return null;
  return {
    rmssd: median(cluster.map((s) => s.rmssd)),
    hr: median(cluster.map((s) => s.hr)),
    n: cluster.length,
  };
}

/**
 * Per-session baseline: collects valid (rmssd, hr) readings after connection and
 * freezes their medians as the reference. Optionally gated so only "settled"
 * readings (HR close to the recent median) feed the baseline, biasing it toward
 * calm periods rather than the transient right after donning the strap.
 * Supports JSON (de)serialisation for cross-session persistence.
 */
class Baseline {
  constructor({ samples = 60, restGate = true, restHrTol = 6, adaptive = null } = {}) {
    this.need = samples;
    this.restGate = restGate;
    this.restHrTol = restHrTol; // bpm: how close to the recent median HR counts as "settled"
    this.rmssd = [];
    this.hr = [];
    this.recentHr = []; // short trailing buffer for the rest gate
    this.frozen = null; // { rmssd, hr, n, savedAt, adaptedAt? }

    // Optional adaptive re-baselining: once the initial baseline is frozen, keep
    // accumulating the whole session and periodically nudge the reference toward
    // the resting cluster of all the data so far (see restClusterBaseline). The
    // move is EMA-smoothed and step-capped so state labels do not jump.
    this.adaptive = adaptive
      ? {
          intervalMs: adaptive.intervalMs ?? 15 * 60 * 1000, // recompute cadence
          minSamples: adaptive.minSamples ?? 30 * 60, // warm-up before first adapt (~30 min @1Hz)
          alpha: adaptive.alpha ?? 0.3, // EMA weight toward the new estimate
          maxStep: adaptive.maxStep ?? 0.2, // cap on relative move per update
          histCap: adaptive.histCap ?? 6 * 60 * 60, // ring-buffer cap (~6 h @1Hz)
          hrQuantile: adaptive.hrQuantile ?? 0.25,
          minCluster: adaptive.minCluster ?? 60,
        }
      : null;
    this.history = []; // [{rmssd, hr}] full-session buffer, only when adaptive
    this.lastAdaptMs = 0;
    this.adaptedAt = null; // ms timestamp of the last adaptive update, if any
  }

  add(rmssd, hr) {
    if (rmssd == null || hr == null) return;

    // Always record valid readings so the resting cluster can be recomputed from
    // the whole session — both for adaptive re-baselining and the manual
    // "re-derive from the full session" action (refreezeFromHistory). The rest
    // gate below only shapes the *initial* freeze; restClusterBaseline does its
    // own low-HR selection over this history.
    this.history.push({ rmssd, hr });
    const histCap = this.adaptive ? this.adaptive.histCap : 6 * 60 * 60; // ~6 h @1Hz
    if (this.history.length > histCap) this.history.shift();

    if (!this.frozen) {
      // Rest gate: skip samples taken during an HR transient (just sat down,
      // moved, talked) so the reference reflects a settled state.
      this.recentHr.push(hr);
      if (this.recentHr.length > 10) this.recentHr.shift();
      let gated = false;
      if (this.restGate && this.recentHr.length >= 5) {
        const med = median(this.recentHr);
        if (med != null && Math.abs(hr - med) > this.restHrTol) gated = true;
      }
      if (!gated) {
        this.rmssd.push(rmssd);
        this.hr.push(hr);
        if (this.rmssd.length >= this.need) {
          this.frozen = {
            rmssd: median(this.rmssd), hr: median(this.hr),
            n: this.rmssd.length, savedAt: Date.now(),
          };
        }
      }
    }

    this._maybeAdapt();
  }

  /**
   * Periodically refine an already-frozen baseline toward the resting cluster of
   * the whole session. EMA-smoothed and step-capped so the reference drifts
   * gently instead of snapping. Returns true if the baseline was updated.
   */
  _maybeAdapt() {
    const a = this.adaptive;
    if (!a || !this.frozen) return false; // only refine an established baseline
    if (this.history.length < a.minSamples) return false;
    const now = Date.now();
    if (this.lastAdaptMs && now - this.lastAdaptMs < a.intervalMs) return false;
    this.lastAdaptMs = now;

    const est = restClusterBaseline(this.history, {
      hrQuantile: a.hrQuantile, minCluster: a.minCluster,
    });
    if (!est) return false; // no solid resting cluster yet — leave baseline as is

    this.frozen = {
      rmssd: emaStep(this.frozen.rmssd, est.rmssd, a.alpha, a.maxStep),
      hr: emaStep(this.frozen.hr, est.hr, a.alpha, a.maxStep),
      n: est.n,
      savedAt: now,
      adaptedAt: now,
    };
    this.adaptedAt = now;
    return true;
  }

  /**
   * Re-derive the baseline from the resting cluster of the WHOLE session so far
   * (the low-HR slice of every reading collected — see restClusterBaseline) and
   * freeze it immediately. Unlike reset(), which discards the reference and
   * recalibrates over the next ~minute, this uses the data already gathered, so
   * the refreshed baseline applies at once. Returns the new frozen baseline, or
   * null if there is not yet a solid resting cluster.
   */
  refreezeFromHistory({ hrQuantile = 0.25, minCluster = 30, extra = null } = {}) {
    // `extra` = persisted whole-history samples (past sessions / CSV), merged
    // with this session's in-memory history so "全期間" truly spans restarts
    // rather than just the current run's ring buffer.
    const all = extra && extra.length ? extra.concat(this.history) : this.history;
    const est = restClusterBaseline(all, { hrQuantile, minCluster });
    if (!est) return null;
    this.frozen = { rmssd: est.rmssd, hr: est.hr, n: est.n, savedAt: Date.now() };
    // The in-progress (reset-style) accumulators are now irrelevant; clear them
    // so progress() reports complete and a later reset() starts clean.
    this.rmssd = [];
    this.hr = [];
    this.recentHr = [];
    return this.frozen;
  }

  /**
   * Override the baseline with user-supplied values (e.g. a personal resting
   * RMSSD/HR known from prior data). Freezes immediately and clears the
   * in-progress accumulators; `manual: true` marks it so it is treated as a
   * settled, deliberate reference rather than an auto-calibrated one.
   */
  setManual(rmssd, hr) {
    if (!(rmssd > 0 && hr > 0)) return null;
    this.frozen = { rmssd, hr, n: 0, savedAt: Date.now(), manual: true };
    this.rmssd = [];
    this.hr = [];
    this.recentHr = [];
    this.lastAdaptMs = Date.now(); // defer any adaptive nudge after a manual set
    this.adaptedAt = null;
    return this.frozen;
  }

  reset() {
    this.rmssd = [];
    this.hr = [];
    this.recentHr = [];
    this.frozen = null;
    this.history = [];
    this.lastAdaptMs = 0;
    this.adaptedAt = null;
  }

  get() {
    return this.frozen;
  }

  /** 0..1 calibration progress. */
  progress() {
    return this.frozen ? 1 : this.rmssd.length / this.need;
  }

  toJSON() {
    return this.frozen ? { ...this.frozen } : null;
  }

  /** Restore a previously frozen baseline (e.g. from disk). */
  loadFrozen(obj) {
    if (obj && obj.rmssd != null && obj.hr != null) {
      this.frozen = { rmssd: obj.rmssd, hr: obj.hr, n: obj.n ?? 0, savedAt: obj.savedAt ?? Date.now(),
        ...(obj.manual ? { manual: true } : {}) };
    }
  }
}

// ln thresholds for RMSSD change vs baseline (ratio -> natural log).
const LN = {
  bigDrop: Math.log(0.55), // strong HRV suppression
  drop: Math.log(0.8), // moderate suppression
  upSlight: Math.log(1.1), // slightly above baseline
  up: Math.log(1.25), // clearly above baseline
};

// Stateless raw classification from ln(RMSSD) delta + HR delta, with HR
// dead-bands so a couple of bpm of noise does not flip the label.
function classifyRaw(rmssd, hr, base) {
  if (rmssd == null || hr == null) {
    return { label: '計測待ち', tone: 'wait', arousal: null, recovery: null, load: null, detail: '心拍データ待機中' };
  }
  if (!base) {
    return { label: 'キャリブレーション中', tone: 'wait', arousal: null, recovery: null, load: null, detail: '基準値を計測中…' };
  }

  const dLn = base.rmssd > 0 && rmssd > 0 ? Math.log(rmssd / base.rmssd) : 0; // <0 = HRV down
  const hrDelta = hr - base.hr; // >0 = HR up vs baseline

  // Arousal score: HR above baseline and HRV below baseline both raise it.
  const arousal = Math.max(0, Math.min(100, Math.round(50 + hrDelta * 2.2 - dLn * 35)));

  // Two near-independent axes for the autonomic map (each clamped to -1..+1):
  //  - recovery: vagally-mediated HRV vs baseline. RMSSD/SD1 is the established
  //    time-domain marker of cardiac vagal (parasympathetic) modulation
  //    (Shaffer & Ginsberg 2017; Laborde et al. 2017). Right = more restored.
  //  - load: physiological load / arousal, driven by HR vs baseline. There is no
  //    clean time-domain marker of *sympathetic* activity, so this is framed as
  //    load/arousal, not "sympathetic tone" (Billman 2013; Goldstein et al. 2011).
  const recovery = Math.max(-1, Math.min(1, dLn / 0.7)); // ln(2)≈0.69 → RMSSD x2 hits the edge
  const load = Math.max(-1, Math.min(1, hrDelta / 12)); // +12 bpm = high-load threshold

  let label, tone, detail;
  if (hrDelta >= 12 || dLn <= LN.bigDrop) {
    label = '高負荷・興奮';
    tone = 'high';
    detail = '心拍が大きく上昇 / HRVが大きく低下。強い負荷や興奮の状態。';
  } else if (dLn <= LN.drop && hrDelta >= 5) {
    label = 'ストレス・緊張↑';
    tone = 'tense';
    detail = 'HRV低下＋心拍上昇。負荷・緊張がかかっている可能性。';
  } else if (hrDelta >= 4 && dLn <= LN.upSlight) {
    label = '集中';
    tone = 'focus';
    detail = '軽い覚醒。タスクに没頭しているフロー寄りの状態。';
  } else if (dLn >= LN.up && hrDelta <= -2) {
    label = 'リラックス・回復';
    tone = 'calm';
    detail = 'HRV上昇＋心拍低下。迷走神経（副交感）優位で回復している状態。';
  } else if (dLn >= LN.upSlight && hrDelta <= 2) {
    label = '回復傾向';
    tone = 'recover';
    detail = 'HRVが基準よりやや高く心拍は基準付近。落ち着いてきている傾向。';
  } else {
    label = '平常・安定';
    tone = 'neutral';
    detail = '基準値の近く。安定した状態。';
  }

  return { label, tone, arousal, recovery, load, detail };
}

/**
 * Hysteresis wrapper around classifyRaw: a new label must persist for at least
 * `minDwellMs` before it is committed, so the displayed state does not flicker.
 * "wait" states (no data / calibrating) bypass the dwell and switch instantly.
 */
class StateClassifier {
  constructor({ minDwellMs = 45000 } = {}) {
    this.minDwellMs = minDwellMs;
    this.current = null; // last committed { label, tone, arousal, detail }
    this.pending = null; // { label, sinceMs }
  }

  update(rmssd, hr, base, nowMs = Date.now()) {
    const raw = classifyRaw(rmssd, hr, base);

    // Wait/calibration states are not subject to hysteresis.
    if (raw.tone === 'wait' || this.current == null || this.current.tone === 'wait') {
      this.current = raw;
      this.pending = null;
      return raw;
    }

    if (raw.label === this.current.label) {
      // Same state: refresh dynamic fields (arousal/detail), clear any pending.
      this.current = { ...raw };
      this.pending = null;
      return this.current;
    }

    // Candidate differs: require it to hold for minDwellMs before switching.
    if (!this.pending || this.pending.label !== raw.label) {
      this.pending = { label: raw.label, sinceMs: nowMs, raw };
    } else {
      this.pending.raw = raw;
      if (nowMs - this.pending.sinceMs >= this.minDwellMs) {
        this.current = { ...raw };
        this.pending = null;
        return this.current;
      }
    }
    // Hold the current label but keep the live scores (arousal/recovery/load) responsive.
    return { ...this.current, arousal: raw.arousal, recovery: raw.recovery, load: raw.load };
  }
}

/** Back-compat stateless classifier (no hysteresis). */
function classifyState(rmssd, hr, base) {
  return classifyRaw(rmssd, hr, base);
}

module.exports = { Baseline, StateClassifier, classifyState, classifyRaw, median, quantile, restClusterBaseline, emaStep };
