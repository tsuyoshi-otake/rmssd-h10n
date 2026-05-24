package dev.otake.rmssdh10n.hrv;

/**
 * Parser for the standard Bluetooth Heart Rate Measurement characteristic
 * (0x2A37). Java port of src/hrm.js — kept byte-for-byte equivalent so the
 * native service derives the same HR/RR the WebView pipeline does.
 *
 * Flags byte (bit fields):
 *   bit0 : HR value format (0 = uint8, 1 = uint16)
 *   bit3 : Energy Expended present (uint16, skipped)
 *   bit4 : RR-Interval(s) present (each uint16, units of 1/1024 s)
 * All multi-byte values are little-endian; bytes are read unsigned.
 */
public final class Hrm {
    /** HR in bpm (-1 when absent) and any RR intervals in ms. */
    public static final class Result {
        public final int hr;       // -1 if not present
        public final double[] rr;  // ms

        Result(int hr, double[] rr) { this.hr = hr; this.rr = rr; }
    }

    private static final double[] NO_RR = new double[0];

    public static Result parse(byte[] d) {
        if (d == null || d.length < 2) return new Result(-1, NO_RR);

        int flags = d[0] & 0xff;
        int i = 1;

        int hr;
        if ((flags & 0x01) != 0) {
            if (i + 2 > d.length) return new Result(-1, NO_RR);
            hr = (d[i] & 0xff) | ((d[i + 1] & 0xff) << 8);
            i += 2;
        } else {
            hr = d[i] & 0xff;
            i += 1;
        }

        if ((flags & 0x08) != 0) i += 2; // energy expended -> skip

        if ((flags & 0x10) != 0) {
            int n = (d.length - i) / 2;
            if (n > 0) {
                double[] rr = new double[n];
                int k = 0;
                for (; i + 2 <= d.length; i += 2) {
                    int raw = (d[i] & 0xff) | ((d[i + 1] & 0xff) << 8);
                    rr[k++] = raw / 1024.0 * 1000.0; // 1/1024 s -> ms
                }
                return new Result(hr, rr);
            }
        }
        return new Result(hr, NO_RR);
    }

    private Hrm() {}
}
