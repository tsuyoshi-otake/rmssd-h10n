'use strict';
const assert = require('node:assert/strict');
const fs = require('node:fs');
const os = require('node:os');
const path = require('node:path');
const { test } = require('node:test');
const { resolveDeviceSelection } = require('../src/device-selection');

test('explicit device selection persists across normal starts and can be forgotten (#17)', t => {
  const base = path.resolve(os.homedir(), 'tmp');
  fs.mkdirSync(base, { recursive: true });
  const dir = fs.mkdtempSync(path.join(base, 'rmssd-device-test-'));
  const file = path.join(dir, 'device.json');
  t.after(() => {
    if (!path.resolve(dir).startsWith(base + path.sep)) throw new Error('unsafe test cleanup path');
    fs.rmSync(dir, { recursive: true, force: true });
  });

  assert.equal(resolveDeviceSelection({ deviceId: 'AA:BB:CC:DD:EE:FF', saveDevice: true }, file),
    'AA:BB:CC:DD:EE:FF');
  assert.equal(resolveDeviceSelection({}, file), 'AA:BB:CC:DD:EE:FF');
  assert.equal(resolveDeviceSelection({ nameExplicit: true }, file), null);
  assert.equal(resolveDeviceSelection({ forgetDevice: true }, file), null);
  assert.equal(fs.existsSync(file), false);
});
