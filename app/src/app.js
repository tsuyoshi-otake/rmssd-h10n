'use strict';

// App entry: wires the shared Monitor (1 Hz HRV pipeline) to a data source and
// exposes a small window.RmssdBridge the dashboard UI talks to instead of the
// desktop WebSocket / REST API. On Android the source is native BLE; in a plain
// browser (no Capacitor native layer) it falls back to a synthetic RR stream so
// the UI can be developed/inspected without hardware.
import { Capacitor } from '@capacitor/core';
import { ScreenOrientation } from '@capacitor/screen-orientation';
import { Monitor } from './monitor.js';
import { BleHr } from './ble-hr.js';
import { localIso } from '../../src/time.js';

const statusListeners = [];
const pointListeners = [];

const monitor = new Monitor({
  windowSec: 30,
  user: 1,
  onStatus: (s) => { for (const cb of statusListeners) cb(s); },
  onPoint: (p) => { for (const cb of pointListeners) cb(p); },
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

// Bridge consumed by the inline dashboard script (see www/index.html).
window.RmssdBridge = {
  onStatus: (cb) => { statusListeners.push(cb); },
  onPoint: (cb) => { pointListeners.push(cb); },
  switchUser: (n) => monitor.switchUser(n),
  resetBaseline: () => monitor.resetBaseline(),
  refreezeBaseline: () => monitor.refreezeFromHistory(), // { applied, baseline? }
  setOrientation: (mode) => applyOrientation(mode), // 'auto' | 'portrait' | 'landscape'
  now: () => localIso(), // JST ISO timestamp, matching point.t — used for activity logging
  iso: (ms) => localIso(new Date(ms)), // arbitrary epoch ms → JST ISO (activity time edits)
  start: () => {
    monitor.start();
    const s = monitor.getStatus();
    if (s) for (const cb of statusListeners) cb(s);
    if (Capacitor.getPlatform() === 'web') startSimulate();
    else startBle();
  },
};
