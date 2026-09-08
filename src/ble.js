'use strict';

const pmd = require('./pmd');
const { withDeadline, abortError } = require('./async');

const identity = value => String(value || '').replace(/[:-]/g, '').toLowerCase();
function matchesDevice(peripheral, deviceId, nameMatch = 'polar') {
  if (deviceId) return [peripheral.id, peripheral.address].some(value => value && identity(value) === identity(deviceId));
  return String(peripheral.advertisement?.localName || '').toLowerCase().includes(nameMatch.toLowerCase());
}

/** Injecting the native adapter lets lifecycle tests run without opening Bluetooth. */
function createBle(noble, { stopTimeoutMs = 1500, connectTimeoutMs = 15000, disconnectTimeoutMs = 4000 } = {}) {
  const connecting = new Map();
  let scanning = false;

  async function disconnectWithTimeout(peripheral, ms = disconnectTimeoutMs) {
    if (!peripheral) return;
    try { await withDeadline(() => peripheral.disconnectAsync(), ms, 'disconnect'); } catch (_) {}
  }

  async function waitForPoweredOn(signal, timeoutMs = 10000) {
    if (noble.state === 'poweredOn') return;
    let onState;
    try {
      await withDeadline(() => new Promise(resolve => {
        onState = state => { if (state === 'poweredOn') resolve(); };
        noble.on('stateChange', onState);
        if (noble.state === 'poweredOn') resolve();
      }), timeoutMs, `Bluetooth adapter (state=${noble.state})`, signal);
    } finally {
      if (onState) noble.removeListener('stateChange', onState);
    }
  }

  async function connect(peripheral, signal) {
    const key = identity(peripheral.address || peripheral.id);
    if (connecting.has(key)) throw Object.assign(new Error('Previous connection is still being released'), { code: 'release_pending' });
    const attempt = {};
    connecting.set(key, attempt);
    // A retired native connect retains this ID until it settles and its late
    // connection is released. Old cleanup cannot disconnect a newer attempt.
    const pending = Promise.resolve().then(() => {
      if (signal?.aborted) throw abortError(signal);
      return peripheral.connectAsync();
    });
    try {
      await withDeadline(pending, connectTimeoutMs, 'connect', signal);
      if (signal?.aborted) throw abortError(signal);
      connecting.delete(key);
      return peripheral;
    } catch (error) {
      const initialRelease = disconnectWithTimeout(peripheral);
      pending.then(async () => {
        await initialRelease;
        await disconnectWithTimeout(peripheral);
      }, () => initialRelease).finally(() => {
        if (connecting.get(key) === attempt) connecting.delete(key);
      }).catch(() => {});
      await initialRelease;
      throw error;
    }
  }

  async function scanAndConnect({ nameMatch = 'polar', deviceId, timeoutMs = 30000, log = () => {}, signal, onStage = () => {} } = {}) {
    onStage('waiting_adapter');
    await waitForPoweredOn(signal);
    if (signal?.aborted) throw abortError(signal);
    if (scanning) throw Object.assign(new Error('A scan is already in progress'), { code: 'scan_busy' });
    scanning = true;
    let onDiscover, onState, peripheral;
    try {
      onStage('scanning');
      log(`Scanning for ${deviceId ? 'the selected device' : `"${nameMatch}"`}...`);
      peripheral = await withDeadline(() => new Promise((resolve, reject) => {
        onDiscover = candidate => {
          if (matchesDevice(candidate, deviceId, nameMatch)) resolve(candidate);
        };
        onState = state => {
          if (state !== 'poweredOn') reject(Object.assign(new Error(`Bluetooth adapter ${state}`), { code: 'adapter_off' }));
        };
        noble.on('discover', onDiscover);
        noble.on('stateChange', onState);
        // H10 alternates named/nameless packets; duplicate advertisements are required.
        Promise.resolve().then(() => noble.startScanningAsync([], true)).catch(reject);
      }), timeoutMs, 'scan', signal);
    } finally {
      if (onDiscover) noble.removeListener('discover', onDiscover);
      if (onState) noble.removeListener('stateChange', onState);
      try { await withDeadline(() => noble.stopScanningAsync(), stopTimeoutMs, 'stop scanning'); } catch (_) {}
      scanning = false;
    }
    if (signal?.aborted) throw abortError(signal);
    onStage('connecting');
    await connect(peripheral, signal);
    log('Connected.');
    return peripheral;
  }

  async function discoverPmd(peripheral) {
    const { characteristics } = await peripheral.discoverSomeServicesAndCharacteristicsAsync(
      [pmd.PMD_SERVICE, pmd.HR_SERVICE], [pmd.PMD_CONTROL, pmd.PMD_DATA, pmd.HR_MEASUREMENT]);
    const byUuid = Object.fromEntries(characteristics.map(c => [c.uuid, c]));
    const control = byUuid[pmd.PMD_CONTROL], data = byUuid[pmd.PMD_DATA];
    if (!control || !data) throw new Error('PMD characteristics not found — is this a Polar H10 with firmware exposing PMD?');
    return { control, data, hr: byUuid[pmd.HR_MEASUREMENT] || null };
  }

  async function discoverHr(peripheral) {
    const { characteristics } = await peripheral.discoverSomeServicesAndCharacteristicsAsync([pmd.HR_SERVICE], [pmd.HR_MEASUREMENT]);
    const hrm = characteristics.find(c => c.uuid === pmd.HR_MEASUREMENT);
    if (!hrm) throw new Error('Heart Rate Measurement characteristic (0x2A37) not found');
    return { hrm };
  }

  async function discoverBattery(peripheral) {
    const { characteristics } = await peripheral.discoverSomeServicesAndCharacteristicsAsync([pmd.BATTERY_SERVICE], [pmd.BATTERY_LEVEL]);
    return characteristics.find(c => c.uuid === pmd.BATTERY_LEVEL) || null;
  }

  return { noble, scanAndConnect, discoverPmd, discoverHr, discoverBattery, disconnectWithTimeout };
}

// Simulation/tests can load this module without initializing the native adapter.
let client;
const defaultClient = () => client || (client = createBle(require('@abandonware/noble')));
module.exports = { createBle, matchesDevice };
for (const name of ['scanAndConnect', 'discoverPmd', 'discoverHr', 'discoverBattery', 'disconnectWithTimeout']) {
  module.exports[name] = (...args) => defaultClient()[name](...args);
}
Object.defineProperty(module.exports, 'noble', { get: () => defaultClient().noble });
