'use strict';
const assert = require('node:assert/strict');
const { test } = require('node:test');
const { EventEmitter } = require('node:events');
const { createBle, matchesDevice } = require('../src/ble');
const { HrSession } = require('../src/hr-session');
const { ConnectionStatus } = require('../src/connection-status');
const { retryDelay } = require('../src/async');

const turn = () => new Promise(resolve => setImmediate(resolve));
function deferred() { let resolve, reject; const promise = new Promise((a, b) => { resolve = a; reject = b; }); return { promise, resolve, reject }; }
async function waitFor(fn) {
  const deadline = Date.now() + 2000;
  while (!fn()) { if (Date.now() > deadline) throw new Error('condition timeout'); await new Promise(r => setTimeout(r, 1)); }
}
function fixture() {
  const peripheral = Object.assign(new EventEmitter(), { id: 'h10-one', address: 'AA:BB:CC:DD:EE:01',
    advertisement: { localName: 'Polar H10' }, connects: 0, disconnects: 0, state: 'disconnected' });
  peripheral.connectAsync = async () => { peripheral.connects++; peripheral.state = 'connected'; };
  peripheral.disconnectAsync = async () => { peripheral.disconnects++; peripheral.state = 'disconnected'; };
  const noble = Object.assign(new EventEmitter(), { state: 'poweredOn', stops: 0 });
  noble.startScanningAsync = async (_, duplicates) => {
    assert.equal(duplicates, true);
    queueMicrotask(() => noble.emit('discover', peripheral));
  };
  noble.stopScanningAsync = async () => { noble.stops++; };
  const ble = createBle(noble, { stopTimeoutMs: 15, connectTimeoutMs: 15, disconnectTimeoutMs: 15 });
  return { noble, peripheral, ble };
}

test('lost scanStop callback is bounded on both discovery and scan timeout (#9)', async () => {
  for (const found of [true, false]) {
    const { ble, noble, peripheral } = fixture();
    noble.stopScanningAsync = () => new Promise(() => {});
    if (!found) noble.startScanningAsync = async () => {};
    if (found) assert.equal(await ble.scanAndConnect({ timeoutMs: 15 }), peripheral);
    else await assert.rejects(ble.scanAndConnect({ timeoutMs: 15 }), /scan timed out/);
    assert.equal(noble.listenerCount('discover'), 0);
    assert.equal(noble.listenerCount('stateChange'), 0);
  }
});

test('retired native connect owns identity until its late connection is released (#10)', async () => {
  const { ble, peripheral } = fixture();
  const pending = deferred();
  peripheral.connectAsync = () => { peripheral.connects++; return pending.promise.then(() => { peripheral.state = 'connected'; }); };
  await assert.rejects(ble.scanAndConnect(), /connect timed out/);
  assert.equal(peripheral.disconnects, 1);
  await assert.rejects(ble.scanAndConnect(), { code: 'release_pending' });
  assert.equal(peripheral.connects, 1);
  pending.resolve(); await turn(); await turn();
  assert.equal(peripheral.disconnects, 2);
  assert.equal(peripheral.state, 'disconnected');
  peripheral.connectAsync = async () => { peripheral.connects++; peripheral.state = 'connected'; };
  await ble.scanAndConnect(); await turn();
  assert.equal(peripheral.connects, 2);
  assert.equal(peripheral.state, 'connected'); // old cleanup must not reach the new connection
});

test('retired connect rejection and cancellation release the identity exactly once (#10)', async () => {
  for (const outcome of ['reject', 'cancel']) {
    const { ble, peripheral } = fixture();
    const pending = deferred(); const controller = new AbortController();
    peripheral.connectAsync = () => { peripheral.connects++; return pending.promise; };
    const result = ble.scanAndConnect({ signal: controller.signal });
    await waitFor(() => peripheral.connects === 1);
    if (outcome === 'cancel') controller.abort();
    else pending.reject(new Error('native connect failed'));
    await assert.rejects(result);
    if (outcome === 'cancel') pending.resolve();
    await turn(); await turn();
    assert.equal(peripheral.disconnects, outcome === 'cancel' ? 2 : 1);
    peripheral.connectAsync = async () => { peripheral.connects++; };
    await ble.scanAndConnect();
  }
});

