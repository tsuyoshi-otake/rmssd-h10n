'use strict';

/**
 * Sliding-window RMSSD (root mean square of successive differences) calculator.
 *
 * Feed it RR intervals (ms) as they are derived from detected R peaks. It keeps
 * a time-bounded window of accepted intervals, rejects artifacts, and computes
 * RMSSD / SDNN / mean HR over the window on demand.
 *
 * Artifact rejection (Kubios-style automatic correction, simplified for the
 * streaming case): a beat is rejected when it is physiologically implausible
 * (absolute range), deviates too far from the LOCAL MEDIAN of recent accepted
 * beats, or (during warm-up) jumps too far from the previous beat. The
 * local-median test is the key improvement over a bare previous-beat ratio: in
 * a low-RMSSD subject the RR series is nearly flat, so a single ectopic/missed
 * beat stands out clearly against the median, whereas a "30 % of previous" test
 * waves it through (640 ms x 0.3 = +-190 ms is huge next to a 5 ms RMSSD).
 */
function median(arr) {
  if (!arr.length) return null;
  const s = [...arr].sort((a, b) => a - b);
  const m = s.length >> 1;
  return s.length % 2 ? s[m] : (s[m - 1] + s[m]) / 2;
}

class RmssdWindow {
  /**
   * @param {object} opts
   * @param {number} opts.windowMs window length in ms (default 30 s)
   * @param {number} opts.minRr minimum plausible RR (ms)
   * @param {number} opts.maxRr maximum plausible RR (ms)
   * @param {number} opts.maxRelJump warm-up: reject RR differing > this fraction from the previous accepted RR
   * @param {number} opts.localTol reject RR deviating > this fraction from the local median
   * @param {number} opts.localN number of recent accepted beats forming the local median
   * @param {number} opts.emaTau RMSSD EMA time constant in seconds (for state classification)
   */
  constructor({
    windowMs = 30000, minRr = 300, maxRr = 2000,
    maxRelJump = 0.25, localTol = 0.25, localN = 7, emaTau = 20,
  } = {}) {
    this.windowMs = windowMs;
    this.minRr = minRr;
    this.maxRr = maxRr;
    this.maxRelJump = maxRelJump;
    this.localTol = localTol;
    this.localN = localN;
    this.emaAlpha = 1 / (emaTau + 1); // ~time constant at the 1 Hz reporting rate
    this.entries = []; // { tMs, rr }
    this.recent = []; // last N accepted RR (ms) for the local-median test
    this.lastAccepted = null;
    this.rejected = 0; // beats rejected as artifacts ("corrected")
    this.rmssdEma = null; // smoothed RMSSD for stable state classification
  }

  /**
   * Add an RR interval observed at absolute time tMs (ms since session start).
   * @returns {boolean} true if accepted, false if rejected as an artifact
   */
  add(tMs, rr) {
    // 1. Physiologically implausible -> reject outright.
    if (rr < this.minRr || rr > this.maxRr) {
      this.rejected++;
      return false;
    }
    // 2. Local-median deviation (once we have a few accepted beats to trust).
    if (this.recent.length >= 3) {
      const med = median(this.recent);
      if (med > 0 && Math.abs(rr - med) / med > this.localTol) {
        this.rejected++;
        return false;
      }
    } else if (this.lastAccepted != null) {
      // 3. Warm-up: fall back to a previous-beat jump test.
      if (Math.abs(rr - this.lastAccepted) / this.lastAccepted > this.maxRelJump) {
        this.rejected++;
        return false;
      }
    }

    this.entries.push({ tMs, rr });
    this.lastAccepted = rr;
    this.recent.push(rr);
    if (this.recent.length > this.localN) this.recent.shift();
    this._evict(tMs);
    return true;
  }

  _evict(nowMs) {
    const cutoff = nowMs - this.windowMs;
    let i = 0;
    while (i < this.entries.length && this.entries[i].tMs < cutoff) i++;
    if (i > 0) this.entries.splice(0, i);
  }

  /**
   * @param {number} [nowMs] current session time; when provided, stale entries
   *   are evicted first so a silent/rejected period does not keep reporting
   *   old RMSSD values past the window.
   * @returns {{ rmssd, rmssdEma, hr, sdnn, count, corrected }}
   */
  compute(nowMs) {
    if (nowMs != null) this._evict(nowMs);
    const rrs = this.entries.map((e) => e.rr);
    const count = rrs.length;
    if (count < 2) {
      return {
        rmssd: null, rmssdEma: this.rmssdEma, hr: count === 1 ? 60000 / rrs[0] : null,
        sdnn: null, count, corrected: this.rejected,
      };
    }

    let sumSqDiff = 0;
    for (let i = 1; i < count; i++) {
      const d = rrs[i] - rrs[i - 1];
      sumSqDiff += d * d;
    }
    const rmssd = Math.sqrt(sumSqDiff / (count - 1));

    const mean = rrs.reduce((a, b) => a + b, 0) / count;
    const variance = rrs.reduce((a, b) => a + (b - mean) * (b - mean), 0) / count;
    const sdnn = Math.sqrt(variance);
    const hr = 60000 / mean;

    // Exponentially-smoothed RMSSD: low-RMSSD windows are noisy second-to-second,
    // so the state classifier reads this slower signal rather than the raw value.
    this.rmssdEma = this.rmssdEma == null ? rmssd : this.emaAlpha * rmssd + (1 - this.emaAlpha) * this.rmssdEma;

    return { rmssd, rmssdEma: this.rmssdEma, hr, sdnn, count, corrected: this.rejected };
  }
}

module.exports = { RmssdWindow, median };
