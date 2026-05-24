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
const HrvNative = registerPlugin('HrvNative');

const platform = Capacitor.getPlatform();
const isAndroid = platform === 'android';
const isSim = platform === 'web' && new URLSearchParams(location.search).has('sim');
const isRemote = platform === 'web' && !isSim; // a PC browser served by the phone

const statusListeners = [];
const pointListeners = [];

// Relay a live frame to any connected remote browsers (Android host only).
// Skip the per-second JSON.stringify + bridge call entirely when no PC is
// actually viewing: poll the connected-client count from the native server at
// most every 5 s and broadcast only while at least one viewer is attached.
let wsClients = 0;
let lastClientPoll = 0;
function pollClients() {
  const now = Date.now();
  if (now - lastClientPoll < 5000) return;
  lastClientPoll = now;
  try { LocalServer.getInfo().then((i) => { wsClients = (i && i.clients) || 0; }).catch(() => {}); } catch (_) {}
}
function hostBroadcast(type, data) {
  if (!isAndroid) return;
  pollClients();
  if (wsClients <= 0) return; // no remote viewers — don't serialize every second
  try { LocalServer.broadcast({ data: JSON.stringify({ type, data }) }); } catch (_) {}
}

const monitor = new Monitor({
  windowSec: 30,
  user: 1,
  onStatus: (s) => { for (const cb of statusListeners) cb(s); hostBroadcast('status', s); },
  onPoint: (p) => { for (const cb of pointListeners) cb(p); hostBroadcast('point', p); },
});

// ---- smart alerts (Android host only) -------------------------------------
// Watch the live status for sustained sedentary / forward-lean / high-load
// conditions and raise an Android notification (works in the background via the
// foreground service). Each alert re-arms only after its condition clears, and
// re-fires once per interval while it persists. Toggleable from the dashboard
// (localStorage flag, shared with the inline UI in the same WebView).
const ALERT_FLAG = 'rmssd-h10n.alerts.v1';
function makeAlertEngine() {
  // Note: no forward-slouch alert — a chest accelerometer reads only the lean
  // *angle*, not its direction, so leaning back on a backrest is indistinguishable
  // from slouching forward; nagging on a normal reclined posture would be wrong.
  // IDs avoid MonitorService's foreground-notification id (1) so alerts never
  // overwrite the ongoing-service notification.
  const defs = {
    sedentary: { id: 101, ms: 60 * 60000, title: '座りすぎです', body: '60分以上座っています。少し立って動きましょう。' },
    load:      { id: 103, ms: 20 * 60000, title: '高負荷が続いています', body: '緊張・高負荷の状態が続いています。深呼吸や休憩を。' },
  };
  const since = { sedentary: null, load: null };
  const enabled = () => { try { return localStorage.getItem(ALERT_FLAG) !== '0'; } catch (_) { return true; } };
  return (s) => {
    if (!enabled()) { since.sedentary = since.load = null; return; }
    const now = Date.now();
    const cond = {
      sedentary: s.body === 'sitting',
      load: !!s.state && (s.state.tone === 'high' || s.state.tone === 'tense'),
    };
    for (const k of Object.keys(defs)) {
      if (cond[k]) {
        if (since[k] == null) since[k] = now;
        if (now - since[k] >= defs[k].ms) {
          try { LocalServer.showAlert({ id: defs[k].id, title: defs[k].title, body: defs[k].body }); } catch (_) {}
          since[k] = now; // re-arm for the next full interval while it persists
        }
      } else {
        since[k] = null;
      }
    }
  };
}
if (isAndroid) statusListeners.push(makeAlertEngine());

let ble = null;

// ---- native engine (Android foreground service) --------------------------
// The native HrvNative plugin runs BLE + the 1 Hz HRV loop inside MonitorService
// so monitoring survives screen-off (the WebView JS timer does not). It writes
// every frame to a DB and pushes live frames here via plugin events; on resume
// we pull the points accumulated while hidden and bulk-replay them.
const ENGINE_KEY = 'rmssd-h10n.engine.v1';
let engineMode = 'js';
let nativeSubs = [];
let lastNativeT = '0'; // watermark (epoch ms as string) for DB catch-up
function loadEngine() {
  try { return localStorage.getItem(ENGINE_KEY) === 'native' ? 'native' : 'js'; } catch (_) { return 'js'; }
}

