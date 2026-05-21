'use strict';

// Parser for the standard Bluetooth Heart Rate Measurement characteristic
// (0x2A37). The Polar H10 reports beat-to-beat RR intervals here, which is the
// robust path on Windows/WinRT and the high-quality source for RMSSD (R-wave
// detection is done by the H10 firmware itself).
//
// Flags byte (bit fields):
//   bit0 : HR value format (0 = uint8, 1 = uint16)
//   bit3 : Energy Expended present (uint16, skipped)
//   bit4 : RR-Interval(s) present (each uint16, units of 1/1024 s)
function parseHrm(buf) {
  if (!buf || buf.length < 2) return { hr: null, rr: [] };
  const flags = buf[0];
  let i = 1;

  let hr;
  if (flags & 0x01) {
    if (i + 2 > buf.length) return { hr: null, rr: [] };
    hr = buf.readUInt16LE(i);
    i += 2;
  } else {
    hr = buf[i];
    i += 1;
  }

  if (flags & 0x08) i += 2; // energy expended -> skip

  const rr = [];
  if (flags & 0x10) {
    for (; i + 2 <= buf.length; i += 2) {
      rr.push((buf.readUInt16LE(i) / 1024) * 1000); // 1/1024 s -> ms
    }
  }

  return { hr, rr };
}

module.exports = { parseHrm };
