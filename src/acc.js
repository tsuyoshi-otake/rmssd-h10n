'use strict';

// Polar Measurement Data (PMD) accelerometer support for the Polar H10.
//
// The H10 carries a 3-axis accelerometer streamed over the PMD service. Because
// the strap sits on the sternum, the gravity vector recovered from a stationary
// reading gives torso orientation (upright / forward-lean / reclined / lying),
// and the high-frequency component gives a movement/activity measure. We reuse
// the same PMD GATT connection the HR path already holds.
//
// Browser-safe: no Node Buffer (this module is bundled into the Capacitor app by
// esbuild). Inputs are normalised to a DataView the same way src/hrm.js does, so
// the parser is shared by a Node self-test (Uint8Array) and Android (DataView).
//
// PMD service UUIDs (128-bit, lowercase dashed — the form @capacitor-community/
// bluetooth-le expects). The desktop noble path uses the dashless form in
// src/pmd.js; these are the same UUIDs.
const PMD_SERVICE = 'fb005c80-02e7-f387-1cad-8acd2d8df0c8';
const PMD_CONTROL = 'fb005c81-02e7-f387-1cad-8acd2d8df0c8'; // write + indicate
const PMD_DATA = 'fb005c82-02e7-f387-1cad-8acd2d8df0c8'; // notify

const MEAS_TYPE_ACC = 0x02;
const ACC_CHANNELS = 3; // x, y, z
const ACC_REF_BITS = 16; // resolution requested below (int16 per axis)

// START command for the PMD control point (TLV settings, little-endian values):
//   0x02            REQUEST_MEASUREMENT_START
//   0x02            measurement type = ACC
//   0x02 0x01 0x02 0x00   RANGE       len 1, value 2  (±2 G)
//   0x00 0x01 0x19 0x00   SAMPLE_RATE len 1, value 25 (0x0019 Hz)
//   0x01 0x01 0x10 0x00   RESOLUTION  len 1, value 16 (bits)
// 25 Hz / ±2 G is ample for posture: gravity dominates and we down-sample anyway.
const ACC_START_COMMAND = new Uint8Array([
  0x02, 0x02,
  0x02, 0x01, 0x02, 0x00,
  0x00, 0x01, 0x19, 0x00,
  0x01, 0x01, 0x10, 0x00,
]);
const ACC_STOP_COMMAND = new Uint8Array([0x03, 0x02]);

const ACC_SAMPLE_RATE = 25; // Hz

// Normalise a Buffer / Uint8Array / DataView to { dv, u8 } (mirrors src/hrm.js).
function toViews(input) {
  if (input instanceof DataView) {
    return { dv: input, u8: new Uint8Array(input.buffer, input.byteOffset, input.byteLength) };
  }
  if (input && input.buffer instanceof ArrayBuffer) {
    const off = input.byteOffset || 0;
    return {
      dv: new DataView(input.buffer, off, input.byteLength),
      u8: new Uint8Array(input.buffer, off, input.byteLength),
    };
  }
  return null;
}

// Read `width` bits starting at absolute bit position `bitPos` within `u8`,
// LSB-first (bit 0 = least-significant bit of the first byte). Returns unsigned.
function readBits(u8, base, bitPos, width) {
  let v = 0;
  for (let k = 0; k < width; k++) {
    const bit = bitPos + k;
    const byte = u8[base + (bit >> 3)];
    v |= ((byte >> (bit & 7)) & 1) << k;
  }
  return v >>> 0;
}

function signExtend(v, width) {
  return (v & (1 << (width - 1))) ? v - (1 << width) : v;
}

/**
 * Parse a PMD ACC data notification into x/y/z samples in milli-G (mg).
 *
 * Notification layout (PMD spec):
 *   byte[0]      measurement type (0x02 = ACC)
 *   byte[1..8]   timestamp, uint64 LE, nanoseconds
 *   byte[9]      frame type (0x00/0x01 = uncompressed, 0x02 = compressed delta)
 *   byte[10..]   samples
 *
 * Uncompressed: each sample is 3 × int16 LE (x, y, z) in mg.
 * Compressed delta: a 3 × int16 LE reference sample, then repeated dumps of
 *   [deltaSize byte][sampleCount byte][bit-packed signed deltas, LSB-first,
 *   x/y/z interleaved]; each sample = previous + delta per axis.
 *
 * @param {Buffer|Uint8Array|DataView} input
 * @returns {{ timestampNs: bigint, frameType: number, samples: {x:number,y:number,z:number}[] } | null}
 */
