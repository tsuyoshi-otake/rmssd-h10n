'use strict';

// Per-user resting baseline persistence for the Android app. The Capacitor
// WebView keeps localStorage in the app's private data, so a baseline frozen in
// one session is reused on the next — mirroring the desktop tool's
// baseline-u<n>.json + "reuse if < 24 h old" behaviour. Kept per user, like the
// dashboard's history/trend localStorage keys.
const KEY = (user) => `rmssd-h10n.baseline.v1.u${user}`;
const MAX_AGE_MS = 24 * 3600 * 1000;

export function loadBaseline(user) {
  try {
    const raw = localStorage.getItem(KEY(user));
    if (!raw) return null;
    const saved = JSON.parse(raw);
    if (saved && saved.rmssd != null && saved.hr != null &&
        Date.now() - (saved.savedAt ?? 0) < MAX_AGE_MS) {
      return saved;
    }
  } catch (_) { /* missing/invalid -> recalibrate fresh */ }
  return null;
}

export function saveBaseline(user, obj) {
  if (!obj) return;
  try { localStorage.setItem(KEY(user), JSON.stringify(obj)); } catch (_) {}
}

// Whole-history downsampled samples ({rmssd, hr, t}) backing the "全期間で基準を
//取り直す" action. Unlike Baseline.history (memory-only, ~6 h ring buffer), this
// is PERSISTED, so the resting baseline can be re-derived from data accumulated
// ACROSS restarts. Stored per user, 1-min downsampled, capped to ~14 days.
const HS_KEY = (user) => `rmssd-h10n.histsamples.v1.u${user}`;
const HS_CAP = 14 * 24 * 60; // ~14 days of 1-min samples (20160 points)

export function loadHistSamples(user) {
  try {
    const a = JSON.parse(localStorage.getItem(HS_KEY(user)) || '[]');
    return Array.isArray(a) ? a : [];
  } catch (_) { return []; }
}

export function saveHistSamples(user, arr) {
  try { localStorage.setItem(HS_KEY(user), JSON.stringify(arr.slice(-HS_CAP))); } catch (_) {}
}

// Per-user upright posture reference (the gravity vector captured while sitting
// straight). Reused within 24 h so posture is calibrated across restarts; after
// that the strap may have been re-mounted, so we recalibrate fresh.
const POS_KEY = (user) => `rmssd-h10n.posture.v1.u${user}`;

export function loadPostureRef(user) {
  try {
    const r = JSON.parse(localStorage.getItem(POS_KEY(user)) || 'null');
    if (r && r.x != null && Date.now() - (r.savedAt ?? 0) < MAX_AGE_MS) return r;
  } catch (_) {}
  return null;
}

export function savePostureRef(user, ref) {
  if (!ref) return;
  try { localStorage.setItem(POS_KEY(user), JSON.stringify(ref)); } catch (_) {}
}
