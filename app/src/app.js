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

// ---- native engine (Android foreground service) --------------------------
// On Android the native HrvNative plugin is the ONLY compute path: it runs BLE +
// the 1 Hz HRV loop inside MonitorService, so monitoring survives screen-off (the
// WebView JS timer does not). It writes every frame to a DB and pushes live
// frames here via plugin events; on resume we pull the points accumulated while
// hidden and bulk-replay them. (The old in-WebView JS engine + its toggle were
// removed once native proved out — the Monitor below is kept only for ?sim=1.)
const useNative = isAndroid;
let currentUser = 1;
let nativeSubs = [];
let lastNativeT = '0'; // watermark (epoch ms as string) for DB catch-up

async function startNativeEngine() {
  for (const s of nativeSubs) { try { (await s).remove(); } catch (_) {} }
  nativeSubs = [];
  const sStatus = HrvNative.addListener('hrvStatus', (s) => {
    for (const cb of statusListeners) cb(s); hostBroadcast('status', s);
  });
  const sPoint = HrvNative.addListener('hrvPoint', (p) => {
    if (p && p.t) { const e = Date.parse(p.t); if (e) lastNativeT = String(e); }
    for (const cb of pointListeners) cb(p); hostBroadcast('point', p);
  });
  const sBackfill = HrvNative.addListener('hrvBackfill', () => {
    // A gap recovered from H10 memory was written to the DB with PAST timestamps. The
    // live event is only a low-latency nudge — drain the durable import ledger so a
    // restore that landed while no WebView was attached is merged too (idempotent).
    drainBackfillImports();
  });
  nativeSubs = [sStatus, sPoint, sBackfill];
  try { await HrvNative.start({ acc: true, user: currentUser, seed: buildNativeSeed(currentUser) }); }
  catch (e) { console.error('[native] start failed', e); }
}

// Seed the native engine with this user's persisted posture/supine refs, sleep
// L/R and baseline so it starts calibrated.
function buildNativeSeed(u) {
  const get = (k) => { try { return JSON.parse(localStorage.getItem(`rmssd-h10n.${k}.v1.u${u}`) || 'null'); } catch (_) { return null; } };
  let swap = false;
  try { swap = localStorage.getItem(`rmssd-h10n.sleeplr.v1.u${u}`) === '1'; } catch (_) {}
  return JSON.stringify({ ref: get('posture'), supine: get('supine'), latSign: swap ? -1 : 1, baseline: get('baseline') });
}

// Switch the active user: restart the native engine on the new user's refs/baseline.
async function switchUserNative(n) {
  currentUser = n;
  try { await HrvNative.stop(); } catch (_) {}
  await startNativeEngine();
}

// Replay points the native engine recorded while the dashboard was hidden.
// Show the current value first (instant), then backfill the gap in ONE batch so
// a long backlog doesn't render the chart in visible chunks or delay "now".
async function nativeCatchUp() {
  if (!useNative) return;
  // After a page reload lastNativeT is "0"; seed it from the newest point the
  // dashboard already holds so we don't re-fetch (and double-count into the trend
  // buckets) the entire DB. Live points then advance it forward as usual.
  if (lastNativeT === '0') {
    try { const lt = window.__latestPointT && window.__latestPointT(); if (lt) lastNativeT = String(lt); } catch (_) {}
  }
  // 1) Apply the latest snapshot immediately so the cards jump to current.
  try {
    const st = await HrvNative.getStatus();
    if (st && st.value) { const s = JSON.parse(st.value); for (const cb of statusListeners) cb(s); }
  } catch (_) {}
  // 2) Accumulate every page, then a single bulk insert + one repaint.
  let since = lastNativeT;
  const all = [];
  for (let guard = 0; guard < 50; guard++) {
    let r;
    try { r = await HrvNative.getPointsSince({ since, limit: 5000 }); } catch (_) { break; }
    let arr = [];
    try { arr = JSON.parse(r.points || '[]'); } catch (_) {}
    if (arr.length) for (const p of arr) all.push(p);
    if (r.lastT) { since = r.lastT; lastNativeT = r.lastT; }
    if (!r || !r.hasMore) break;
  }
  if (all.length && window.__pushPointsBulk) window.__pushPointsBulk(all);
  await drainBackfillImports(); // merge any service-only gap restore (past timestamps)
}

