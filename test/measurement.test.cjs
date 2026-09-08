'use strict';
const assert = require('node:assert/strict');
const { test } = require('node:test');
const { EventEmitter } = require('node:events');
const { measure } = require('../src/measurement');
const pmd = require('../src/pmd');

function fixture() {
  const events = [];
  const peripheral = Object.assign(new EventEmitter(), { id: 'test-h10' });
  const hrm = new EventEmitter();
  hrm.subscribeAsync = async () => { hrm.emit('data', Buffer.from([0x10, 60, 0, 4, 0, 4])); };
  hrm.unsubscribeAsync = async () => { events.push('unsubscribe'); };
  const ble = { scanAndConnect: async () => peripheral, discoverHr: async () => ({ hrm }),
    disconnectWithTimeout: async () => { events.push('disconnect'); } };
  return { events, peripheral, hrm, ble };
}

test('measurement result is returned only after unsubscribe and disconnect (#12)', async () => {
  const f = fixture();
  const output = await measure({ seconds: 0.005, rr: true }, { ble: f.ble, cleanupMs: 10 });
  assert.equal(output.result.ok, true);
  assert.equal(output.code, 0);
  assert.deepEqual(output.result.rr_ms, [1000, 1000]);
  assert.deepEqual(f.events, ['unsubscribe', 'disconnect']);
  assert.equal(f.hrm.listenerCount('data'), 0);
});

test('watchdog during discovery finalizes through disconnect (#12)', async () => {
  const f = fixture(); f.ble.discoverHr = () => new Promise(() => {});
  const output = await measure({ seconds: 1 }, { ble: f.ble, deadlineMs: 10 });
  assert.equal(output.result.error, 'timeout');
  assert.equal(output.code, 2);
  assert.deepEqual(f.events, ['disconnect']);
  assert.equal(f.peripheral.listenerCount('disconnect'), 0);
});

test('unsubscribe timeout cannot skip disconnect (#12)', async () => {
  const f = fixture();
  f.hrm.unsubscribeAsync = () => { f.events.push('unsubscribe'); return new Promise(() => {}); };
  const output = await measure({ seconds: 0.005 }, { ble: f.ble, cleanupMs: 10 });
  assert.equal(output.result.ok, true);
  assert.equal(output.result.cleanup, 'deadline_reached');
  assert.deepEqual(f.events, ['unsubscribe', 'disconnect']);
});

test('signal during subscribe invalidates callbacks and finalizes once (#12)', async () => {
  const f = fixture(); const controller = new AbortController();
  f.hrm.subscribeAsync = () => {
    controller.abort(Object.assign(new Error('interrupted'), { code: 'interrupted' }));
    return new Promise(() => {});
  };
  const output = await measure({ seconds: 1 }, { ble: f.ble, signal: controller.signal, cleanupMs: 10 });
  f.hrm.emit('data', Buffer.from([0x10, 60, 0, 4]));
  assert.equal(output.result.error, 'interrupted');
  assert.equal(output.result.samples.rrAccepted, 0);
  assert.deepEqual(f.events, ['unsubscribe', 'disconnect']);
  assert.equal(f.hrm.listenerCount('data'), 0);
});

test('failed ECG start still sends stop, unsubscribes, then disconnects (#12)', async () => {
  const f = fixture();
  const control = { writeAsync: async command => {
    if (command.equals(pmd.ECG_START_COMMAND)) throw new Error('ECG start failed');
    f.events.push('ecg-stop');
  } };
  f.ble.discoverPmd = async () => ({ control, data: f.hrm });
  f.hrm.subscribeAsync = async () => {};
  const output = await measure({ mode: 'ecg', seconds: 0.005 }, { ble: f.ble, cleanupMs: 10 });
  assert.equal(output.result.error, 'capture_failed');
  assert.deepEqual(f.events, ['ecg-stop', 'unsubscribe', 'disconnect']);
});
