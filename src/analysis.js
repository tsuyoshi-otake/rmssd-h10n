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

/**
 * Per-session baseline: collects valid (rmssd, hr) readings after connection and
 * freezes their medians as the reference. Optionally gated so only "settled"
 * readings (HR close to the recent median) feed the baseline, biasing it toward
 * calm periods rather than the transient right after donning the strap.
 * Supports JSON (de)serialisation for cross-session persistence.
 */
class Baseline {
  constructor({ samples = 60, restGate = true, restHrTol = 6 } = {}) {
    this.need = samples;
    this.restGate = restGate;
    this.restHrTol = restHrTol; // bpm: how close to the recent median HR counts as "settled"
    this.rmssd = [];
    this.hr = [];
    this.recentHr = []; // short trailing buffer for the rest gate
    this.frozen = null; // { rmssd, hr, n, savedAt }
  }

  add(rmssd, hr) {
    if (this.frozen || rmssd == null || hr == null) return;
    // Rest gate: skip samples taken during an HR transient (just sat down,
    // moved, talked) so the reference reflects a settled state.
    this.recentHr.push(hr);
    if (this.recentHr.length > 10) this.recentHr.shift();
    if (this.restGate && this.recentHr.length >= 5) {
      const med = median(this.recentHr);
      if (med != null && Math.abs(hr - med) > this.restHrTol) return;
    }
    this.rmssd.push(rmssd);
    this.hr.push(hr);
    if (this.rmssd.length >= this.need) {
      this.frozen = {
        rmssd: median(this.rmssd), hr: median(this.hr),
        n: this.rmssd.length, savedAt: Date.now(),
      };
    }
  }

  reset() {
    this.rmssd = [];
    this.hr = [];
    this.recentHr = [];
    this.frozen = null;
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
      this.frozen = { rmssd: obj.rmssd, hr: obj.hr, n: obj.n ?? 0, savedAt: obj.savedAt ?? Date.now() };
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
    return { label: '計測待ち', tone: 'wait', arousal: null, detail: '心拍データ待機中' };
  }
  if (!base) {
    return { label: 'キャリブレーション中', tone: 'wait', arousal: null, detail: '基準値を計測中…' };
  }

  const dLn = base.rmssd > 0 && rmssd > 0 ? Math.log(rmssd / base.rmssd) : 0; // <0 = HRV down
  const hrDelta = hr - base.hr; // >0 = HR up vs baseline

  // Arousal score: HR above baseline and HRV below baseline both raise it.
  const arousal = Math.max(0, Math.min(100, Math.round(50 + hrDelta * 2.2 - dLn * 35)));

  let label, tone, detail;
  if (hrDelta >= 12 || dLn <= LN.bigDrop) {
    label = '高負荷・興奮';
    tone = 'high';
    detail = '心拍が大きく上昇 / HRVが大きく低下。強い負荷や興奮の状態。';
  } else if (dLn <= LN.drop && hrDelta >= 5) {
    label = 'ストレス・緊張↑';
    tone = 'tense';
    detail = '交感神経優位。緊張やプレッシャーがかかっている可能性。';
  } else if (hrDelta >= 4 && dLn <= LN.upSlight) {
    label = '集中';
    tone = 'focus';
    detail = '軽い覚醒。タスクに没頭しているフロー寄りの状態。';
  } else if (dLn >= LN.up && hrDelta <= -2) {
    label = 'リラックス・回復';
    tone = 'calm';
    detail = '副交感神経優位。落ち着いて回復している状態。';
  } else if (dLn >= LN.upSlight && hrDelta <= 2) {
    label = '回復傾向';
    tone = 'recover';
    detail = 'HRVが基準よりやや高く心拍は基準付近。落ち着いてきている傾向。';
  } else {
    label = '平常・安定';
    tone = 'neutral';
    detail = '基準値の近く。安定した状態。';
  }

  return { label, tone, arousal, detail };
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
    // Hold the current label but keep arousal responsive.
    return { ...this.current, arousal: raw.arousal };
  }
}

/** Back-compat stateless classifier (no hysteresis). */
function classifyState(rmssd, hr, base) {
  return classifyRaw(rmssd, hr, base);
}

module.exports = { Baseline, StateClassifier, classifyState, classifyRaw, median };