// A backfilled gap has PAST timestamps, so it can't ride the forward-only watermark
// catch-up. Re-fetch every DB point in the (widest) trend buckets the gap touches —
// gap points plus any live boundary points — and merge: history is rebuilt
// sorted+deduped and only the touched buckets are recomputed from source, which is
// correct even though they arrive after newer live data.
async function nativeBackfillMerge(fromMs, toMs, truncated) {
  if (!useNative) return;
  const WIDE = 15 * 60 * 1000; // widest trend bucket — covers both 5- and 15-min stores
  const lo = Math.floor(fromMs / WIDE) * WIDE;
  const hi = Math.floor(toMs / WIDE) * WIDE + WIDE;
  const pts = [];
  let since = String(lo - 1);
  for (let guard = 0; guard < 50; guard++) {
    let r;
    try { r = await HrvNative.getPointsSince({ since, limit: 5000 }); } catch (_) { break; }
    let arr = [];
    try { arr = JSON.parse(r.points || '[]'); } catch (_) {}
    let stop = false;
    for (const p of arr) { const e = Date.parse(p.t); if (e >= hi) { stop = true; break; } pts.push(p); }
    if (r && r.lastT) since = r.lastT;
    if (stop || !r || !r.hasMore) break;
  }
  if (pts.length && window.__mergeBackfill) window.__mergeBackfill(pts, { truncated: !!truncated });
}

// WebView-independent backfill catch-up: drain the native import ledger (gap ranges
// restored from H10 memory — possibly while NO WebView was attached, e.g. after an
// app/OS restart) and merge each touched trend-bucket range. The live hrvBackfill
// event is just a nudge to run this; it is idempotent and guarded until the chart
// hooks exist, so it is safe to call on load, on resume, and on the event.
async function drainBackfillImports() {
  if (!useNative || !HrvNative.getUnmergedImports || !window.__mergeBackfill) return;
  let imports = [];
  try { const r = await HrvNative.getUnmergedImports(); imports = JSON.parse((r && r.imports) || '[]'); }
  catch (_) { return; }
  if (!imports.length) return;
  const ids = [];
  for (const im of imports) {
    const from = Number(im.fromMs), to = Number(im.toMs);
    if (Number.isFinite(from) && Number.isFinite(to)) await nativeBackfillMerge(from, to, !!Number(im.truncated));
    ids.push(im.id);
  }
  try { await HrvNative.markImportsMerged({ ids: ids.join(',') }); } catch (_) {}
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
  // On Android every control op targets the native engine (the source of truth);
  // in ?sim=1 they target the in-WebView Monitor.
  switchUser: (n) => useNative ? switchUserNative(n) : monitor.switchUser(n),
  resetBaseline: () => useNative ? HrvNative.resetBaseline() : monitor.resetBaseline(),
  refreezeBaseline: () => monitor.refreezeFromHistory(), // sim only; native refreeze is computed in the dashboard and pushed via setBaseline
  setBaseline: (rmssd, hr) => useNative ? HrvNative.setBaseline({ rmssd, hr }) : monitor.setBaseline(rmssd, hr), // -> { ok }
  setPostureRef: () => useNative ? HrvNative.setPostureRef() : monitor.setPostureReference(), // -> { ok }
  setSupineRef: () => useNative ? HrvNative.setSupineRef() : monitor.setSupineReference(), // -> { ok }
  toggleSleepLR: () => useNative ? HrvNative.toggleSleepLR() : monitor.toggleSleepLR(), // -> { ok, swap }
  setOrientation: (mode) => applyOrientation(mode), // 'auto' | 'portrait' | 'landscape'
  setRelaxVoice: (sec) => useNative ? HrvNative.setRelaxVoice({ sec }) : ({ ok: false }), // native TTS readout interval (s; 0=off)
  setPowerSave: (on) => useNative ? HrvNative.setPowerSave({ on }) : ({ ok: false }), // ACC power-save mode (duty-cycle + steps off)
  exportFiles: (files) => exportFiles(files), // share/download CSVs -> { method, count }
  getRrLog: async () => { // recent raw RR beats for the Kubios export
    if (!useNative) return monitor.getRrLog();
    try { const r = await HrvNative.getRrLog(); return JSON.parse(r.log || '[]'); } catch (_) { return []; }
  },
  now: () => localIso(), // JST ISO timestamp, matching point.t — used for activity logging
  iso: (ms) => localIso(new Date(ms)), // arbitrary epoch ms → JST ISO (activity time edits)
  isRemote: false,
  nativeEngine: useNative, // dashboard branches refreeze-from-history on this
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
  start: () => {
    if (useNative) {
      // BLE + compute run in the foreground service (survives screen-off).
      try { LocalServer.keepAlive({ enabled: true }); } catch (_) {}
      startNativeEngine();
      // Cold start: visibilitychange doesn't fire for the initial 'visible' state, so
      // trigger catch-up once the chart hooks exist — pulls forward points AND drains a
      // gap restored while no WebView was attached (service-only restore after a restart).
      setTimeout(() => { try { nativeCatchUp(); } catch (_) {} }, 1500);
      return;
    }
    // ?sim=1: the in-WebView Monitor driven by a synthetic RR stream.
    monitor.start();
    const s = monitor.getStatus();
    if (s) for (const cb of statusListeners) cb(s);
    startSimulate();
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
    setRelaxVoice: () => ({ ok: false }), // native-only (host TTS); remote view can't drive it
    setPowerSave: () => ({ ok: false }), // native-only (BLE/ACC); remote view can't drive it
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
