'use strict';

// Native-BLE adapter for Android (Capacitor) — counterpart of the desktop
// src/ble.js + index.js runHrRr path. Subscribes to the standard HR Measurement
// characteristic (0x2A37) and feeds RR/HR to a Monitor, with auto-reconnect.
//
// Device selection uses BleClient.requestDevice (the OS device picker) FILTERED
// to the Heart Rate service (0x180D): on Android a filterless app scan plus the
// neverForLocation flag returns nothing, a namePrefix filter drops the H10's
// nameless adverts, but a service filter shows exactly the HR straps regardless
// of advertised name. The chosen deviceId is then remembered (localStorage) so
// later launches connect directly with no dialog.
import { BleClient, numberToUUID } from '@capacitor-community/bluetooth-le';
import { parseHrm } from '../../src/hrm.js';
import { parseAcc, PMD_SERVICE, PMD_CONTROL, PMD_DATA, ACC_START_COMMAND } from '../../src/acc.js';

const HR_SERVICE = numberToUUID(0x180d);
const HR_MEAS = numberToUUID(0x2a37);
const DEVICE_KEY = 'rmssd-h10n.bleDeviceId.v1';
const delay = (ms) => new Promise((r) => setTimeout(r, ms));
// BLE ops can hang on a flaky link; bound them so a stuck PMD start never blocks
// the (already working) HR path.
const withTimeout = (p, ms, label) => Promise.race([
  p, new Promise((_, rej) => setTimeout(() => rej(new Error(`${label} timeout`)), ms)),
]);

export class BleHr {
  constructor({ preferredId = null, onRr, onHr, onAcc, onConnected, log = () => {} } = {}) {
    this.onRr = onRr;
    this.onHr = onHr;
    this.onAcc = onAcc;
    this.onConnected = onConnected;
    this.log = log;
    this.deviceId = null; // currently connected
    // Prefer a remembered device, else a caller-provided known address. The H10
    // advertises neither a stable name nor the HR service UUID, so picker filters
    // cannot select it — a direct connect by address is the reliable path.
    let saved = null;
    try { saved = localStorage.getItem(DEVICE_KEY); } catch (_) {}
    this.savedDeviceId = saved || preferredId || null;
    this.stopping = false;
    // ACC throughput telemetry (logged ~every 60 s; see [batt] in monitor.js).
    this._accNotify = 0;
    this._accSamp = 0;
    this._accLogAt = Date.now();
  }

  async start() {
    await BleClient.initialize({ androidNeverForLocation: true });
    this.stopping = false;
    await this._connectLoop();
  }

  async stop() {
    this.stopping = true;
    if (this.deviceId) {
      try { await BleClient.disconnect(this.deviceId); } catch (_) {}
      this.deviceId = null;
    }
  }

  // Forget the remembered device so the next round shows the picker again.
  forget() {
    this.savedDeviceId = null;
    try { localStorage.removeItem(DEVICE_KEY); } catch (_) {}
  }

  async _connectLoop() {
    let failures = 0;
    while (!this.stopping) {
      try {
        let id = this.savedDeviceId;
        if (!id) {
          this.log('opening device picker (all devices)...');
          const device = await BleClient.requestDevice({
            optionalServices: [HR_SERVICE, PMD_SERVICE],
          });
          id = device.deviceId;
          this.log(`selected ${device.name || id}`);
        }
        if (this.stopping) return;
        await this._attach(id);
        this.savedDeviceId = id;
        try { localStorage.setItem(DEVICE_KEY, id); } catch (_) {} // remember for silent reconnect
        return; // connected; disconnect handler re-enters connectLoop on drop
      } catch (e) {
        if (this.stopping) return;
        // KEEP the saved id and retry it directly. Dropping to the picker is
        // useless while the screen is off / app backgrounded (no one to tap it),
        // and spinning the picker just drains battery; the H10's address is
        // stable, so a direct reconnect is the reliable path. Use forget() to
        // deliberately return to the picker. Back off 5→10→20→30 s.
        failures++;
        const wait = Math.min(30000, 5000 * 2 ** (failures - 1));
        this.log(`connect failed (${e.message}); retrying${this.savedDeviceId ? ' saved device' : ' via picker'} in ${wait / 1000}s...`);
        await delay(wait);
      }
    }
  }

  async _attach(deviceId) {
    this.deviceId = deviceId;
    await BleClient.connect(deviceId, () => this._onDisconnect());
    await BleClient.startNotifications(deviceId, HR_SERVICE, HR_MEAS, (value) => {
      const { hr, rr } = parseHrm(value); // value is a DataView
      if (hr != null && this.onHr) this.onHr(hr);
      if (this.onRr) for (const interval of rr) this.onRr(interval);
    });
    this.log('subscribed to HR Measurement (0x2A37). Reading RR intervals.');
    if (this.onConnected) this.onConnected(true);

    // Best-effort accelerometer for posture, on the SAME PMD connection. Failure
    // here (e.g. PMD busy/unsupported) must not affect HR/RR, so it is fully
    // isolated: any error is logged and swallowed.
    if (this.onAcc) this._startAcc(deviceId).catch((e) => this.log(`ACC start skipped: ${e.message}`));
  }

  async _startAcc(deviceId) {
    // Data notifications must be enabled before the device starts streaming.
    await withTimeout(
      BleClient.startNotifications(deviceId, PMD_SERVICE, PMD_DATA, (value) => {
        const f = parseAcc(value); // value is a DataView
        if (f && f.samples.length) for (const s of f.samples) this.onAcc(s);
        // ACC throughput telemetry — driven by notify arrival (no extra timer).
        this._accNotify++;
        this._accSamp += (f && f.samples.length) || 0;
        const t = Date.now();
        if (t - this._accLogAt >= 60000) {
          const secs = Math.max(1, Math.round((t - this._accLogAt) / 1000));
          this.log(`[batt-acc] notify=${this._accNotify} samples=${this._accSamp} in ${secs}s (${Math.round(this._accSamp / secs)}/s)`);
          this._accNotify = 0; this._accSamp = 0; this._accLogAt = t;
        }
      }), 8000, 'PMD data subscribe');
    // The control point answers START over indications; enable them (some stacks
    // require the CCCD before they accept the write) and ignore the payload.
    try {
      await withTimeout(
        BleClient.startNotifications(deviceId, PMD_SERVICE, PMD_CONTROL, () => {}),
        5000, 'PMD control subscribe');
    } catch (e) { this.log(`PMD control indicate: ${e.message}`); }
    const cmd = new DataView(ACC_START_COMMAND.buffer, ACC_START_COMMAND.byteOffset, ACC_START_COMMAND.byteLength);
    await withTimeout(
      BleClient.write(deviceId, PMD_SERVICE, PMD_CONTROL, cmd), 5000, 'PMD ACC start');
    this.log('PMD ACC started (25 Hz). Streaming accelerometer for posture.');
  }

  _onDisconnect() {
    this.deviceId = null;
    if (this.onConnected) this.onConnected(false);
    if (this.stopping) return;
    this.log('disconnected — reconnecting...');
    this._connectLoop(); // reuses savedDeviceId for a silent reconnect
  }
}
