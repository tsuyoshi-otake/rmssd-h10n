package dev.otake.rmssdh10n.hrv;

import static org.junit.Assert.assertEquals;

import org.junit.Test;

public class HrmTest {
    private static byte[] bytes(int... v) {
        byte[] b = new byte[v.length];
        for (int i = 0; i < v.length; i++) b[i] = (byte) v[i];
        return b;
    }

    @Test
    public void uint8HrWithTwoRr() {
        // flags=0x10 (RR present, HR uint8), hr=60, rr raw 1024 (=1000ms) and 820.
        Hrm.Result r = Hrm.parse(bytes(0x10, 60, 0x00, 0x04, 0x34, 0x03));
        assertEquals(60, r.hr);
        assertEquals(2, r.rr.length);
        assertEquals(1000.0, r.rr[0], 1e-6);
        assertEquals(820 / 1024.0 * 1000.0, r.rr[1], 1e-6);
    }

    @Test
    public void uint16Hr() {
        // flags=0x01 (HR uint16), hr=300 (0x012C LE), no RR.
        Hrm.Result r = Hrm.parse(bytes(0x01, 0x2C, 0x01));
        assertEquals(300, r.hr);
        assertEquals(0, r.rr.length);
    }

    @Test
    public void energyExpendedSkipped() {
        // flags=0x18 (energy + RR), hr uint8=70, energy 2 bytes skipped, then rr 1024.
        Hrm.Result r = Hrm.parse(bytes(0x18, 70, 0xAA, 0xBB, 0x00, 0x04));
        assertEquals(70, r.hr);
        assertEquals(1, r.rr.length);
        assertEquals(1000.0, r.rr[0], 1e-6);
    }

    @Test
    public void tooShort() {
        Hrm.Result r = Hrm.parse(bytes(0x00));
        assertEquals(-1, r.hr);
        assertEquals(0, r.rr.length);
    }
}
