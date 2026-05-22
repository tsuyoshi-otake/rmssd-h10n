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

const HR_SERVICE = numberToUUID(0x180d);
const HR_MEAS = numberToUUID(0x2a37);
const DEVICE_KEY = 'rmssd-h10n.bleDeviceId.v1';
const delay = (ms) => new Promise((r) => setTimeout(r, ms));

export class BleHr {
  constructor({ preferredId = null, onRr, onHr, onConnected, log = () => {} } = {}) {
    this.onRr = onRr;
    this.onHr = onHr;
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
    while (!this.stopping) {
      try {
        let id = this.savedDeviceId;
        if (!id) {
          this.log('opening device picker (all devices)...');
          const device = await BleClient.requestDevice({
            optionalServices: [HR_SERVICE],
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
        this.log(`connect failed (${e.message}); retrying in 5s...`);
        this.savedDeviceId = null; // a dead saved id falls back to the picker
        await delay(5000);
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
  }

  _onDisconnect() {
    this.deviceId = null;
    if (this.onConnected) this.onConnected(false);
    if (this.stopping) return;
    this.log('disconnected — reconnecting...');
    this._connectLoop(); // reuses savedDeviceId for a silent reconnect
  }
}
