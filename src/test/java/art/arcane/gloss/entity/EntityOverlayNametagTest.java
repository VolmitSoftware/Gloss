package art.arcane.gloss.entity;

import art.arcane.gloss.util.common.TeamAllocator;
import org.bukkit.entity.EntityType;
import org.bukkit.event.player.PlayerQuitEvent;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.File;
import java.lang.reflect.Method;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class EntityOverlayNametagTest {
    private static final String OVERRIDE = """
        {"schemaVersion":2,"revision":1,"includePlayers":false,"overrideNametag":true}
        """;

    @TempDir
    File dataFolder;

    @Test
    void nativeNamesArePreservedByDefault() {
        try (EntityOverlayHarness harness = new EntityOverlayHarness(dataFolder)) {
            EntityOverlayHarness.WorldState world = harness.world("world");
            harness.join("Viewer", world, 0, 64, 0);
            harness.mob(world, EntityType.ZOMBIE, 4, 64, 0).customName = "Sentinel";
            EntityOverlayService service = harness.service("{\"schemaVersion\":2,\"revision\":1}");
            harness.drive(service);
            assertTrue(harness.teams.calls.isEmpty());
        }
    }

    @Test
    void overrideHidesOnlyForViewersWithAnOverlayAndKeepsTheSavedName() {
        try (EntityOverlayHarness harness = new EntityOverlayHarness(dataFolder)) {
            EntityOverlayHarness.WorldState world = harness.world("world");
            EntityOverlayHarness.PlayerHandle near = harness.join("Near", world, 0, 64, 0);
            EntityOverlayHarness.PlayerHandle far = harness.join("Far", world, 30, 64, 0);
            EntityOverlayHarness.MobHandle mob = harness.mob(world, EntityType.ZOMBIE, 4, 64, 0);
            mob.customName = "Sentinel";
            EntityOverlayService service = harness.service(OVERRIDE);
            harness.drive(service);
            harness.drive(service);
            assertEquals(TeamAllocator.NameTagVisibility.NEVER,
                harness.teams.layersFor(near.uuid, mob.uuid.toString()).get("entity-overlay").nameTagVisibility());
            assertTrue(harness.teams.layersFor(far.uuid, mob.uuid.toString()).isEmpty());
            assertEquals("Sentinel", mob.customName);
            assertEquals(1, harness.teams.calls.size());

            near.location = near.location.clone().add(40, 0, 0);
            for (int pass = 0; pass < 4; pass++) {
                harness.drive(service);
            }
            assertTrue(harness.teams.layersFor(near.uuid, mob.uuid.toString()).isEmpty());
        }
    }

    @Test
    void hiddenSharedPaneRestoresNativeNameAndVisiblePaneSuppressesAgain() {
        try (EntityOverlayHarness harness = new EntityOverlayHarness(dataFolder)) {
            EntityOverlayHarness.WorldState world = harness.world("world");
            EntityOverlayHarness.PlayerHandle viewer = harness.join("Viewer", world, 0, 64, 0);
            EntityOverlayHarness.MobHandle mob = harness.mob(world, EntityType.ZOMBIE, 4, 64, 0);
            mob.customName = "Sentinel";
            EntityOverlayService service = harness.service("""
                {"schemaVersion":2,"revision":1,"overrideNametag":true,"show":"entity.health > 10"}
                """);
            harness.drive(service);
            assertEquals(1, harness.teams.layersFor(viewer.uuid, mob.uuid.toString()).size());
            mob.health = 5;
            harness.drive(service);
            assertTrue(harness.teams.layersFor(viewer.uuid, mob.uuid.toString()).isEmpty());
            mob.health = 15;
            harness.drive(service);
            assertEquals(1, harness.teams.layersFor(viewer.uuid, mob.uuid.toString()).size());
            harness.unloadDocument(service);
            assertTrue(harness.teams.layersFor(viewer.uuid, mob.uuid.toString()).isEmpty());
        }
    }

    @Test
    void personalPaneConditionsAndViewerDepartureReleaseClaims() {
        try (EntityOverlayHarness harness = new EntityOverlayHarness(dataFolder)) {
            EntityOverlayHarness.WorldState world = harness.world("world");
            EntityOverlayHarness.PlayerHandle near = harness.join("Near", world, 0, 64, 0);
            EntityOverlayHarness.PlayerHandle far = harness.join("Far", world, 10, 64, 0);
            EntityOverlayHarness.MobHandle mob = harness.mob(world, EntityType.ZOMBIE, 4, 64, 0);
            mob.customName = "Sentinel";
            EntityOverlayService service = harness.service("""
                {"schemaVersion":2,"revision":1,"includePlayers":false,"overrideNametag":true,
                 "show":"entity.distance < 5"}
                """);
            harness.drive(service);
            assertEquals(1, harness.teams.layersFor(near.uuid, mob.uuid.toString()).size());
            assertTrue(harness.teams.layersFor(far.uuid, mob.uuid.toString()).isEmpty());
            near.location = near.location.clone().add(-2, 0, 0);
            harness.drive(service);
            assertTrue(harness.teams.layersFor(near.uuid, mob.uuid.toString()).isEmpty());
            near.location = near.location.clone().add(2, 0, 0);
            harness.drive(service);
            assertEquals(1, harness.teams.layersFor(near.uuid, mob.uuid.toString()).size());
            harness.quit(near);
            service.onQuit(new PlayerQuitEvent(near.proxy, (String) null));
            assertTrue(harness.teams.layersFor(near.uuid, mob.uuid.toString()).isEmpty());
        }
    }

    @Test
    void playerNametagsRemainOwnedByTheirExistingServices() {
        try (EntityOverlayHarness harness = new EntityOverlayHarness(dataFolder)) {
            EntityOverlayHarness.WorldState world = harness.world("world");
            harness.join("Viewer", world, 0, 64, 0);
            harness.join("Player", world, 4, 64, 0);
            EntityOverlayService service = harness.service("""
                {"schemaVersion":2,"revision":1,"overrideNametag":true}
                """);
            harness.drive(service);
            assertTrue(harness.teams.calls.isEmpty());
        }
    }

    @Test
    void retiringAnOldOverlayDoesNotReleaseItsReplacementClaim() throws ReflectiveOperationException {
        try (EntityOverlayHarness harness = new EntityOverlayHarness(dataFolder)) {
            EntityOverlayHarness.WorldState world = harness.world("world");
            EntityOverlayHarness.PlayerHandle viewer = harness.join("Viewer", world, 0, 64, 0);
            EntityOverlayHarness.MobHandle mob = harness.mob(world, EntityType.ZOMBIE, 4, 64, 0);
            mob.customName = "Sentinel";
            EntityOverlayService service = harness.service(OVERRIDE);
            harness.drive(service);
            EntityOverlayTarget retired = harness.overlays(service).remove(mob.uuid);
            assertEquals(1, harness.teams.calls.size(), "Initial admission: " + harness.teams.calls);
            harness.drive(service);
            assertEquals(1, harness.teams.calls.size(), "Replacement admission: " + harness.teams.calls);
            Method destroy = EntityOverlayService.class.getDeclaredMethod("destroy", EntityOverlayTarget.class);
            destroy.setAccessible(true);
            destroy.invoke(service, retired);
            assertEquals(1, harness.teams.layersFor(viewer.uuid, mob.uuid.toString()).size());
            assertEquals(1, harness.teams.calls.size(), "Retired display: " + harness.teams.calls);
        }
    }
}
