package art.arcane.gloss.motd;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * A ping is unauthenticated, so nothing may render per ping that does not have to. Only a text
 * function can produce different output next time; everything else is rendered once per revision.
 */
class MotdExtrasMemoTest {

    @Test
    void aPlainEntrysSampleCountsAndVersionAreMemoised() {
        assertFalse(MotdService.extrasVary(new MotdDoc.MotdEntry(List.of("&aHello"), null,
            List.of("&7A line", "&7Another"), "12", "100", "&bGloss")));
    }

    @Test
    void anEntryWhoseSampleOrVersionCallsAFunctionIsRenderedEveryPing() {
        assertTrue(MotdService.extrasVary(new MotdDoc.MotdEntry(List.of("&aHello"), null,
            List.of("|animation.rainbow|"), "12", "100", "&bGloss")));
        assertTrue(MotdService.extrasVary(new MotdDoc.MotdEntry(List.of("&aHello"), null,
            List.of("&7A line"), "12", "100", "|animation.rainbow|")));
        assertTrue(MotdService.extrasVary(new MotdDoc.MotdEntry(List.of("&aHello"), null,
            List.of(), "|animation.rainbow|", "100", "&bGloss")));
    }
}
