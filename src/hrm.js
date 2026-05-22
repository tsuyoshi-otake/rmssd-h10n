'use strict';

// Parser for the standard Bluetooth Heart Rate Measurement characteristic
// (0x2A37). The Polar H10 reports beat-to-beat RR intervals here, which is the
// robust path on Windows/WinRT and the high-quality source for RMSSD (R-wave
// detection is done by the H10 firmware itself).
//
// Accepts whatever the BLE layer hands us so the same parser is shared by both
// front-ends: a Node Buffer (noble, desktop) or a DataView (Capacitor BLE on
// Android, whose startNotifications callback delivers a DataView). A plain
// Uint8Array works too. All are normalised to a DataView and read little-endian.
//
// Flags byte (bit fields):
//   bit0 : HR value format (0 = uint8, 1 = uint16)
//   bit3 : Energy Expended present (uint16, skipped)
//   bit4 : RR-Interval(s) present (each uint16, units of 1/1024 s)
function parseHrm(input) {
  let dv;
  if (input instanceof DataView) {
    dv = input;
  } else if (input && input.buffer instanceof ArrayBuffer) {
    // Buffer / Uint8Array: respect byteOffset/length (Node Buffers are slices
    // of a shared, larger ArrayBuffer pool).
    dv = new DataView(input.buffer, input.byteOffset || 0, input.byteLength);
  } else {
    return { hr: null, rr: [] };
  }

  const len = dv.byteLength;
  if (len < 2) return { hr: null, rr: [] };

  const flags = dv.getUint8(0);
  let i = 1;

  let hr;
  if (flags & 0x01) {
    if (i + 2 > len) return { hr: null, rr: [] };
    hr = dv.getUint16(i, true);
    i += 2;
  } else {
    hr = dv.getUint8(i);
    i += 1;
  }

  if (flags & 0x08) i += 2; // energy expended -> skip

  const rr = [];
  if (flags & 0x10) {
    for (; i + 2 <= len; i += 2) {
      rr.push((dv.getUint16(i, true) / 1024) * 1000); // 1/1024 s -> ms
    }
  }

  return { hr, rr };
}

module.exports = { parseHrm };
