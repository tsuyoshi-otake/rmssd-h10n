#!/usr/bin/env node
'use strict';
const { measure } = require('../src/measurement');
const { localIso } = require('../src/time');
const { resolveDeviceSelection } = require('../src/device-selection');

function parseArgs(argv) {
  const o = { seconds: 30, name: 'polar', mode: 'hr-rr', rr: false, pretty: false, scanTimeout: 30000 };
  for (let i = 2; i < argv.length; i++) {
    switch (argv[i]) {
      case '--seconds': case '-s': o.seconds = Number(argv[++i]); break;
      case '--name': o.name = argv[++i]; o.nameExplicit = true; break;
      case '--device': o.deviceId = argv[++i]; break;
      case '--ecg': o.mode = 'ecg'; break;
      case '--rr': o.rr = true; break;
      case '--pretty': o.pretty = true; break;
      case '--scan-timeout': o.scanTimeout = Number(argv[++i]); break;
      case '--help': case '-h':
        process.stderr.write('Usage: node tools/measure.js [--seconds N] [--rr] [--ecg] [--pretty] [--name str] [--device ID]\n');
        process.exit(0);
    }
  }
  return o;
}


async function main() {
  const opts = parseArgs(process.argv);
  const controller = new AbortController();
  const interrupt = () => controller.abort(Object.assign(new Error('Measurement interrupted'), { code: 'interrupted' }));
  process.once('SIGINT', interrupt);
  process.once('SIGTERM', interrupt);
  let output;
  try {
    opts.deviceId = resolveDeviceSelection(opts);
    output = await measure(opts, { signal: controller.signal,
      log: (...args) => process.stderr.write('[measure] ' + args.join(' ') + '\n') });
  } catch (error) {
    output = { result: { ok: false, error: 'fatal', message: error.message, finishedAt: localIso() }, code: 2 };
  } finally {
    process.removeListener('SIGINT', interrupt);
    process.removeListener('SIGTERM', interrupt);
  }
  // measure resolves only AFTER bounded BLE cleanup, including on watchdog/signals.
  process.stdout.write(JSON.stringify(output.result, null, opts.pretty ? 2 : 0) + '\n', () => process.exit(output.code));
}

if (require.main === module) main();
module.exports = { parseArgs };
