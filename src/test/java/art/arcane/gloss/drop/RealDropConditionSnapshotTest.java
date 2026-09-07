package art.arcane.gloss.drop;

import art.arcane.gloss.condition.BoundedConditionErrorCallback;
import art.arcane.gloss.condition.ShowCondition;
import org.bukkit.Material;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Snapshot capture runs once per drop per tick and once per drop event, so the two values that cost
 * more than a field read - the rendered entity name and the world player list - are only paid for
 * when a compiled condition actually names them.
 */
class RealDropConditionSnapshotTest {

    @Test
    void theDefaultPlanNeverRendersTheDropNameOrWalksThePlayerList() {
        DropFakes.WorldFake world = new DropFakes.WorldFake(UUID.randomUUID(), 1000);
        DropFakes.ItemFake item = new DropFakes.ItemFake(world.proxy, DropFakes.stack(Material.STONE, 4));
        RealDropConditionPlan plan = plan("true", ShowCondition.ALWAYS, List.of());

        RealDropConditionSnapshot snapshot =
            RealDropConditionSnapshot.capture(item.proxy, "spawn", plan.fields());

        assertEquals(0, item.calls("getName"));
        assertEquals(0, world.calls("getPlayers"));
        assertFalse(snapshot.values().containsKey("subject.name"));
        assertFalse(snapshot.values().containsKey("world.players"));
        assertEquals("STONE", snapshot.values().get("drop.material"));
    }

    @Test
    void aPlanThatNamesTheExpensiveValuesStillCapturesThem() {
        DropFakes.WorldFake world = new DropFakes.WorldFake(UUID.randomUUID(), 3);
        DropFakes.ItemFake item = new DropFakes.ItemFake(world.proxy, DropFakes.stack(Material.STONE, 4));
        RealDropConditionPlan plan = plan("world.players > 0", ShowCondition.ALWAYS,
            List.of(variant("named", 1, "subject.name == 'Fake Drop'")));

        RealDropConditionSnapshot snapshot =
            RealDropConditionSnapshot.capture(item.proxy, "spawn", plan.fields());

        assertEquals("Fake Drop", snapshot.values().get("subject.name"));
        assertEquals(3.0D, snapshot.values().get("world.players"));
    }

    @Test
    void theConfiguredDropShowConditionCountsAsAReference() {
        RealDropConditionPlan plan = plan("true", ShowCondition.of("subject.name != ''"), List.of());

        assertTrue(plan.fields().subjectName());
        assertFalse(plan.fields().worldPlayers());
    }

    @Test
    void theWorldPlayerCountIsReadOncePerWorldPerTick() {
        DropFakes.WorldFake world = new DropFakes.WorldFake(UUID.randomUUID(), 1000);

        assertEquals(1000, RealDropConditionSnapshot.worldPlayers(world.proxy, 40L));
        assertEquals(1000, RealDropConditionSnapshot.worldPlayers(world.proxy, 40L));
        assertEquals(1, world.calls("getPlayers"));

        assertEquals(1000, RealDropConditionSnapshot.worldPlayers(world.proxy, 41L));
        assertEquals(2, world.calls("getPlayers"));
    }

    private static RealDropConditionPlan plan(String audience, ShowCondition viewerShow,
                                              List<RealDropSettingsDoc.Variant> variants) {
        RealDropSettingsDoc document = new RealDropSettingsDoc(
            RealDropSettingsDoc.CURRENT_SCHEMA_VERSION,
            1L,
            RealDropSettingsDoc.DEFAULTS.presentation(),
            variants,
            new RealDropSettingsDoc.Audience(audience),
            ShowCondition.ALWAYS);
        return RealDropConditionPlan.compile(
            document, true, BoundedConditionErrorCallback.silent(), viewerShow);
    }

    private static RealDropSettingsDoc.Variant variant(String id, int priority, String when) {
        return new RealDropSettingsDoc.Variant(
            id, priority, when, RealDropSettingsDoc.DEFAULTS.presentation());
    }
}