async function startNativeEngine() {
  // Hand off cleanly: the H10 is single-connection, so the JS engine must fully
  // release BLE before the native service connects.
  try { if (ble) { await ble.stop(); ble = null; } } catch (_) {}
  monitor.stop();
  for (const s of nativeSubs) { try { (await s).remove(); } catch (_) {} }
  nativeSubs = [];
  const sStatus = HrvNative.addListener('hrvStatus', (s) => {
    for (const cb of statusListeners) cb(s); hostBroadcast('status', s);
  });
  const sPoint = HrvNative.addListener('hrvPoint', (p) => {
    if (p && p.t) { const e = Date.parse(p.t); if (e) lastNativeT = String(e); }
    for (const cb of pointListeners) cb(p); hostBroadcast('point', p);
  });
  nativeSubs = [sStatus, sPoint];
  try { await HrvNative.start({ acc: false, user: monitor.currentUser || 1 }); }
  catch (e) { console.error('[native] start failed', e); }
  engineMode = 'native';
}

async function stopNativeEngine() {
  for (const s of nativeSubs) { try { (await s).remove(); } catch (_) {} }
  nativeSubs = [];
  try { await HrvNative.stop(); } catch (_) {}
  engineMode = 'js';
}

// Replay points the native engine recorded while the dashboard was hidden.
async function nativeCatchUp() {
  if (engineMode !== 'native') return;
  let since = lastNativeT;
  for (let guard = 0; guard < 50; guard++) {
    let r;
    try { r = await HrvNative.getPointsSince({ since, limit: 2000 }); } catch (_) { break; }
    let arr = [];
    try { arr = JSON.parse(r.points || '[]'); } catch (_) {}
    if (arr.length && window.__pushPointsBulk) window.__pushPointsBulk(arr);
    if (r.lastT) { since = r.lastT; lastNativeT = r.lastT; }
    if (!r || !r.hasMore) break;
  }
}

async function switchEngine(mode) {
  if (mode === engineMode) return { ok: true, engine: engineMode };
  try { localStorage.setItem(ENGINE_KEY, mode); } catch (_) {}
  if (mode === 'native') {
    try { LocalServer.keepAlive({ enabled: true }); } catch (_) {}
    await startNativeEngine();
  } else {
    await stopNativeEngine();
    monitor.start();
    startBle();
  }
  return { ok: true, engine: engineMode };
}

if (isAndroid && typeof document !== 'undefined') {
  document.addEventListener('visibilitychange', () => { if (!document.hidden) nativeCatchUp(); });
}

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
    onAcc: (s) => monitor.onAcc(s),
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
  setPostureRef: () => monitor.setPostureReference(), // capture upright posture -> { ok }
  setSupineRef: () => monitor.setSupineReference(), // capture supine reference (sleep position) -> { ok }
  toggleSleepLR: () => monitor.toggleSleepLR(), // flip sleep-position left/right -> { ok, swap }
  setOrientation: (mode) => applyOrientation(mode), // 'auto' | 'portrait' | 'landscape'
  exportFiles: (files) => exportFiles(files), // share/download CSVs -> { method, count }
  getRrLog: () => monitor.getRrLog(), // recent raw RR beats for the Kubios export
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
  // Start/stop the foreground service that keeps monitoring + server alive in the background.
  keepAlive: (enabled) => { try { return LocalServer.keepAlive({ enabled }); } catch (_) {} },
  // Switch the measurement engine: 'js' (WebView 1 Hz, pauses with screen off) or
  // 'native' (foreground-service BLE + 1 Hz, survives screen off). -> { ok, engine }
  setEngine: (mode) => switchEngine(mode),
  getEngine: () => engineMode,
  start: () => {
    // Android with the native engine selected: BLE + compute run in the service.
    if (isAndroid && loadEngine() === 'native') {
      try { LocalServer.keepAlive({ enabled: true }); } catch (_) {}
      startNativeEngine();
      return;
    }
    monitor.start();
    const s = monitor.getStatus();
    if (s) for (const cb of statusListeners) cb(s);
    if (platform === 'web') {
      startSimulate();
    } else {
      startBle();
      // Keep running with the screen off / app backgrounded (foreground service).
      try { LocalServer.keepAlive({ enabled: true }); } catch (_) {}
    }
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
      if (snap.trend5) localStorage.setItem(`rmssd-h10n.trend5.v1.u${u}`, JSON.stringify(snap.trend5));
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
    setPostureRef: () => ({ ok: false }),
    setSupineRef: () => ({ ok: false }),
    toggleSleepLR: () => ({ ok: false }),
    setOrientation: noop,
    setEngine: () => ({ ok: false, engine: 'js' }),
    getEngine: () => 'js',
    exportFiles: (files) => exportFiles(files), // browser download
    getRrLog: () => [], // raw RR is host-only (not streamed to remote)
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
