package art.arcane.gloss.integrate;

import org.junit.jupiter.api.Test;

import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class MetricReferencesTest {
    @Test
    void onlyRequestedKeysBecomeActive() {
        MetricReferences references = new MetricReferences(8, 1000L);
        references.reference("adapt.player-sessions", 0L);

        assertEquals(Set.of("adapt.player-sessions"), references.active(0L));
    }

    @Test
    void blankKeysAreNeverTracked() {
        MetricReferences references = new MetricReferences(8, 1000L);
        references.reference(null, 0L);
        references.reference("  ", 0L);

        assertEquals(Set.of(), references.active(0L));
        assertEquals(0, references.tracked());
    }

    @Test
    void keysExpireOnceTheWindowHasPassedAndReviveWhenRequestedAgain() {
        MetricReferences references = new MetricReferences(8, 1000L);
        references.reference("iris.generation-time", 0L);

        assertEquals(Set.of(), references.active(1001L));
        assertEquals(0, references.tracked());

        references.reference("iris.generation-time", 2000L);
        assertEquals(Set.of("iris.generation-time"), references.active(2000L));
    }

    @Test
    void theTrackedSetIsBoundedAndKeepsTheMostRecentlyRequestedKeys() {
        MetricReferences references = new MetricReferences(2, 10000L);
        references.reference("a", 10L);
        references.reference("b", 20L);
        references.reference("c", 30L);

        Set<String> active = references.active(30L);
        assertEquals(2, active.size());
        assertTrue(active.contains("c"));
        assertTrue(active.contains("b"));
        assertFalse(active.contains("a"));
        assertEquals(2, references.tracked());
    }

    @Test
    void clearForgetsEverything() {
        MetricReferences references = new MetricReferences(8, 1000L);
        references.referenceAll(Set.of("a", "b"), 0L);
        references.clear();

        assertEquals(Set.of(), references.active(0L));
    }
}
