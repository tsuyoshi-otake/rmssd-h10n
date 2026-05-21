#!/usr/bin/env node
'use strict';

// HR-service diagnostic: subscribe to the standard Heart Rate Measurement
// characteristic (0x2A37) and print HR + RR intervals. This is the robust path
// on Windows/WinRT and is sufficient for RMSSD. Auto-exits after ~12 s.
const ble = require('../src/ble');
const pmd = require('../src/pmd');
const { parseHrm } = require('../src/hrm');

const RUN_MS = Number(process.argv[2]) || 12000;
const log = (...a) => console.log(`[${new Date().toLocaleTimeString()}]`, ...a);

(async () => {
  const peripheral = await ble.scanAndConnect({ nameMatch: 'polar', timeoutMs: 25000, log });
  const { characteristics } = await peripheral.discoverSomeServicesAndCharacteristicsAsync(
    [pmd.HR_SERVICE],
    [pmd.HR_MEASUREMENT]
  );
  const hrm = characteristics[0];
  if (!hrm) throw new Error('HR Measurement characteristic not found');

  let beats = 0;
  hrm.on('data', (buf) => {
    const { hr, rr } = parseHrm(buf);
    beats += rr.length;
    log(`HR ${hr} bpm  RR ${rr.map((x) => x.toFixed(0)).join(',') || '(none)'} ms`);
  });
  await hrm.subscribeAsync();
  log('subscribed to HR Measurement (0x2A37)');

  setTimeout(async () => {
    log(`--- done: ${beats} RR intervals received ---`);
    try { await peripheral.disconnectAsync(); } catch (_) {}
    process.exit(0);
  }, RUN_MS);
})().catch((e) => {
  console.error('Fatal:', e.message);
  process.exit(1);
});
