'use strict';

const noble = require('@abandonware/noble');
const pmd = require('./pmd');

function waitForPoweredOn(timeoutMs = 10000) {
  if (noble.state === 'poweredOn') return Promise.resolve();
  return new Promise((resolve, reject) => {
    const timer = setTimeout(() => {
      noble.removeListener('stateChange', onState);
      reject(new Error(`Bluetooth adapter not poweredOn (state=${noble.state}) within ${timeoutMs} ms`));
    }, timeoutMs);
    function onState(state) {
      if (state === 'poweredOn') {
        clearTimeout(timer);
        noble.removeListener('stateChange', onState);
        resolve();
      }
    }
    noble.on('stateChange', onState);
  });
}

/**
 * Scan for a Polar H10 (by name fragment) and connect.
 * @param {object} opts
 * @param {string} opts.nameMatch case-insensitive substring of the device name (default "polar")
 * @param {number} opts.timeoutMs scan timeout
 * @param {(msg:string)=>void} opts.log
 * @returns {Promise<import('@abandonware/noble').Peripheral>}
 */
async function scanAndConnect({ nameMatch = 'polar', timeoutMs = 30000, log = () => {} } = {}) {
  await waitForPoweredOn();
  log(`Scanning for "${nameMatch}"...`);

  const peripheral = await new Promise((resolve, reject) => {
    const timer = setTimeout(async () => {
      noble.removeListener('discover', onDiscover);
      await noble.stopScanningAsync().catch(() => {});
      reject(new Error(`No matching device found within ${timeoutMs} ms`));
    }, timeoutMs);

    async function onDiscover(p) {
      const name = (p.advertisement && p.advertisement.localName) || '';
      // Polar H10 interleaves name-less and named advertisement packets, so we
      // must keep receiving duplicates (allowDuplicates=true) until the named
      // one arrives — otherwise the first name-less packet hides the match.
      if (name.toLowerCase().includes(nameMatch.toLowerCase())) {
        clearTimeout(timer);
        noble.removeListener('discover', onDiscover);
        await noble.stopScanningAsync().catch(() => {});
        log(`Found ${name} (${p.address || p.id}), connecting...`);
        resolve(p);
      }
    }

    noble.on('discover', onDiscover);
    noble.startScanningAsync([], true).catch((err) => {
      clearTimeout(timer);
      noble.removeListener('discover', onDiscover);
      reject(err);
    });
  });

  // Bound the connect so a stuck GATT connection cannot hang the caller forever.
  const connectTimeoutMs = 15000;
  try {
    await Promise.race([
      peripheral.connectAsync(),
      new Promise((_, reject) =>
        setTimeout(() => reject(new Error(`connect timed out after ${connectTimeoutMs} ms`)), connectTimeoutMs)
      ),
    ]);
  } catch (err) {
    // The losing connectAsync may still complete AFTER the timeout. A late success
    // would silently hold the single-connection H10 (it stops advertising, so no
    // rescan ever finds it again — only a Bluetooth toggle or re-strapping recovers).
    // Always release the peripheral before surfacing the failure.
    await disconnectWithTimeout(peripheral, 4000);
    throw err;
  }
  log('Connected.');
  return peripheral;
}

/**
 * Discover the PMD control + data characteristics (and HR measurement if present).
 * @returns {Promise<{ control: any, data: any, hr: any|null }>}
 */
async function discoverPmd(peripheral) {
  const { characteristics } = await peripheral.discoverSomeServicesAndCharacteristicsAsync(
    [pmd.PMD_SERVICE, pmd.HR_SERVICE],
    [pmd.PMD_CONTROL, pmd.PMD_DATA, pmd.HR_MEASUREMENT]
  );

  const byUuid = {};
  for (const c of characteristics) byUuid[c.uuid] = c;

  const control = byUuid[pmd.PMD_CONTROL];
  const data = byUuid[pmd.PMD_DATA];
  const hr = byUuid[pmd.HR_MEASUREMENT] || null;

  if (!control || !data) {
    throw new Error('PMD characteristics not found — is this a Polar H10 with firmware exposing PMD?');
  }
  return { control, data, hr };
}

/**
 * Disconnect a peripheral but never hang: if the GATT disconnect does not
 * resolve (common when the device already dropped on WinRT), resolve anyway
 * after `ms` so shutdown can proceed.
 */
function disconnectWithTimeout(peripheral, ms = 4000) {
  return Promise.race([
    peripheral.disconnectAsync().catch(() => {}),
    new Promise((resolve) => setTimeout(resolve, ms)),
  ]);
}

/**
 * Discover the standard Heart Rate Measurement characteristic (0x2A37), which
 * carries beat-to-beat RR intervals on the Polar H10.
 * @returns {Promise<{ hrm: any }>}
 */
async function discoverHr(peripheral) {
  const { characteristics } = await peripheral.discoverSomeServicesAndCharacteristicsAsync(
    [pmd.HR_SERVICE],
    [pmd.HR_MEASUREMENT]
  );
  const hrm = characteristics.find((c) => c.uuid === pmd.HR_MEASUREMENT) || characteristics[0];
  if (!hrm) throw new Error('Heart Rate Measurement characteristic (0x2A37) not found');
  return { hrm };
}

/**
 * Discover the standard Battery Level characteristic (0x2A19, Battery Service
 * 0x180F). Optional: returns null when the device doesn't expose it, so a
 * missing/failed battery service never breaks the HR session.
 * @returns {Promise<any|null>} the battery-level characteristic, or null
 */
async function discoverBattery(peripheral) {
  const { characteristics } = await peripheral.discoverSomeServicesAndCharacteristicsAsync(
    [pmd.BATTERY_SERVICE],
    [pmd.BATTERY_LEVEL]
  );
  return characteristics.find((c) => c.uuid === pmd.BATTERY_LEVEL) || null;
}

module.exports = { noble, scanAndConnect, discoverPmd, discoverHr, discoverBattery, disconnectWithTimeout };
