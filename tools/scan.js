#!/usr/bin/env node
'use strict';

// BLE scan diagnostic: lists every advertising device noble can see for ~20 s.
// Use this to confirm noble works on this PC and to find the H10's exact name.
const noble = require('@abandonware/noble');

const seen = new Map();
const DURATION_MS = Number(process.argv[2]) || 20000;

noble.on('stateChange', (state) => {
  console.log(`[noble] stateChange -> ${state}`);
  if (state === 'poweredOn') {
    console.log('[noble] start scanning (all devices)...');
    noble.startScanningAsync([], true).catch((e) => console.error('scan error:', e.message));
    setTimeout(async () => {
      await noble.stopScanningAsync().catch(() => {});
      console.log(`\n=== ${seen.size} device(s) seen ===`);
      for (const d of seen.values()) {
        console.log(`${(d.name || '(no name)').padEnd(28)} rssi=${d.rssi}  id=${d.id}  svc=[${d.services.join(',')}]`);
      }
      process.exit(0);
    }, DURATION_MS);
  } else {
    console.error(`[noble] adapter not ready (state=${state}). On Windows, Bluetooth must be ON.`);
  }
});

noble.on('discover', (p) => {
  const a = p.advertisement || {};
  seen.set(p.id, {
    id: p.id,
    name: a.localName || '',
    rssi: p.rssi,
    services: a.serviceUuids || [],
  });
  const name = a.localName || '(no name)';
  console.log(`found: ${name.padEnd(28)} rssi=${p.rssi} id=${p.id}`);
});
