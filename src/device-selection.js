'use strict';

const fs = require('node:fs');
const path = require('node:path');

function validDeviceId(value) { return typeof value === 'string' && /^[a-zA-Z0-9:-]{4,64}$/.test(value); }

function resolveDeviceSelection(opts, file = path.join(__dirname, '..', 'data', 'device.json')) {
  if (opts.forgetDevice) {
    try { fs.unlinkSync(file); } catch (error) { if (error.code !== 'ENOENT') throw error; }
  }
  if (opts.deviceId != null) {
    if (!validDeviceId(opts.deviceId)) throw new Error('--device must be a Bluetooth address or device identifier (4–64 letters/digits/colon/hyphen)');
    if (opts.saveDevice) {
      fs.mkdirSync(path.dirname(file), { recursive: true });
      const temporary = `${file}.${process.pid}.tmp`;
      fs.writeFileSync(temporary, JSON.stringify({ deviceId: opts.deviceId }));
      fs.renameSync(temporary, file);
    }
    return opts.deviceId;
  }
  if (opts.nameExplicit || opts.forgetDevice) return null;
  try {
    const selected = JSON.parse(fs.readFileSync(file, 'utf8')).deviceId;
    return validDeviceId(selected) ? selected : null;
  } catch (error) {
    if (error.code === 'ENOENT' || error instanceof SyntaxError) return null;
    throw error;
  }
}

module.exports = { validDeviceId, resolveDeviceSelection };