function parseAcc(input) {
  const v = toViews(input);
  if (!v) return null;
  const { dv, u8 } = v;
  if (dv.byteLength < 10) return null;
  if (dv.getUint8(0) !== MEAS_TYPE_ACC) return null;

  const timestampNs = dv.getBigUint64(1, true);
  const frameType = dv.getUint8(9);
  const samples = [];
  let off = 10;

  if (frameType === 0x02) {
    // Compressed delta frame.
    if (off + ACC_CHANNELS * 2 > dv.byteLength) return { timestampNs, frameType, samples };
    const cur = [dv.getInt16(off, true), dv.getInt16(off + 2, true), dv.getInt16(off + 4, true)];
    off += ACC_CHANNELS * (ACC_REF_BITS / 8);
    samples.push({ x: cur[0], y: cur[1], z: cur[2] });

    while (off + 2 <= dv.byteLength) {
      const deltaSize = u8[off];
      const count = u8[off + 1];
      off += 2;
      if (deltaSize === 0 || count === 0) break;
      const totalBits = deltaSize * ACC_CHANNELS * count;
      const need = off + Math.ceil(totalBits / 8);
      if (need > dv.byteLength) break;
      let bitPos = 0;
      for (let s = 0; s < count; s++) {
        for (let ch = 0; ch < ACC_CHANNELS; ch++) {
          const d = signExtend(readBits(u8, off, bitPos, deltaSize), deltaSize);
          bitPos += deltaSize;
          cur[ch] += d;
        }
        samples.push({ x: cur[0], y: cur[1], z: cur[2] });
      }
      off = need;
    }
  } else {
    // Uncompressed: 3 × int16 LE per sample.
    for (; off + 6 <= dv.byteLength; off += 6) {
      samples.push({
        x: dv.getInt16(off, true),
        y: dv.getInt16(off + 2, true),
        z: dv.getInt16(off + 4, true),
      });
    }
  }
  return { timestampNs, frameType, samples };
}

module.exports = {
  PMD_SERVICE,
  PMD_CONTROL,
  PMD_DATA,
  MEAS_TYPE_ACC,
  ACC_START_COMMAND,
  ACC_STOP_COMMAND,
  ACC_SAMPLE_RATE,
  parseAcc,
};

// ---- self-test: `node src/acc.js` ----------------------------------------
if (require.main === module) {
  const u16le = (n) => [n & 0xff, (n >> 8) & 0xff];
  let fail = 0;
  const eq = (a, b, msg) => { if (a !== b) { console.error(`FAIL ${msg}: ${a} !== ${b}`); fail++; } };

  // 1) uncompressed frame: two samples.
  const unc = new Uint8Array([
    0x02, 0, 0, 0, 0, 0, 0, 0, 0, 0x01,
    ...u16le(1000), ...u16le(0), ...u16le(0xfff6 /* -10 */),
    ...u16le(998), ...u16le(5), ...u16le(0xffff /* -1 */),
  ]);
  const a = parseAcc(unc);
  eq(a.samples.length, 2, 'unc count');
  eq(a.samples[0].x, 1000, 'unc s0.x');
  eq(a.samples[0].z, -10, 'unc s0.z');
  eq(a.samples[1].y, 5, 'unc s1.y');
  eq(a.samples[1].z, -1, 'unc s1.z');

  // 2) compressed delta frame: ref (1000,0,0) + 2 samples, 4-bit deltas
  //    s1 = +1,+2,-1 -> (1001,2,-1); s2 = -1,0,+1 -> (1000,2,0)
  //    packed LSB-first: [0x1,0x2,0xF, 0xF,0x0,0x1] -> 0x21,0xFF,0x10
  const cmp = new Uint8Array([
    0x02, 0, 0, 0, 0, 0, 0, 0, 0, 0x02,
    ...u16le(1000), ...u16le(0), ...u16le(0),
    0x04, 0x02, 0x21, 0xff, 0x10,
  ]);
  const c = parseAcc(cmp);
  eq(c.samples.length, 3, 'cmp count (ref+2)');
  eq(c.samples[0].x, 1000, 'cmp ref.x');
  eq(c.samples[1].x, 1001, 'cmp s1.x');
  eq(c.samples[1].y, 2, 'cmp s1.y');
  eq(c.samples[1].z, -1, 'cmp s1.z');
  eq(c.samples[2].x, 1000, 'cmp s2.x');
  eq(c.samples[2].y, 2, 'cmp s2.y');
  eq(c.samples[2].z, 0, 'cmp s2.z');

  console.log(fail === 0 ? 'acc.js self-test: OK' : `acc.js self-test: ${fail} FAILED`);
  process.exit(fail === 0 ? 0 : 1);
}
