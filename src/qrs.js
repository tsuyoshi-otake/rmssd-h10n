'use strict';

/**
 * Streaming QRS (R-wave) detector based on a simplified Pan–Tompkins pipeline:
 *   baseline removal (high-pass) -> derivative -> squaring -> moving-window
 *   integration -> adaptive thresholding with a refractory period.
 *
 * Samples are fed one at a time via push(). When an R peak is confirmed the
 * onPeak(timeMs) callback fires with the peak time in milliseconds since the
 * first sample, derived from the running sample index and the sample rate.
 */
class QRSDetector {
  /**
   * @param {object} opts
   * @param {number} opts.sampleRate Hz (Polar H10 ECG = 130)
   * @param {(timeMs:number)=>void} opts.onPeak called with peak time in ms
   */
  constructor({ sampleRate = 130, onPeak = () => {} } = {}) {
    this.fs = sampleRate;
    this.onPeak = onPeak;

    this.n = 0; // running sample index

    // High-pass (baseline removal) via moving average over ~200 ms.
    this.hpWin = Math.max(1, Math.round(0.2 * this.fs));
    this.hpBuf = new Float64Array(this.hpWin);
    this.hpPos = 0;
    this.hpSum = 0;
    this.hpCount = 0;

    // Derivative needs the previous (baseline-removed) sample.
    this.prevHp = 0;

    // Moving-window integration over ~150 ms.
    this.intWin = Math.max(1, Math.round(0.15 * this.fs));
    this.intBuf = new Float64Array(this.intWin);
    this.intPos = 0;
    this.intSum = 0;

    // Peak tracking on the integrated signal (need 3 points for a local max).
    this.prevInt = 0;
    this.prevPrevInt = 0;
    this.prevIntIndex = -1;

    // Adaptive thresholds (Pan–Tompkins SPKI / NPKI on the integrated signal).
    this.spki = 0; // running estimate of signal peaks
    this.npki = 0; // running estimate of noise peaks
    this.threshold = 0;
    this.initialized = false;
    this.warmupSamples = Math.round(2 * this.fs); // 2 s warm-up to seed thresholds

    // Refractory period (~200 ms — physiological max ~300 bpm guard).
    this.refractory = Math.round(0.2 * this.fs);
    this.lastPeakIndex = -Infinity;
  }

  push(sampleUv) {
    const i = this.n++;

    // --- High-pass: subtract moving average (baseline wander removal) ---
    const oldHp = this.hpBuf[this.hpPos];
    this.hpBuf[this.hpPos] = sampleUv;
    this.hpPos = (this.hpPos + 1) % this.hpWin;
    this.hpSum += sampleUv - oldHp;
    if (this.hpCount < this.hpWin) this.hpCount++;
    const baseline = this.hpSum / this.hpCount;
    const hp = sampleUv - baseline;

    // --- Derivative ---
    const deriv = hp - this.prevHp;
    this.prevHp = hp;

    // --- Squaring ---
    const sq = deriv * deriv;

    // --- Moving-window integration ---
    const oldInt = this.intBuf[this.intPos];
    this.intBuf[this.intPos] = sq;
    this.intPos = (this.intPos + 1) % this.intWin;
    this.intSum += sq - oldInt;
    const integrated = this.intSum / this.intWin;

    // Seed thresholds during warm-up from the integrated signal statistics.
    if (!this.initialized) {
      this.spki = Math.max(this.spki, integrated);
      this.npki = this.npki === 0 ? integrated : 0.875 * this.npki + 0.125 * integrated;
      if (i >= this.warmupSamples) {
        this.threshold = this.npki + 0.25 * (this.spki - this.npki);
        this.initialized = true;
      }
      this._shift(integrated, i);
      return;
    }

    // --- Local-maximum peak detection on the integrated signal ---
    // A peak sits at prevInt (one sample back) when it exceeds both neighbours.
    if (this.prevInt > this.prevPrevInt && this.prevInt >= integrated) {
      const peakIndex = i - 1;
      const peakVal = this.prevInt;
      if (peakVal > this.threshold && peakIndex - this.lastPeakIndex > this.refractory) {
        // Signal peak: update SPKI and emit.
        this.spki = 0.125 * peakVal + 0.875 * this.spki;
        this.lastPeakIndex = peakIndex;
        const timeMs = (peakIndex / this.fs) * 1000;
        this.onPeak(timeMs);
      } else if (peakVal > this.threshold) {
        // Within refractory window — treat as part of the same beat, nudge SPKI.
        this.spki = 0.25 * peakVal + 0.75 * this.spki;
      } else {
        // Noise peak.
        this.npki = 0.125 * peakVal + 0.875 * this.npki;
      }
      this.threshold = this.npki + 0.25 * (this.spki - this.npki);
    }

    this._shift(integrated, i);
  }

  _shift(integrated, index) {
    this.prevPrevInt = this.prevInt;
    this.prevInt = integrated;
    this.prevIntIndex = index;
  }
}

module.exports = { QRSDetector };
