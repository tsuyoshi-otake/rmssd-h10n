'use strict';

// App entry: wires the shared Monitor (1 Hz HRV pipeline) to a data source and
// exposes a small window.RmssdBridge the dashboard UI talks to instead of the
// desktop WebSocket / REST API. There are three modes:
//   - Android device  : native BLE  + hosts a local server (broadcasts live data)
//   - browser ?sim=1  : synthetic RR stream (development without hardware)
//   - browser (remote): a PC viewing the phone's server — subscribes over WS and
//                       pulls the initial localStorage snapshot over HTTP.
import { Capacitor, registerPlugin } from '@capacitor/core';
import { ScreenOrientation } from '@capacitor/screen-orientation';
import { Share } from '@capacitor/share';
import { Filesystem, Directory, Encoding } from '@capacitor/filesystem';
import { Monitor } from './monitor.js';
import { BleHr } from './ble-hr.js';
import { localIso } from '../../src/time.js';

const LocalServer = registerPlugin('LocalServer');

const platform = Capacitor.getPlatform();
const isAndroid = platform === 'android';
const isSim = platform === 'web' && new URLSearchParams(location.search).has('sim');
const isRemote = platform === 'web' && !isSim; // a PC browser served by the phone

const statusListeners = [];
const pointListeners = [];

// Relay a live frame to any connected remote browsers (Android host only).
function hostBroadcast(type, data) {
  if (!isAndroid) return;
  try { LocalServer.broadcast({ data: JSON.stringify({ type, data }) }); } catch (_) {}
}

const monitor = new Monitor({
  windowSec: 30,
  user: 1,
  onStatus: (s) => { for (const cb of statusListeners) cb(s); hostBroadcast('status', s); },
  onPoint: (p) => { for (const cb of pointListeners) cb(p); hostBroadcast('point', p); },
});

let ble = null;

// Synthetic RR generator for the browser — mirrors index.js --simulate.
function startSimulate() {
  monitor.setConnected(true);
  let tMs = 0;
  const baseRr = 1000;
  const tick = () => {
    const resp = 40 * Math.sin((2 * Math.PI * tMs) / 5000);
    const noise = (Math.random() - 0.5) * 30;
    const rr = baseRr + resp + noise;
    tMs += rr;
    monitor.onRr(rr);
    setTimeout(tick, rr);
  };
  tick();
}

async function startBle() {
  ble = new BleHr({
    // The H10's address is known from pairing; connect directly (no scan/dialog,
    // which the H10's adverts defeat). Falls back to the picker if it ever fails.
    preferredId: '24:AC:AC:1B:54:C8',
    onRr: (rr) => monitor.onRr(rr),
    onHr: (hr) => monitor.onHr(hr),
    onConnected: (v) => monitor.setConnected(v),
    log: (m) => console.log('[BLE]', m),
  });
  try {
    await ble.start();
  } catch (e) {
    const msg = (e && e.message) || String(e);
    console.error('[BLE] failed to start:', msg);
    // Surface the failure on the connection line via a status push.
    const s = monitor.getStatus() || {};
    for (const cb of statusListeners) cb({ ...s, connected: false, bleError: msg });
  }
}

// Screen-orientation control for the dashboard's 画面向き toggle. 'auto' returns
// to the device's rotation setting; 'portrait'/'landscape' lock it. Failures
// (e.g. a plain browser where lock needs fullscreen) are non-fatal.
async function applyOrientation(mode) {
  try {
    if (mode === 'portrait') await ScreenOrientation.lock({ orientation: 'portrait' });
    else if (mode === 'landscape') await ScreenOrientation.lock({ orientation: 'landscape' });
    else await ScreenOrientation.unlock();
  } catch (e) {
    console.warn('[orientation]', (e && e.message) || e);
  }
}

// Export the dashboard's CSV files. On Android each is written to the app cache
// dir and handed to the system share sheet (Gmail/Drive/Files…), which is the
// only reliable way out of a WebView — anchor downloads don't work there. In a
// plain browser (incl. remote) we fall back to a per-file download.
async function exportFiles(files) {
  if (Capacitor.getPlatform() === 'web') {
    for (const f of files) {
      const url = URL.createObjectURL(new Blob([f.content], { type: f.mime || 'text/csv' }));
      const a = document.createElement('a');
      a.href = url; a.download = f.name; a.click();
      setTimeout(() => URL.revokeObjectURL(url), 1000);
    }
    return { ok: true, method: 'download', count: files.length };
  }
  const uris = [];
  for (const f of files) {
    const w = await Filesystem.writeFile({
      path: f.name, data: f.content, directory: Directory.Cache, encoding: Encoding.UTF8,
    });
    uris.push(w.uri);
  }
  await Share.share({ title: 'RMSSD エクスポート', files: uris });
  return { ok: true, method: 'share', count: files.length };
}

