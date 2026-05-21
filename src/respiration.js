'use strict';

// Respiration-rate estimation from RR intervals via Respiratory Sinus
// Arrhythmia (RSA): breathing modulates heart rate (HR rises on inhale, falls
// on exhale), so the RR-interval series oscillates at the breathing frequency.
//
// Pipeline (Welch PSD):
//   tachogram (beat time, RR) -> linear-interpolate to 4 Hz -> 2nd-order
//   polynomial detrend (kills DC + slow non-linear drift that leaks into the
//   respiration band) -> split into 50%-overlapping Hann-windowed segments ->
//   average the per-segment periodograms (Welch) -> scan the search band for
//   the dominant peak.
//
// Confidence is a SIGNAL-QUALITY score, not a probability: it combines the
// peak SNR (peak power / median band power = robust noise floor) and the peak
// sharpness (narrow peak = a real oscillation, broad = noise). Welch averaging
// reduces the periodogram variance so weak RSA (low-RMSSD subjects) still
// produces a stable, separable peak — a single periodogram is too noisy there.
//
// Bands are deliberately split:
//   - SEARCH band 0.10-0.50 Hz (6-30 br/min) so we can also catch slow/fast
//     breathing, not just the classic HF band.
//   - HRV HF band 0.15-0.40 Hz is the standard vagal HF interpretation band
//     (Task Force 1996); peaks below ~0.13 Hz risk Mayer-wave (LF, ~0.1 Hz)
//     confusion and are de-weighted.
//
// Refs: Welch (1967); Task Force HRV standards (1996); Schäfer & Kratky (2008)
// "Estimation of breathing rate from RSA"; Natarajan et al. (2021) npj Digital
// Med (PSD-background + SNR gating for wearable respiration).

const DEFAULTS = {
  fs: 4, // Hz, resample grid
  // Search band starts at the classic HF lower bound 0.15 Hz (9 br/min): going
  // lower invites the Mayer wave (~0.1 Hz baroreflex oscillation), which is a
  // strong, clean LF peak that is NOT respiration — at rest/coding it would be
  // mistaken for "6 br/min". Slow paced breathing < 9/min is out of scope here.
  searchMin: 0.15, // Hz, peak-search lower bound (9 br/min)
  searchMax: 0.45, // Hz, peak-search upper bound (27 br/min, covers fast/shallow)
  hfMin: 0.15, // Hz, HRV HF band (interpretation only)
  hfMax: 0.4,
  mayerMax: 0.16, // Hz, near the LF/HF boundary -> de-weight a weak edge peak
  step: 0.005, // Hz, scan resolution (oversamples the true bin width)
  segSec: 60, // s, Welch segment length
  overlap: 0.5, // Welch segment overlap fraction
  previewSpanMs: 30000, // 30-60 s -> preview (provisional, low confidence)
  minSpanMs: 60000, // >= 60 s -> a full estimate (>=4 breaths even at 6/min)
  minEntries: 20, // and enough beats
  minSnr: 2.5, // peak must exceed the median noise floor by this factor
};

function resampleLinear(xs, ys, fs, t0, tEnd) {
  const dtMs = 1000 / fs;
  const n = Math.max(2, Math.floor((tEnd - t0) / dtMs) + 1);
  const out = new Float64Array(n);
  let j = 0;
  for (let i = 0; i < n; i++) {
    const t = t0 + i * dtMs;
    while (j < xs.length - 2 && xs[j + 1] < t) j++;
    const x0 = xs[j];
    const x1 = xs[j + 1];
    const frac = x1 > x0 ? (t - x0) / (x1 - x0) : 0;
    out[i] = ys[j] + (ys[j + 1] - ys[j]) * Math.max(0, Math.min(1, frac));
  }
  return out;
}

