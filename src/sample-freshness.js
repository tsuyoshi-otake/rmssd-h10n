'use strict';

const { performance } = require('node:perf_hooks');

/** Receive time and accepted-sample time differ when the artifact filter rejects RR. */
class SampleFreshness {
  constructor({ now = () => performance.now(), wall = Date.now, freshMs = 5000, gapMs = 10000 } = {}) {
    Object.assign(this, { now, wall, freshMs, gapMs });
    this.reset();
  }
  reset() { this.receivedAt = this.acceptedAt = this.hrAt = this.sampleAt = null; }
  receiveRr(rr) {
    if (!Number.isFinite(rr) || rr < 300 || rr > 2000) return false;
    const now = this.now();
    const gap = this.receivedAt != null && now - this.receivedAt >= this.gapMs;
    this.receivedAt = now;
    return gap;
  }
  acceptRr() { this.acceptedAt = this.now(); this.sampleAt = this.wall(); }
  receiveHr(hr) { if (Number.isFinite(hr) && hr > 0) this.hrAt = this.now(); }
  snapshot(connected) {
    const now = this.now();
    const age = this.acceptedAt == null ? null : Math.max(0, now - this.acceptedAt);
    return {
      dataFresh: connected && age != null && age < this.freshMs,
      hrFresh: connected && this.hrAt != null && now - this.hrAt < this.freshMs,
      sampleAt: this.sampleAt,
      sampleAgeMs: age == null ? null : Math.round(age),
    };
  }
  windowNow(lastPeakMs) {
    return lastPeakMs == null ? undefined : lastPeakMs + (this.receivedAt == null ? 0 : Math.max(0, this.now() - this.receivedAt));
  }
}

module.exports = { SampleFreshness };
