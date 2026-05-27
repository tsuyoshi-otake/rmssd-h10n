'use strict';

// Polar Measurement Data (PMD) service — UUIDs without dashes (noble normalizes to lowercase, no dashes).
const PMD_SERVICE = 'fb005c8002e7f3871cad8acd2d8df0c8';
const PMD_CONTROL = 'fb005c8102e7f3871cad8acd2d8df0c8'; // write + indicate
const PMD_DATA = 'fb005c8202e7f3871cad8acd2d8df0c8'; // notify

// Standard Heart Rate service (fallback / HR display).
const HR_SERVICE = '180d';
const HR_MEASUREMENT = '2a37';

// Standard Battery service — battery level % (0x180F / 0x2A19, single uint8 0-100).
const BATTERY_SERVICE = '180f';
const BATTERY_LEVEL = '2a19';

// ECG settings: sample rate 130 Hz, resolution 14 bit.
// Frame layout of the start command (see Polar SDK PMD spec):
//   0x02            = REQUEST_MEASUREMENT_START
//   0x00            = measurement type ECG
//   0x00 0x01 0x82 0x00 = setting SAMPLE_RATE, len 1, value 130 (0x0082, little-endian)
//   0x01 0x01 0x0E 0x00 = setting RESOLUTION,  len 1, value 14  (0x000E, little-endian)
const ECG_START_COMMAND = Buffer.from([
  0x02, 0x00, 0x00, 0x01, 0x82, 0x00, 0x01, 0x01, 0x0e, 0x00,
]);
const ECG_STOP_COMMAND = Buffer.from([0x03, 0x00]);

const ECG_SAMPLE_RATE = 130; // Hz

// PMD measurement type identifiers (first byte of a data notification).
const MEAS_TYPE_ECG = 0x00;

/**
 * Parse a PMD ECG data notification into an array of microvolt samples.
 *
 * Notification layout:
 *   byte[0]      measurement type (0x00 = ECG)
 *   byte[1..8]   timestamp, uint64 little-endian, nanoseconds
 *   byte[9]      frame type (0x00 = ECG raw)
 *   byte[10..]   samples, each 3 bytes signed 24-bit little-endian (microvolts)
 *
 * @param {Buffer} data raw notification payload
 * @returns {{ timestampNs: bigint, samples: number[] } | null}
 */
function parseEcg(data) {
  if (!data || data.length < 10) return null;
  if (data[0] !== MEAS_TYPE_ECG) return null;

  const timestampNs = data.readBigUInt64LE(1);
  const frameType = data[9];
  if (frameType !== 0x00) return null; // only raw ECG frames handled here

  const samples = [];
  for (let i = 10; i + 3 <= data.length; i += 3) {
    // Reconstruct 24-bit two's-complement little-endian value.
    let v = data[i] | (data[i + 1] << 8) | (data[i + 2] << 16);
    if (v & 0x800000) v -= 0x1000000; // sign-extend
    samples.push(v); // microvolts
  }
  return { timestampNs, samples };
}

module.exports = {
  PMD_SERVICE,
  PMD_CONTROL,
  PMD_DATA,
  HR_SERVICE,
  HR_MEASUREMENT,
  BATTERY_SERVICE,
  BATTERY_LEVEL,
  ECG_START_COMMAND,
  ECG_STOP_COMMAND,
  ECG_SAMPLE_RATE,
  MEAS_TYPE_ECG,
  parseEcg,
};
