package art.arcane.gloss.indicator;

import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

class DamageIndicatorEventSnapshotTest {
    @Test
    void conditionValuesExposeKnownCriticalDamage() {
        DamageIndicatorEventSnapshot snapshot = new DamageIndicatorEventSnapshot(
            true,
            "entity_attack",
            8.0D,
            true,
            true,
            "player",
            new DamageIndicatorEventSnapshot.EntityState(Map.of()));

        Map<String, Object> values = snapshot.values(null, null, 6.0D);

        assertEquals(true, values.get("event.critical"));
        assertEquals(true, values.get("event.criticalKnown"));
    }

    /** Sourceless damage (fall, lava, drowning) must not rebuild the 44-entry default map. */
    @Test
    void theEmptyEntityStateIsASharedConstant() {
        assertSame(DamageIndicatorEventSnapshot.EntityState.empty(),
            DamageIndicatorEventSnapshot.EntityState.empty());
    }

    @Test
    void theEmptyEntityStateStillExposesEveryDefaultKey() {
        DamageIndicatorEventSnapshot snapshot = new DamageIndicatorEventSnapshot(
            true, "fall", 4.0D, false, true, "",
            DamageIndicatorEventSnapshot.EntityState.empty());

        Map<String, Object> values = snapshot.values(null, null, 4.0D);

        assertEquals(false, values.get("source.present"));
        assertEquals("", values.get("source.name"));
        assertEquals(0.0D, values.get("source.health"));
        assertEquals("", values.get("source.group"));
        assertEquals(false, values.get("subject.present"));
        assertTrue(values.containsKey("subject.clientViewDistance"));
    }

    @Test
    void conditionValuesExposeUnknownCriticalityWithoutClaimingACrit() {
        DamageIndicatorEventSnapshot snapshot = new DamageIndicatorEventSnapshot(
            true,
            "entity_attack",
            8.0D,
            false,
            false,
            "player",
            new DamageIndicatorEventSnapshot.EntityState(Map.of()));

        Map<String, Object> values = snapshot.values(null, null, 6.0D);

        assertEquals(false, values.get("event.critical"));
        assertEquals(false, values.get("event.criticalKnown"));
    }
}