// ---- host bridge: Android device, or a browser with ?sim=1 ----------------
const hostBridge = {
  onStatus: (cb) => { statusListeners.push(cb); },
  onPoint: (cb) => { pointListeners.push(cb); },
  switchUser: (n) => monitor.switchUser(n),
  resetBaseline: () => monitor.resetBaseline(),
  refreezeBaseline: () => monitor.refreezeFromHistory(), // { applied, baseline? }
  setBaseline: (rmssd, hr) => monitor.setBaseline(rmssd, hr), // manual override -> { ok, baseline? }
  setOrientation: (mode) => applyOrientation(mode), // 'auto' | 'portrait' | 'landscape'
  exportFiles: (files) => exportFiles(files), // share/download CSVs -> { method, count }
  now: () => localIso(), // JST ISO timestamp, matching point.t — used for activity logging
  iso: (ms) => localIso(new Date(ms)), // arbitrary epoch ms → JST ISO (activity time edits)
  isRemote: false,
  canServe: isAndroid, // only the real device can host the local server (not ?sim)
  // Local-server controls (Android host). PC browsers reach the dashboard at the
  // returned URL over the same Wi-Fi.
  server: {
    start: (port) => LocalServer.start({ port: port || 8080 }),
    stop: () => LocalServer.stop(),
    info: () => LocalServer.getInfo(),
  },
  // Hand the current localStorage snapshot to the server for GET /api/snapshot.
  setSnapshot: (json) => { try { return LocalServer.setSnapshot({ data: json }); } catch (_) {} },
  start: () => {
    monitor.start();
    const s = monitor.getStatus();
    if (s) for (const cb of statusListeners) cb(s);
    if (platform === 'web') startSimulate(); else startBle();
  },
};

// ---- remote bridge: a PC browser served by the phone ----------------------
// Subscribes to live status/point over WebSocket and seeds history/trend/
// activities from the phone's snapshot. View-only: control actions are no-ops
// (the UI hides their buttons in remote mode).
function makeRemoteBridge() {
  let ws = null;
  const connect = () => {
    try {
      ws = new WebSocket(`ws://${location.host}/`);
      ws.onmessage = (ev) => {
        try {
          const m = JSON.parse(ev.data);
          if (m.type === 'status') { for (const cb of statusListeners) cb(m.data); }
          else if (m.type === 'point') { for (const cb of pointListeners) cb(m.data); }
        } catch (_) {}
      };
      ws.onclose = () => { ws = null; setTimeout(connect, 2000); }; // auto-reconnect
      ws.onerror = () => { try { ws.close(); } catch (_) {} };
    } catch (_) { setTimeout(connect, 2000); }
  };
  const applySnapshot = (snap) => {
    if (!snap || !snap.user) return;
    const u = snap.user;
    try {
      if (snap.history) localStorage.setItem(`rmssd-h10n.history.v1.u${u}`, JSON.stringify(snap.history));
      if (snap.trend) localStorage.setItem(`rmssd-h10n.trend.v1.u${u}`, JSON.stringify(snap.trend));
      if (snap.activities) localStorage.setItem(`rmssd-h10n.activities.v1.u${u}`, JSON.stringify(snap.activities));
    } catch (_) {}
    if (window.__reloadFromStorage) window.__reloadFromStorage(u);
  };
  const noop = () => {};
  return {
    onStatus: (cb) => { statusListeners.push(cb); },
    onPoint: (cb) => { pointListeners.push(cb); },
    switchUser: noop,
    resetBaseline: noop,
    refreezeBaseline: () => ({ ok: false, applied: false }),
    setBaseline: () => ({ ok: false }),
    setOrientation: noop,
    exportFiles: (files) => exportFiles(files), // browser download
    now: () => localIso(),
    iso: (ms) => localIso(new Date(ms)),
    isRemote: true,
    server: null,
    setSnapshot: noop,
    start: async () => {
      try { applySnapshot(await fetch('/api/snapshot').then((r) => r.json())); } catch (_) {}
      connect();
    },
  };
}

// Bridge consumed by the inline dashboard script (see www/index.html).
window.RmssdBridge = isRemote ? makeRemoteBridge() : hostBridge;