// 2nd-order polynomial detrend: removes DC, linear and quadratic trend so a
// slowly drifting mean HR does not leak power into the respiration band.
function detrendPoly2(y) {
  const n = y.length;
  if (n < 3) return Float64Array.from(y);
  // Normalise the abscissa to [0,1] for conditioning.
  let s0 = n, s1 = 0, s2 = 0, s3 = 0, s4 = 0;
  let b0 = 0, b1 = 0, b2 = 0;
  for (let i = 0; i < n; i++) {
    const x = i / (n - 1);
    const x2 = x * x;
    s1 += x; s2 += x2; s3 += x2 * x; s4 += x2 * x2;
    b0 += y[i]; b1 += x * y[i]; b2 += x2 * y[i];
  }
  // Solve the 3x3 normal equations [s0 s1 s2; s1 s2 s3; s2 s3 s4] [a b c] = [b0 b1 b2].
  const m = [
    [s0, s1, s2, b0],
    [s1, s2, s3, b1],
    [s2, s3, s4, b2],
  ];
  // Gaussian elimination with partial pivoting.
  for (let col = 0; col < 3; col++) {
    let piv = col;
    for (let r = col + 1; r < 3; r++) if (Math.abs(m[r][col]) > Math.abs(m[piv][col])) piv = r;
    if (Math.abs(m[piv][col]) < 1e-12) continue;
    [m[col], m[piv]] = [m[piv], m[col]];
    for (let r = 0; r < 3; r++) {
      if (r === col) continue;
      const f = m[r][col] / m[col][col];
      for (let k = col; k < 4; k++) m[r][k] -= f * m[col][k];
    }
  }
  const a = m[0][3] / (m[0][0] || 1);
  const b = m[1][3] / (m[1][1] || 1);
  const c = m[2][3] / (m[2][2] || 1);
  const out = new Float64Array(n);
  for (let i = 0; i < n; i++) {
    const x = i / (n - 1);
    out[i] = y[i] - (a + b * x + c * x * x);
  }
  return out;
}

function hannWindow(n) {
  const w = new Float64Array(n);
  if (n < 2) { w.fill(1); return w; }
  for (let i = 0; i < n; i++) w[i] = 0.5 * (1 - Math.cos((2 * Math.PI * i) / (n - 1)));
  return w;
}

// |DFT|^2 of a windowed segment at frequency f.
function powerAt(seg, win, f, fs) {
  const w = (2 * Math.PI * f) / fs;
  let re = 0, im = 0;
  for (let n = 0; n < seg.length; n++) {
    const v = seg[n] * win[n];
    re += v * Math.cos(w * n);
    im -= v * Math.sin(w * n);
  }
  return re * re + im * im;
}

function median(arr) {
  if (!arr.length) return 0;
  const s = [...arr].sort((a, b) => a - b);
  const m = s.length >> 1;
  return s.length % 2 ? s[m] : (s[m - 1] + s[m]) / 2;
}

// Welch PSD across the candidate frequencies: average per-segment periodograms.
function welchSpectrum(sig, fs, freqs, segLen, hop) {
  const N = sig.length;
  const eff = Math.min(segLen, N);
  const win = hannWindow(eff);
  const starts = [];
  for (let s = 0; s + eff <= N; s += hop) starts.push(s);
  if (starts.length === 0) starts.push(0);

  const psd = new Float64Array(freqs.length);
  for (const s0 of starts) {
    const seg = sig.subarray(s0, s0 + eff);
    for (let k = 0; k < freqs.length; k++) psd[k] += powerAt(seg, win, freqs[k], fs);
  }
  for (let k = 0; k < psd.length; k++) psd[k] /= starts.length;
  return { psd, segments: starts.length, segLen: eff };
}

/**
 * Estimate respiration rate from RR-interval entries.
 * @param {{tMs:number, rr:number}[]} entries ascending by tMs (artifact-cleaned NN)
 * @param {object} [opts]
 * @returns {{ breathsPerMin:number|null, freqHz:number|null, confidence:number,
 *             snr:number|null, peakWidthHz:number|null, valid:boolean,
 *             preview:boolean, reason:string }}
 */
