#!/usr/bin/env node
'use strict';

// ECG diagnostic: connect to the H10, subscribe to PMD control (indications)
// and data (notifications), send the ECG start command, and dump what comes
// back — control responses and raw ECG frame stats — for ~12 s, then exit.
const ble = require('../src/ble');
const pmd = require('../src/pmd');

const RUN_MS = Number(process.argv[2]) || 12000;
const log = (...a) => console.log(`[${new Date().toLocaleTimeString()}]`, ...a);

(async () => {
  const peripheral = await ble.scanAndConnect({ nameMatch: 'polar', timeoutMs: 25000, log });
  const { control, data } = await ble.discoverPmd(peripheral);

  log('control props:', JSON.stringify(control.properties), '| data props:', JSON.stringify(data.properties));
  try {
    const feat = await control.readAsync();
    log('control READ (feature support):', feat.toString('hex'));
  } catch (e) {
    log('control read err:', e.message);
  }

  let dataFrames = 0;
  let totalSamples = 0;
  let firstFramesShown = 0;
  let lastSample = null;

  control.on('data', (buf) => {
    log('CONTROL response:', buf.toString('hex'));
  });
  await control.subscribeAsync().catch((e) => log('control subscribe err:', e.message));

  data.on('data', (buf) => {
    dataFrames++;
    if (firstFramesShown < 3) {
      firstFramesShown++;
      log(`DATA frame #${dataFrames} len=${buf.length} head=${buf.subarray(0, 12).toString('hex')}`);
    }
    const parsed = pmd.parseEcg(buf);
    if (parsed) {
      totalSamples += parsed.samples.length;
      if (parsed.samples.length) lastSample = parsed.samples[parsed.samples.length - 1];
    } else if (dataFrames <= 3) {
      log(`  -> parseEcg returned null for frame type byte ${buf[0]}`);
    }
  });
  await data.subscribeAsync();
  log('subscribed to PMD data');

  log('writing ECG start command:', pmd.ECG_START_COMMAND.toString('hex'));
  try {
    await control.writeAsync(pmd.ECG_START_COMMAND, false);
    log('write (with response) OK');
  } catch (e) {
    log('write WITH response failed:', e.message, '-> retrying without response');
    try {
      await control.writeAsync(pmd.ECG_START_COMMAND, true);
      log('write (without response) OK');
    } catch (e2) {
      log('write without response also failed:', e2.message);
    }
  }

  setTimeout(async () => {
    log('--- summary ---');
    log(`data frames: ${dataFrames}, total ECG samples: ${totalSamples}, last sample(uV): ${lastSample}`);
    log(`approx sample rate: ${(totalSamples / (RUN_MS / 1000)).toFixed(1)} Hz (expect ~130)`);
    try {
      await control.writeAsync(pmd.ECG_STOP_COMMAND, false);
      await peripheral.disconnectAsync();
    } catch (_) {}
    process.exit(0);
  }, RUN_MS);
})().catch((e) => {
  console.error('Fatal:', e.message);
  process.exit(1);
});
