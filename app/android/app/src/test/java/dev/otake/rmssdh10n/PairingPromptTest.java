package dev.otake.rmssdh10n;

import org.junit.Test;
import static org.junit.Assert.*;

public class PairingPromptTest {
    private long now = 5_000;
    private boolean inFront = true;
    private final PairingPrompt prompt = new PairingPrompt(() -> now, () -> inFront);

    @Test public void theFirstPromptIsAllowedWhileTheUserCanAnswer() {
        assertTrue(prompt.allowed());
        assertTrue(prompt.claim());
    }

    @Test public void noPromptIsSpentWhileTheUserCannotAnswer() {
        inFront = false;
        assertFalse(prompt.allowed());
        assertFalse(prompt.claim());
        inFront = true;
        assertTrue("a refused claim must not spend the interval", prompt.claim());
    }

    @Test public void aClaimSpendsTheIntervalForEveryPath() {
        assertTrue(prompt.claim());
        assertFalse(prompt.allowed());
        assertFalse(prompt.claim());
        now += PairingPrompt.MIN_INTERVAL_MS - 1;
        assertFalse(prompt.claim());
        now += 1;
        assertTrue(prompt.allowed());
        assertTrue(prompt.claim());
        assertFalse(prompt.claim());
    }

    @Test public void allowedDoesNotSpendThePrompt() {
        assertTrue(prompt.allowed());
        assertTrue(prompt.allowed());
        assertTrue(prompt.claim());
    }

    @Test public void aPromptShownWithoutAClaimStillSpendsTheInterval() {
        prompt.spent();
        assertFalse(prompt.claim());
        now += PairingPrompt.MIN_INTERVAL_MS;
        assertTrue(prompt.claim());
    }

    @Test public void theIntervalHoldsEvenWhenTheClockStartsAtZero() {
        now = 0;
        assertTrue(prompt.claim());
        now = PairingPrompt.MIN_INTERVAL_MS - 1;
        assertFalse(prompt.claim());
    }

    @Test public void theIntervalIsTenMinutes() {
        assertEquals(10 * 60_000L, PairingPrompt.MIN_INTERVAL_MS);
    }
}
