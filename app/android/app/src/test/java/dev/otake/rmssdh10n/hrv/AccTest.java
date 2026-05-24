package dev.otake.rmssdh10n.hrv;

import static org.junit.Assert.assertEquals;

import org.junit.Test;

/** Golden vectors copied verbatim from src/acc.js's self-test. */
public class AccTest {
    private static byte[] bytes(int... v) {
        byte[] b = new byte[v.length];
        for (int i = 0; i < v.length; i++) b[i] = (byte) v[i];
        return b;
    }

    @Test
    public void uncompressedTwoSamples() {
        // type, 8-byte ts, frameType 0x01, then (1000,0,-10) and (998,5,-1).
        byte[] d = bytes(
            0x02, 0, 0, 0, 0, 0, 0, 0, 0, 0x01,
            0xE8, 0x03, /*1000*/ 0x00, 0x00, /*0*/ 0xF6, 0xFF, /*-10*/
            0xE6, 0x03, /*998*/ 0x05, 0x00, /*5*/ 0xFF, 0xFF  /*-1*/);
        Acc.Frame f = Acc.parse(d);
        assertEquals(2, f.samples.size());
        assertEquals(1000, f.samples.get(0).x);
        assertEquals(-10, f.samples.get(0).z);
        assertEquals(5, f.samples.get(1).y);
        assertEquals(-1, f.samples.get(1).z);
    }

    @Test
    public void compressedDeltaFrame() {
        // ref (1000,0,0) + 2 samples, 4-bit deltas:
        //   s1 = +1,+2,-1 -> (1001,2,-1); s2 = -1,0,+1 -> (1000,2,0)
        // packed LSB-first: [0x1,0x2,0xF, 0xF,0x0,0x1] -> 0x21,0xFF,0x10
        byte[] d = bytes(
            0x02, 0, 0, 0, 0, 0, 0, 0, 0, 0x02,
            0xE8, 0x03, /*1000*/ 0x00, 0x00, /*0*/ 0x00, 0x00, /*0*/
            0x04, 0x02, 0x21, 0xFF, 0x10);
        Acc.Frame f = Acc.parse(d);
        assertEquals(3, f.samples.size());
        assertEquals(1000, f.samples.get(0).x);
        assertEquals(1001, f.samples.get(1).x);
        assertEquals(2, f.samples.get(1).y);
        assertEquals(-1, f.samples.get(1).z);
        assertEquals(1000, f.samples.get(2).x);
        assertEquals(2, f.samples.get(2).y);
        assertEquals(0, f.samples.get(2).z);
    }

    @Test
    public void wrongTypeReturnsNull() {
        assertEquals(null, Acc.parse(bytes(0x01, 0, 0, 0, 0, 0, 0, 0, 0, 0x01)));
    }
}
