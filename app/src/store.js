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