test('scan cancellation, start failure and adapter-off remove attempt listeners', async () => {
  for (const kind of ['cancel', 'start-fail', 'adapter-off']) {
    const { ble, noble } = fixture();
    const controller = new AbortController();
    noble.startScanningAsync = async () => {
      if (kind === 'start-fail') throw new Error('scan failed');
      if (kind === 'cancel') controller.abort();
      else noble.emit('stateChange', 'poweredOff');
    };
    await assert.rejects(ble.scanAndConnect({ signal: controller.signal }));
    assert.equal(noble.listenerCount('discover'), 0);
    assert.equal(noble.listenerCount('stateChange'), 0);
  }
});

test('device identity wins over same-name and nameless advertisements (#17)', () => {
  const { peripheral } = fixture();
  assert.equal(matchesDevice(peripheral, 'aa-bb-cc-dd-ee-01'), true);
  assert.equal(matchesDevice(peripheral, 'AA:BB:CC:DD:EE:02'), false);
  peripheral.advertisement.localName = '';
  assert.equal(matchesDevice(peripheral, 'h10-one'), true);
  assert.equal(matchesDevice(peripheral, null, 'polar'), false);
});

test('disconnect/stop during pending subscribe cannot revive the session (#11)', async t => {
  for (const kind of ['disconnect', 'stop']) {
    const { peripheral } = fixture();
    const subscribed = deferred();
    const hrm = new EventEmitter();
    hrm.subscribeAsync = () => subscribed.promise;
    hrm.unsubscribeAsync = async () => {};
    const changes = [], beats = [], stages = [];
    const ble = { scanAndConnect: async () => peripheral, discoverHr: async () => ({ hrm }),
      disconnectWithTimeout: peripheral.disconnectAsync };
    const session = new HrSession({ ble, retryMs: 10000, cleanupMs: 15,
      setConnected: c => changes.push(c), onRr: rr => beats.push(rr), onStage: stage => stages.push(stage) });
    t.after(() => session.stop());
    session.start();
    await waitFor(() => hrm.listenerCount('data') > 0);
    if (kind === 'disconnect') {
      peripheral.emit('disconnect');
      await waitFor(() => stages.includes('retry_wait'));
    }
    await session.stop();
    subscribed.resolve(); await turn();
    hrm.emit('data', Buffer.from([0x10, 60, 0, 4]));
    assert.equal(changes.includes(true), false);
    assert.deepEqual(beats, []);
    assert.equal(hrm.listenerCount('data'), 0);
    assert.equal(peripheral.listenerCount('disconnect'), 0);
    assert.equal(stages.at(-1), 'stopped');
  }
});

