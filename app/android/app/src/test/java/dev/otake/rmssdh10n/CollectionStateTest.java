package dev.otake.rmssdh10n;

import org.junit.Test;
import static org.junit.Assert.*;

public class CollectionStateTest {
    @Test public void onlyAConnectedFreshMeasurementIsReportedAsReceiving() {
        for (boolean fresh : new boolean[]{false,true})
            for (boolean stalled : new boolean[]{false,true})
                assertEquals(CollectionState.WAITING,CollectionState.from(false,fresh,stalled));
        assertEquals(CollectionState.WARMING,CollectionState.from(true,false,false));
        assertEquals(CollectionState.STALLED,CollectionState.from(true,false,true));
        assertEquals(CollectionState.RECEIVING,CollectionState.from(true,true,false));
        assertEquals(CollectionState.RECEIVING,CollectionState.from(true,true,true));
    }
}
