package dev.otake.rmssdh10n.hrv;

import java.util.ArrayList;
import java.util.List;

/**
 * Polar Measurement Data (PMD) accelerometer support for the Polar H10. Java
 * port of src/acc.js — same UUIDs, START command and frame parser, so the
 * native service streams identical x/y/z samples (mg) for posture/steps.
 *
 * Notification layout (PMD spec):
 *   byte[0]      measurement type (0x02 = ACC)
 *   byte[1..8]   timestamp, uint64 LE, nanoseconds
 *   byte[9]      frame type (0x00/0x01 = uncompressed, 0x02 = compressed delta)
 *   byte[10..]   samples
 * Uncompressed: each sample is 3 x int16 LE (x, y, z) in mg.
 * Compressed delta: a 3 x int16 LE reference sample, then repeated dumps of
 *   [deltaSize][sampleCount][bit-packed signed deltas, LSB-first, x/y/z interleaved].
 */
public final class Acc {
    // PMD service UUIDs (128-bit, lowercase dashed — the BLE form the app uses).
    public static final String PMD_SERVICE = "fb005c80-02e7-f387-1cad-8acd2d8df0c8";
    public static final String PMD_CONTROL = "fb005c81-02e7-f387-1cad-8acd2d8df0c8"; // write + indicate
    public static final String PMD_DATA    = "fb005c82-02e7-f387-1cad-8acd2d8df0c8"; // notify

    public static final int MEAS_TYPE_ACC = 0x02;
    private static final int ACC_CHANNELS = 3;
    private static final int ACC_REF_BITS = 16;

    // START command for the PMD control point (TLV settings, little-endian):
    //   0x02 0x02              REQUEST_MEASUREMENT_START, type = ACC
    //   0x02 0x01 0x02 0x00    RANGE       len 1, value 2  (±2 G)
    //   0x00 0x01 0x19 0x00    SAMPLE_RATE len 1, value 25 (0x0019 Hz)
    //   0x01 0x01 0x10 0x00    RESOLUTION  len 1, value 16 (bits)
    public static final byte[] ACC_START_COMMAND = {
        0x02, 0x02,
        0x02, 0x01, 0x02, 0x00,
        0x00, 0x01, 0x19, 0x00,
        0x01, 0x01, 0x10, 0x00,
    };
    public static final byte[] ACC_STOP_COMMAND = { 0x03, 0x02 };
    public static final int ACC_SAMPLE_RATE = 25; // Hz

    public static final class Sample {
        public final int x, y, z; // mg
        public Sample(int x, int y, int z) { this.x = x; this.y = y; this.z = z; }
    }

    public static final class Frame {
        public final long timestampNs;
        public final int frameType;
        public final List<Sample> samples;
        Frame(long ts, int ft, List<Sample> s) { timestampNs = ts; frameType = ft; samples = s; }
    }

    private static int u8(byte[] d, int i) { return d[i] & 0xff; }

    private static int i16le(byte[] d, int off) {
        return (short) ((d[off] & 0xff) | ((d[off + 1] & 0xff) << 8));
    }

    private static long u64le(byte[] d, int off) {
        long v = 0;
        for (int k = 0; k < 8; k++) v |= ((long) (d[off + k] & 0xff)) << (8 * k);
        return v;
    }

    // Read `width` bits starting at absolute bit position `bitPos` within d (from
    // base), LSB-first (bit 0 = least-significant bit of the first byte). Unsigned.
    private static int readBits(byte[] d, int base, int bitPos, int width) {
        int v = 0;
        for (int k = 0; k < width; k++) {
            int bit = bitPos + k;
            int b = d[base + (bit >> 3)] & 0xff;
            v |= ((b >> (bit & 7)) & 1) << k;
        }
        return v;
    }

    private static int signExtend(int v, int width) {
        return (v & (1 << (width - 1))) != 0 ? v - (1 << width) : v;
    }

    public static Frame parse(byte[] d) {
        if (d == null || d.length < 10) return null;
        if (u8(d, 0) != MEAS_TYPE_ACC) return null;

        long timestampNs = u64le(d, 1);
        int frameType = u8(d, 9);
        List<Sample> samples = new ArrayList<>();
        int off = 10;

        if (frameType == 0x02) {
            // Compressed delta frame.
            if (off + ACC_CHANNELS * 2 > d.length) return new Frame(timestampNs, frameType, samples);
            int[] cur = { i16le(d, off), i16le(d, off + 2), i16le(d, off + 4) };
            off += ACC_CHANNELS * (ACC_REF_BITS / 8);
            samples.add(new Sample(cur[0], cur[1], cur[2]));

            while (off + 2 <= d.length) {
                int deltaSize = u8(d, off);
                int count = u8(d, off + 1);
                off += 2;
                if (deltaSize == 0 || count == 0) break;
                int totalBits = deltaSize * ACC_CHANNELS * count;
                int need = off + (int) Math.ceil(totalBits / 8.0);
                if (need > d.length) break;
                int bitPos = 0;
                for (int s = 0; s < count; s++) {
                    for (int ch = 0; ch < ACC_CHANNELS; ch++) {
                        int delta = signExtend(readBits(d, off, bitPos, deltaSize), deltaSize);
                        bitPos += deltaSize;
                        cur[ch] += delta;
                    }
                    samples.add(new Sample(cur[0], cur[1], cur[2]));
                }
                off = need;
            }
        } else {
            // Uncompressed: 3 x int16 LE per sample.
            for (; off + 6 <= d.length; off += 6) {
                samples.add(new Sample(i16le(d, off), i16le(d, off + 2), i16le(d, off + 4)));
            }
        }
        return new Frame(timestampNs, frameType, samples);
    }

    private Acc() {}
}