test('disconnect invalidates pending discovery and battery callbacks (#11)', async t => {
  for (const phase of ['discover', 'battery']) {
    const { peripheral } = fixture();
    const pending = deferred(); const changes = [], beats = [], batteries = [];
    let scans = 0, discovers = 0, batteryDiscovers = 0;
    const oldHrm = new EventEmitter(); oldHrm.subscribeAsync = async () => {}; oldHrm.unsubscribeAsync = async () => {};
    const liveHrm = new EventEmitter(); liveHrm.subscribeAsync = async () => {}; liveHrm.unsubscribeAsync = async () => {};
    const battery = new EventEmitter(); battery.readAsync = () => pending.promise; battery.subscribeAsync = async () => {}; battery.unsubscribeAsync = async () => {};
    const ble = {
      scanAndConnect: async () => { scans++; return peripheral; },
      discoverHr: async () => {
        discovers++;
        if (discovers === 1 && phase === 'discover') return pending.promise;
        return { hrm: discovers === 1 ? oldHrm : liveHrm };
      },
      discoverBattery: async () => ++batteryDiscovers === 1 && phase === 'battery' ? battery : null,
      disconnectWithTimeout: peripheral.disconnectAsync,
    };
    const session = new HrSession({ ble, retryMs: 2, cleanupMs: 10,
      setConnected: value => changes.push(value), onRr: value => beats.push(value),
      onBattery: value => batteries.push(value) }).start();
    t.after(() => session.stop());
    await waitFor(() => peripheral.listenerCount('disconnect') > 0 && (phase === 'discover' || batteryDiscovers > 0));
    peripheral.emit('disconnect');
    await waitFor(() => scans >= 2);
    pending.resolve(phase === 'discover' ? { hrm: oldHrm } : Buffer.from([77]));
    await turn(); await turn();
    oldHrm.emit('data', Buffer.from([0x10, 60, 0, 4]));
    battery.emit('data', Buffer.from([88]));
    assert.deepEqual(beats, []);
    assert.deepEqual(batteries, []);
    assert.equal(oldHrm.listenerCount('data'), 0);
    await session.stop();
    assert.equal(changes.at(-1), false);
  }
});

test('only one reconnect owner handles a subscribed disconnect (#11)', async t => {
  const { peripheral } = fixture();
  const hrm = new EventEmitter();
  hrm.subscribeAsync = async () => {};
  hrm.unsubscribeAsync = async () => {};
  let scans = 0, active = 0, maxActive = 0;
  const ble = { scanAndConnect: async () => { scans++; maxActive = Math.max(maxActive, ++active); await turn(); active--; return peripheral; },
    discoverHr: async () => ({ hrm }), disconnectWithTimeout: peripheral.disconnectAsync };
  const session = new HrSession({ ble, retryMs: 5, random: () => 0, cleanupMs: 15 }).start();
  t.after(() => session.stop());
  await session.ready;
  peripheral.emit('disconnect'); peripheral.emit('disconnect');
  await waitFor(() => scans === 2 && hrm.listenerCount('data') === 2);
  assert.equal(scans, 2);
  assert.equal(maxActive, 1);
  await session.stop();
  assert.equal(hrm.listenerCount('data'), 0);
});

test('watchdog and disconnect share one reconnect owner (#11)', async t => {
  const { peripheral } = fixture();
  const hrm = new EventEmitter(); hrm.subscribeAsync = async () => {}; hrm.unsubscribeAsync = async () => {};
  let scans = 0, now = 0;
  const ble = { scanAndConnect: async () => { scans++; return peripheral; },
    discoverHr: async () => ({ hrm }), disconnectWithTimeout: peripheral.disconnectAsync };
  const session = new HrSession({ ble, retryMs: 2, cleanupMs: 10, stallMs: 5, now: () => now }).start();
  t.after(() => session.stop());
  await session.ready;
  now = 20;
  peripheral.emit('disconnect');
  await waitFor(() => scans === 2);
  await new Promise(resolve => setTimeout(resolve, 15));
  assert.equal(scans, 2);
});

test('diagnostics are bounded and retry backoff is capped (#15)', () => {
  const status = new ConnectionStatus({ now: () => 1, limit: 64 });
  for (let i = 0; i < 640; i++) status.transition('retry_wait', 'timeout', { attempt: i, nextRetryAt: i + 100 });
  assert.equal(status.diagnostics().events.length, 64);
  assert.equal(status.snapshot().attempt, 639);
  status.transition('AA:BB:CC:DD', 'raw 812.5');
  assert.equal(status.snapshot().stage, 'idle');
  assert.equal(status.snapshot().reason, 'connection_failed');
  assert.equal(retryDelay(100, () => 1), 60000);
  assert.ok(retryDelay(1, () => 0) >= 4000);
  const copy = status.diagnostics(); copy.events.length = 0;
  assert.equal(status.diagnostics().events.length, 64);
});
