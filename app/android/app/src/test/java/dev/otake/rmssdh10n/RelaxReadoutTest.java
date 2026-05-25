package dev.otake.rmssdh10n;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

public class RelaxReadoutTest {
    @Test public void heartRateOnly() {
        assertEquals("心拍60。", RelaxReadout.format(60.0, null, null, null, null, null, null));
    }

    @Test public void respirationOnlyWhenConfident() {
        assertFalse(RelaxReadout.format(60.0, 14.0, 0.20, null, null, null, null).contains("呼吸"));
        assertTrue(RelaxReadout.format(60.0, 14.0, 0.35, null, null, null, null).contains("呼吸14"));
        // unknown confidence is not a reason to hide it
        assertTrue(RelaxReadout.format(60.0, 14.0, null, null, null, null, null).contains("呼吸14"));
    }

    @Test public void rmssdValueAndBaselineDirection() {
        assertTrue(RelaxReadout.format(60.0, null, null, 42.3, null, null, null).contains("RMSSDは42.3"));
        assertTrue(RelaxReadout.format(60.0, null, null, 40.0, 40.0, 30.0, null).contains("基準より高め"));
        assertTrue(RelaxReadout.format(60.0, null, null, 20.0, 20.0, 30.0, null).contains("基準より低め"));
        assertTrue(RelaxReadout.format(60.0, null, null, 30.0, 30.0, 30.0, null).contains("基準どおり"));
        // no baseline → no direction hint
        assertFalse(RelaxReadout.format(60.0, null, null, 30.0, 30.0, null, null).contains("基準"));
    }

    @Test public void stateLabelAppendedWhenPresent() {
        assertTrue(RelaxReadout.format(60.0, null, null, null, null, null, "リラックス・回復").contains("リラックス・回復"));
        assertEquals("心拍60。", RelaxReadout.format(60.0, null, null, null, null, null, "")); // empty omitted
    }

    @Test public void containsNoEmoji() {
        String s = RelaxReadout.format(72.0, 12.0, 0.9, 45.6, 50.0, 40.0, "集中");
        for (int i = 0; i < s.length(); ) {
            int cp = s.codePointAt(i);
            assertFalse("astral/emoji U+" + Integer.toHexString(cp), cp >= 0x1F000);
            assertFalse("pictograph U+" + Integer.toHexString(cp), cp >= 0x2600 && cp <= 0x27BF);
            i += Character.charCount(cp);
        }
    }
}
