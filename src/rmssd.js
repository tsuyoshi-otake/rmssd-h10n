'use strict';

/**
 * Sliding-window RMSSD (root mean square of successive differences) calculator.
 *
 * Feed it RR intervals (ms) as they are derived from detected R peaks. It keeps
 * a time-bounded window of accepted intervals, rejects obvious artifacts, and
 * computes RMSSD plus mean HR over the window on demand.
 */
class RmssdWindow {
  /**
   * @param {object} opts
   * @param {number} opts.windowMs window length in ms (default 30 s)
   * @param {number} opts.minRr minimum plausible RR (ms)
   * @param {number} opts.maxRr maximum plausible RR (ms)
   * @param {number} opts.maxRelJump reject RR differing > this fraction from the previous accepted RR
   */
  constructor({ windowMs = 30000, minRr = 300, maxRr = 2000, maxRelJump = 0.3 } = {}) {
    this.windowMs = windowMs;
    this.minRr = minRr;
    this.maxRr = maxRr;
    this.maxRelJump = maxRelJump;
    this.entries = []; // { tMs, rr }
    this.lastAccepted = null;
    this.rejected = 0;
  }

  /**
   * Add an RR interval observed at absolute time tMs (ms since session start).
   * @returns {boolean} true if accepted, false if rejected as an artifact
   */
  add(tMs, rr) {
    if (rr < this.minRr || rr > this.maxRr) {
      this.rejected++;
      return false;
    }
    if (this.lastAccepted != null) {
      const rel = Math.abs(rr - this.lastAccepted) / this.lastAccepted;
      if (rel > this.maxRelJump) {
        this.rejected++;
        return false;
      }
    }
    this.entries.push({ tMs, rr });
    this.lastAccepted = rr;
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
   * @returns {{ rmssd: number|null, hr: number|null, sdnn: number|null, count: number }}
   */
  compute(nowMs) {
    if (nowMs != null) this._evict(nowMs);
    const rrs = this.entries.map((e) => e.rr);
    const count = rrs.length;
    if (count < 2) {
      return { rmssd: null, hr: count === 1 ? 60000 / rrs[0] : null, sdnn: null, count };
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

    return { rmssd, hr, sdnn, count };
  }
}

module.exports = { RmssdWindow };
