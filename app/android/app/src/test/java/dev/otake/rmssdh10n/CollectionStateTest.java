package dev.otake.rmssdh10n;

import org.junit.Test;
import static org.junit.Assert.*;

public class CollectionStateTest {
    @Test public void onlyAConnectedFreshMeasurementIsReportedAsReceiving() {
        for (boolean unbonded : new boolean[]{false,true}) {
            for (boolean fresh : new boolean[]{false,true})
                for (boolean stalled : new boolean[]{false,true})
                    assertEquals(CollectionState.WAITING,CollectionState.from(false,fresh,stalled,unbonded));
            assertEquals(CollectionState.WARMING,CollectionState.from(true,false,false,unbonded));
            assertEquals(CollectionState.RECEIVING,CollectionState.from(true,true,false,unbonded));
            assertEquals(CollectionState.RECEIVING,CollectionState.from(true,true,true,unbonded));
        }
    }

    @Test public void aSilentLinkToAnUnbondedH10AsksForPairingInsteadOfReStrapping() {
        assertEquals(CollectionState.STALLED,CollectionState.from(true,false,true,false));
        assertEquals(CollectionState.PAIRING,CollectionState.from(true,false,true,true));
    }
}
