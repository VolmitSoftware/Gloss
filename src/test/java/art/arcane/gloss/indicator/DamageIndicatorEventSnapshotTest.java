package art.arcane.gloss.indicator;

import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;

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