function estimateRespiration(entries, opts = {}) {
  const o = { ...DEFAULTS, ...opts };
  const fail = (reason) => ({
    breathsPerMin: null, freqHz: null, confidence: 0, snr: null,
    peakWidthHz: null, valid: false, preview: false, reason,
  });

  if (!Array.isArray(entries) || entries.length < o.minEntries) return fail('insufficient_intervals');
  const xs = entries.map((e) => e.tMs);
  const ys = entries.map((e) => e.rr);
  const span = xs[xs.length - 1] - xs[0];
  if (span < o.previewSpanMs) return fail('insufficient_span');

  const grid = resampleLinear(xs, ys, o.fs, xs[0], xs[xs.length - 1]);
  if (grid.length < 8) return fail('too_few_samples');
  const sig = detrendPoly2(grid);

  // Candidate frequencies over the search band.
  const freqs = [];
  for (let f = o.searchMin; f <= o.searchMax + 1e-9; f += o.step) freqs.push(f);

  const segLen = Math.round(o.segSec * o.fs);
  const hop = Math.max(1, Math.round(segLen * (1 - o.overlap)));
  const { psd } = welchSpectrum(sig, o.fs, freqs, segLen, hop);

  // Peak + robust noise floor (median is insensitive to the peak itself).
  let peakP = -1, peakK = -1;
  for (let k = 0; k < psd.length; k++) if (psd[k] > peakP) { peakP = psd[k]; peakK = k; }
  const floor = median(psd);
  const snr = floor > 0 ? peakP / floor : 0;
  const peakF = freqs[peakK];

  if (snr < o.minSnr) return fail('no_clear_peak');
  // Boundary guard: if the maximum sits on the first/last scanned bin the true
  // dominant oscillation is OUTSIDE the band (e.g. a strong sub-0.15 Hz Mayer
  // wave clipped to the lower edge), not a respiration peak. Reject it rather
  // than report the band edge with false confidence.
  if (peakK <= 0 || peakK >= psd.length - 1) return fail('peak_at_band_edge');

  // Peak sharpness: -3 dB (half-power) width around the peak. Narrow = a real
  // oscillation; broad = smeared noise.
  const half = peakP / 2;
  let lo = peakK, hi = peakK;
  while (lo > 0 && psd[lo] > half) lo--;
  while (hi < psd.length - 1 && psd[hi] > half) hi++;
  const peakWidthHz = (hi - lo) * o.step;

  // Confidence: SNR score (median-floor based) blended with sharpness.
  const snrScore = Math.max(0, Math.min(1, (snr - o.minSnr) / (12 - o.minSnr)));
  const widthScore = Math.max(0, Math.min(1, 1 - peakWidthHz / 0.08));
  let confidence = 0.7 * snrScore + 0.3 * widthScore;
  // De-weight peaks in the Mayer/LF zone unless they are very strong.
  if (peakF < o.mayerMax && snr < 6) confidence *= 0.4;

  const preview = span < o.minSpanMs;
  if (preview) confidence *= 0.6; // provisional: not enough breaths yet

  return {
    breathsPerMin: Math.round(peakF * 60 * 10) / 10,
    freqHz: Math.round(peakF * 1000) / 1000,
    confidence: Math.round(Math.max(0, Math.min(1, confidence)) * 100) / 100,
    snr: Math.round(snr * 10) / 10,
    peakWidthHz: Math.round(peakWidthHz * 1000) / 1000,
    valid: !preview,
    preview,
    reason: preview ? 'preview' : 'ok',
  };
}

module.exports = { estimateRespiration };

// --- self-test: synthesize an RR series modulated at a known rate ---
if (require.main === module) {
  function synth({ brPerMin = 14, seconds = 120, amp = 30, baseRr = 1000, noise = 5, driftAmp = 0, driftHz = 0.02 }) {
    const fHz = brPerMin / 60;
    const entries = [];
    let t = 0;
    while (t < seconds * 1000) {
      const resp = amp * Math.sin((2 * Math.PI * fHz * t) / 1000);
      const drift = driftAmp * Math.sin((2 * Math.PI * driftHz * t) / 1000);
      const rr = baseRr + resp + drift + (Math.random() - 0.5) * noise;
      t += rr;
      entries.push({ tMs: t, rr });
    }
    return entries;
  }
  const show = (label, est) =>
    console.log(
      `${label.padEnd(34)} br=${String(est.breathsPerMin).padStart(5)} conf=${String(est.confidence).padStart(4)} ` +
      `snr=${String(est.snr).padStart(5)} w=${est.peakWidthHz} valid=${est.valid} preview=${est.preview} (${est.reason})`,
    );
  console.log('# strong RSA');
  for (const target of [12, 15, 18]) show(`target ${target} br/min (amp40)`, estimateRespiration(synth({ brPerMin: target, amp: 40, noise: 5 })));
  console.log('# weak RSA (low RMSSD), noisy, with LF drift');
  show('amp6 noise2', estimateRespiration(synth({ brPerMin: 14, amp: 6, noise: 2, baseRr: 640 })));
  show('amp3 noise2', estimateRespiration(synth({ brPerMin: 14, amp: 3, noise: 2, baseRr: 640 })));
  show('amp3 noise2 +LFdrift15', estimateRespiration(synth({ brPerMin: 14, amp: 3, noise: 2, baseRr: 640, driftAmp: 15 })));
  console.log('# gating');
  show('30-60s window (preview)', estimateRespiration(synth({ brPerMin: 15, amp: 30, seconds: 45 })));
  show('short window (15s)', estimateRespiration(synth({ brPerMin: 15, amp: 30, seconds: 15 })));
}
