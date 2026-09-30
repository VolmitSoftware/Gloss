package art.arcane.gloss.drop;

import org.bukkit.Material;
import org.bukkit.event.entity.EntityRemoveEvent;
import org.bukkit.event.entity.ItemMergeEvent;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The two drop-name event paths, driven through the real service on a headless server: a removal
 * the server never reports as a despawn or a pickup has to drop the tracked item straight away,
 * and a merge has to rename the survivor exactly once, on the tick after the merge, when the
 * merged amount is final.
 */
class DropNameEventBehaviourTest {
    private DropFakes.Harness harness;
    private DropFakes.WorldFake world;
    private DropNameService service;

    @BeforeEach
    void bootHeadlessService() throws ReflectiveOperationException {
        harness = DropFakes.harness("");
        world = new DropFakes.WorldFake(UUID.randomUUID(), 1);
        service = new DropNameService(harness.gloss());
        DropFakes.Harness.setField(service, "listening", true);
        DropFakes.Harness.invoke(service, "refreshRealDropConfig", new Class<?>[0]);
    }

    @AfterEach
    void restore() {
        harness.restore();
    }

    @Test
    void labelTextAndMaterialNamesComeFromTheRealDropsDocument() throws ReflectiveOperationException {
        DropFakes.Harness.setField(service, "realDropDoc", RealDropSettingsDoc.parse("default.json", """
            {"schemaVersion":4,"revision":2,"presentation":{"labels":{
              "format":"&f{type} &8x{count}","names":{"cobblestone":"Cobble"}}}}
            """));
        DropFakes.Harness.invoke(service, "refreshRealDropConfig", new Class<?>[0]);
        DropFakes.ItemFake cobblestone = new DropFakes.ItemFake(world.proxy, DropFakes.stack(Material.COBBLESTONE, 5));
        DropFakes.ItemFake log = new DropFakes.ItemFake(world.proxy, DropFakes.stack(Material.OAK_LOG, 2));

        service.refresh(cobblestone.proxy);
        service.refresh(log.proxy);
        harness.runScheduled();

        assertEquals("\u00a7fCobble \u00a78x5", cobblestone.customName());
        assertEquals("\u00a7fOak Log \u00a78x2", log.customName());
    }

    @Test
    void anEntityRemovalDropsTheTrackedItemImmediately() {
        DropFakes.ItemFake item = new DropFakes.ItemFake(world.proxy, DropFakes.stack(Material.STONE, 2));
        service.refresh(item.proxy);
        assertEquals(1, service.activeCount());

        service.onEntityRemove(new EntityRemoveEvent(item.proxy, EntityRemoveEvent.Cause.DEATH));

        assertEquals(0, service.activeCount());
    }

    @Test
    void aMergeNeverRenamesTheTargetInline() {
        DropFakes.ItemFake source = new DropFakes.ItemFake(world.proxy, DropFakes.stack(Material.STONE, 1));
        DropFakes.ItemFake target = new DropFakes.ItemFake(world.proxy, DropFakes.stack(Material.STONE, 2));

        service.onItemMerge(new ItemMergeEvent(source.proxy, target.proxy));

        assertNull(target.customName());
        assertEquals(0, service.activeCount());
        assertEquals(1, harness.scheduled().size());
        assertEquals(1L, harness.scheduled().getFirst().delayTicks());
    }

    @Test
    void theDeferredMergeRefreshRenamesTheSurvivorOnceWithTheSettledAmount() {
        DropFakes.ItemFake source = new DropFakes.ItemFake(world.proxy, DropFakes.stack(Material.STONE, 1));
        DropFakes.ItemFake target = new DropFakes.ItemFake(world.proxy, DropFakes.stack(Material.STONE, 2));

        service.onItemMerge(new ItemMergeEvent(source.proxy, target.proxy));
        target.stack(DropFakes.stack(Material.STONE, 3));
        harness.runScheduled();

        assertEquals(1, target.calls("setCustomName"));
        assertTrue(target.customName().contains("3x"),
            "the survivor is named with the settled merged amount, got " + target.customName());
        assertTrue(target.customName().contains("3x Stone"), "name=" + target.customName());
        assertEquals(1, service.activeCount());
        assertEquals(0, harness.scheduled().size());
    }

    @Test
    void aMergedAwaySourceStopsBeingTracked() {
        DropFakes.ItemFake source = new DropFakes.ItemFake(world.proxy, DropFakes.stack(Material.STONE, 1));
        DropFakes.ItemFake target = new DropFakes.ItemFake(world.proxy, DropFakes.stack(Material.STONE, 2));
        service.refresh(source.proxy);
        assertEquals(1, service.activeCount());

        service.onItemMerge(new ItemMergeEvent(source.proxy, target.proxy));

        assertEquals(0, service.activeCount());
    }
}
