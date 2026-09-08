package dev.otake.rmssdh10n;

import org.junit.Test;
import static org.junit.Assert.*;

public class DeviceSelectionTest {
    @Test public void explicitSelectionWinsAndIsNormalized() {
        assertEquals("AA:BB:CC:DD:EE:FF", DeviceSelection.resolve("aa:bb:cc:dd:ee:ff", "11:22:33:44:55:66"));
    }
    @Test public void everyRestartPathUsesSavedSelectionAndNeverAnotherNameMatch() {
        assertEquals("11:22:33:44:55:66", DeviceSelection.resolve(null, "11:22:33:44:55:66"));
        assertNull(DeviceSelection.resolve(null, null));
        assertNull(DeviceSelection.normalize("Polar H10 around me"));
    }
    @Test public void displayOnlyExposesSuffix() {
        assertEquals("…EE:FF", DeviceSelection.masked("AA:BB:CC:DD:EE:FF"));
    }
}
