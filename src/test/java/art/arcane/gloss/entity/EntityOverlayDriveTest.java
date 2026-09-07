package art.arcane.gloss.entity;

import org.bukkit.entity.EntityType;
import org.bukkit.event.entity.EntityRemoveEvent;
import org.bukkit.plugin.Plugin;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.File;
import java.lang.reflect.InvocationHandler;
import java.lang.reflect.Proxy;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class EntityOverlayDriveTest {
    private static final String MOBS_ONLY = """
        {"schemaVersion":2,"revision":1,"includePlayers":false,"range":16.0}
        """;

    @TempDir
    File dataFolder;

    @Test
    void everyViewerOfATargetSharesOneHologram() {
        try (EntityOverlayHarness harness = new EntityOverlayHarness(dataFolder)) {
            EntityOverlayHarness.WorldState world = harness.world("world");
            harness.join("A", world, 0, 64, 0);
            harness.join("B", world, 1, 64, 0);
            harness.join("C", world, 2, 64, 0);
            harness.mob(world, EntityType.ZOMBIE, 4, 64, 0);
            harness.mob(world, EntityType.COW, 5, 64, 0);
            EntityOverlayService service = harness.service(MOBS_ONLY);

            harness.drive(service);

            assertEquals(2, harness.overlays(service).size());
            assertEquals(2, harness.holograms.temporaryCount());
            for (EntityOverlayTarget overlay : harness.overlays(service).values()) {
                assertEquals(3, overlay.audience.size());
                assertTrue(overlay.personalViewers.isEmpty());
                assertEquals(overlay.audience.keySet(), harness.sharedWhitelist(overlay),
                    "every shared-audience viewer must be on the hologram whitelist");
            }
        }
    }

    @Test
    void playerTargetsAlsoCollapseToOneHologramEach() {
        try (EntityOverlayHarness harness = new EntityOverlayHarness(dataFolder)) {
            EntityOverlayHarness.WorldState world = harness.world("world");
            harness.join("A", world, 0, 64, 0);
            harness.join("B", world, 1, 64, 0);
            harness.join("C", world, 2, 64, 0);
            EntityOverlayService service = harness.service("""
                {"schemaVersion":2,"revision":1,"includePlayers":true,"range":16.0}
                """);

            harness.drive(service);

            assertEquals(3, harness.overlays(service).size());
            assertEquals(3, harness.holograms.temporaryCount());
            for (EntityOverlayTarget overlay : harness.overlays(service).values()) {
                assertEquals(2, overlay.audience.size());
            }
        }
    }

    @Test
    void removingAnEntityWithoutAnOverlayTouchesNothing() {
        try (EntityOverlayHarness harness = new EntityOverlayHarness(dataFolder)) {
            EntityOverlayHarness.WorldState world = harness.world("world");
            harness.join("A", world, 0, 64, 0);
            EntityOverlayHarness.MobHandle watched = harness.mob(world, EntityType.ZOMBIE, 4, 64, 0);
            EntityOverlayHarness.MobHandle stranger = harness.mob(world, EntityType.COW, 4000, 64, 0);
            EntityOverlayService service = harness.service(MOBS_ONLY);
            harness.drive(service);
            long before = harness.counter(service, "removalMutations");

            service.onRemove(new EntityRemoveEvent(stranger.proxy, EntityRemoveEvent.Cause.PLUGIN));

            assertEquals(before, harness.counter(service, "removalMutations"));
            assertEquals(1, harness.overlays(service).size());

            service.onRemove(new EntityRemoveEvent(watched.proxy, EntityRemoveEvent.Cause.PLUGIN));

            assertEquals(before + 1, harness.counter(service, "removalMutations"));
            assertEquals(0, harness.overlays(service).size());
        }
    }

    @Test
    void perViewerBudgetKeepsTheNearestTargets() {
        try (EntityOverlayHarness harness = new EntityOverlayHarness(dataFolder)) {
            EntityOverlayHarness.WorldState world = harness.world("world");
            harness.join("A", world, 0, 64, 0);
            EntityOverlayHarness.MobHandle near = harness.mob(world, EntityType.ZOMBIE, 2, 64, 0);
            EntityOverlayHarness.MobHandle middle = harness.mob(world, EntityType.COW, 5, 64, 0);
            harness.mob(world, EntityType.PIG, 9, 64, 0);
            harness.mob(world, EntityType.SHEEP, 12, 64, 0);
            EntityOverlayService service = harness.service("""
                {"schemaVersion":2,"revision":1,"includePlayers":false,"range":16.0,"maxEntitiesPerViewer":2}
                """);

            harness.drive(service);

            Map<UUID, EntityOverlayTarget> overlays = harness.overlays(service);
            assertEquals(2, overlays.size());
            assertTrue(overlays.containsKey(near.uuid));
            assertTrue(overlays.containsKey(middle.uuid));
        }
    }

    @Test
    void globalBudgetKeepsTheNearestTargets() {
        try (EntityOverlayHarness harness = new EntityOverlayHarness(dataFolder)) {
            EntityOverlayHarness.WorldState world = harness.world("world");
            harness.join("A", world, 0, 64, 0);
            EntityOverlayHarness.MobHandle near = harness.mob(world, EntityType.ZOMBIE, 2, 64, 0);
            for (int index = 0; index < 40; index++) {
                harness.mob(world, EntityType.COW, 5 + index * 0.25D, 64, 0);
            }
            EntityOverlayService service = harness.service("""
                {"schemaVersion":2,"revision":1,"includePlayers":false,"range":16.0,
                 "maxEntitiesPerViewer":256,"maxActiveOverlays":16}
                """);

            harness.drive(service);

            Map<UUID, EntityOverlayTarget> overlays = harness.overlays(service);
            assertEquals(16, overlays.size());
            assertEquals(16, harness.holograms.temporaryCount());
            assertTrue(overlays.containsKey(near.uuid));
        }
    }

    @Test
    void onlyTheInsightViewerGetsItsOwnHologram() {
        try (EntityOverlayHarness harness = new EntityOverlayHarness(dataFolder)) {
            EntityOverlayHarness.WorldState world = harness.world("world");
            EntityOverlayHarness.PlayerHandle a = harness.join("A", world, 0, 64, 0);
            harness.join("B", world, 1, 64, 0);
            harness.join("C", world, 2, 64, 0);
            EntityOverlayHarness.MobHandle mob = harness.mob(world, EntityType.ZOMBIE, 4, 64, 0);
            EntityOverlayService service = harness.service(MOBS_ONLY);
            service.updateInsight(owner(), a.proxy, mob.proxy, List.of("&7Speed &f0.3"), 5000L);

            harness.drive(service);

            EntityOverlayTarget overlay = harness.overlays(service).get(mob.uuid);
            assertEquals(3, overlay.audience.size());
            assertEquals(java.util.Set.of(a.uuid), overlay.personalViewers);
            assertEquals(2, harness.holograms.temporaryCount());
        }
    }

    @Test
    void unchangedSamplesDoNotRerender() {
        try (EntityOverlayHarness harness = new EntityOverlayHarness(dataFolder)) {
            EntityOverlayHarness.WorldState world = harness.world("world");
            harness.join("A", world, 0, 64, 0);
            harness.join("B", world, 1, 64, 0);
            EntityOverlayHarness.MobHandle mob = harness.mob(world, EntityType.ZOMBIE, 4, 64, 0);
            EntityOverlayService service = harness.service(MOBS_ONLY);

            harness.drive(service);
            long dispatches = harness.counter(service, "renderPasses");
            long preparations = harness.counter(service, "textPreparations");
            harness.drive(service);
            harness.drive(service);

            assertEquals(dispatches, harness.counter(service, "renderPasses"));
            assertEquals(preparations, harness.counter(service, "textPreparations"));

            mob.health = 12.0D;
            harness.drive(service);

            assertEquals(dispatches + 1, harness.counter(service, "renderPasses"));
            assertEquals(preparations + 1, harness.counter(service, "textPreparations"));
        }
    }

    @Test
    void hiddenAndExcludedTargetsNeverGetAnOverlay() {
        try (EntityOverlayHarness harness = new EntityOverlayHarness(dataFolder)) {
            EntityOverlayHarness.WorldState world = harness.world("world");
            EntityOverlayHarness.PlayerHandle viewer = harness.join("A", world, 0, 64, 0);
            EntityOverlayHarness.MobHandle vanished = harness.mob(world, EntityType.ZOMBIE, 3, 64, 0);
            harness.mob(world, EntityType.ARMOR_STAND, 4, 64, 0);
            viewer.visibility = entity -> !entity.getUniqueId().equals(vanished.uuid);
            EntityOverlayService service = harness.service(MOBS_ONLY);

            harness.drive(service);

            assertEquals(0, harness.overlays(service).size());
            assertEquals(0, harness.holograms.temporaryCount());
        }
    }

    @Test
    void viewerSpecificDocumentsFallBackToOneHologramPerViewer() {
        try (EntityOverlayHarness harness = new EntityOverlayHarness(dataFolder)) {
            EntityOverlayHarness.WorldState world = harness.world("world");
            harness.join("A", world, 0, 64, 0);
            harness.join("B", world, 1, 64, 0);
            EntityOverlayHarness.MobHandle mob = harness.mob(world, EntityType.ZOMBIE, 4, 64, 0);
            EntityOverlayService service = harness.service("""
                {"schemaVersion":2,"revision":1,"includePlayers":false,"range":16.0,
                 "lines":[{"id":"who","text":"&f{name} %player_name%"}]}
                """);

            harness.drive(service);

            EntityOverlayTarget overlay = harness.overlays(service).get(mob.uuid);
            assertEquals(2, overlay.personalViewers.size());
            assertEquals(2, harness.holograms.temporaryCount());
        }
    }

    @Test
    void theOverlayDiesWhenItsLastViewerLeaves() {
        try (EntityOverlayHarness harness = new EntityOverlayHarness(dataFolder)) {
            EntityOverlayHarness.WorldState world = harness.world("world");
            EntityOverlayHarness.PlayerHandle a = harness.join("A", world, 0, 64, 0);
            EntityOverlayHarness.PlayerHandle b = harness.join("B", world, 1, 64, 0);
            EntityOverlayHarness.MobHandle mob = harness.mob(world, EntityType.ZOMBIE, 4, 64, 0);
            EntityOverlayService service = harness.service(MOBS_ONLY);
            harness.drive(service);
            assertEquals(2, harness.overlays(service).get(mob.uuid).audience.size());

            harness.quit(b);
            service.onQuit(new org.bukkit.event.player.PlayerQuitEvent(b.proxy, (String) null));
            harness.drive(service);

            assertEquals(1, harness.overlays(service).get(mob.uuid).audience.size());

            a.location = a.location.clone().add(200, 0, 0);
            harness.drive(service);
            harness.drive(service);
            harness.drive(service);

            assertTrue(harness.overlays(service).isEmpty());
            assertEquals(0, harness.holograms.temporaryCount());
        }
    }

    @Test
    void viewerGatedAnimationClipsForcePerViewerHolograms() {
        try (EntityOverlayHarness harness = new EntityOverlayHarness(dataFolder)) {
            EntityOverlayHarness.WorldState world = harness.world("world");
            harness.join("A", world, 0, 64, 0);
            harness.join("B", world, 1, 64, 0);
            EntityOverlayHarness.MobHandle mob = harness.mob(world, EntityType.ZOMBIE, 4, 64, 0);
            harness.publishAnimationClip("vip", List.of("A", "B"), "viewer.level > 1");
            EntityOverlayService service = harness.service("""
                {"schemaVersion":2,"revision":1,"includePlayers":false,"range":16.0,
                 "lines":[{"id":"banner","text":"|animation.vip|"}]}
                """);

            harness.drive(service);

            EntityOverlayTarget overlay = harness.overlays(service).get(mob.uuid);
            assertEquals(2, overlay.personalViewers.size());
            assertEquals(2, harness.holograms.temporaryCount());
        }
    }

    @Test
    void sharedAnimationClipsKeepOneHologram() {
        try (EntityOverlayHarness harness = new EntityOverlayHarness(dataFolder)) {
            EntityOverlayHarness.WorldState world = harness.world("world");
            harness.join("A", world, 0, 64, 0);
            harness.join("B", world, 1, 64, 0);
            EntityOverlayHarness.MobHandle mob = harness.mob(world, EntityType.ZOMBIE, 4, 64, 0);
            harness.publishAnimationClip("spin", List.of("A", "B"), "true");
            EntityOverlayService service = harness.service("""
                {"schemaVersion":2,"revision":1,"includePlayers":false,"range":16.0,
                 "lines":[{"id":"banner","text":"|animation.spin|"}]}
                """);

            harness.drive(service);

            EntityOverlayTarget overlay = harness.overlays(service).get(mob.uuid);
            assertTrue(overlay.personalViewers.isEmpty());
            assertEquals(1, harness.holograms.temporaryCount());
        }
    }

    @Test
    void distanceOverlaysFollowTheViewerWithoutAnyEntityChange() {
        try (EntityOverlayHarness harness = new EntityOverlayHarness(dataFolder)) {
            EntityOverlayHarness.WorldState world = harness.world("world");
            EntityOverlayHarness.PlayerHandle a = harness.join("A", world, 0, 64, 0);
            EntityOverlayHarness.MobHandle mob = harness.mob(world, EntityType.ZOMBIE, 10, 64, 0);
            EntityOverlayService service = harness.service("""
                {"schemaVersion":2,"revision":1,"includePlayers":false,"range":16.0,
                 "lines":[{"id":"far","text":"&7{distance}m"}]}
                """);

            harness.drive(service);
            long preparations = harness.counter(service, "textPreparations");
            assertEquals(1, harness.overlays(service).get(mob.uuid).personalViewers.size());

            a.location = a.location.clone().add(5, 0, 0);
            harness.drive(service);

            assertTrue(harness.counter(service, "textPreparations") > preparations,
                "distance overlays must re-prepare when the viewer moves");
        }
    }

    @Test
    void theGlobalCapKeepsExistingOverlaysAliveInsteadOfFlickering() {
        try (EntityOverlayHarness harness = new EntityOverlayHarness(dataFolder)) {
            EntityOverlayHarness.WorldState world = harness.world("world");
            harness.join("A", world, 0, 64, 0);
            EntityOverlayHarness.MobHandle near = harness.mob(world, EntityType.ZOMBIE, 1, 64, 0);
            EntityOverlayHarness.MobHandle second = harness.mob(world, EntityType.COW, 2, 64, 0);
            EntityOverlayService service = harness.service("""
                {"schemaVersion":2,"revision":1,"includePlayers":false,"range":16.0,
                 "maxEntitiesPerViewer":256,"maxActiveOverlays":16}
                """);
            harness.drive(service);
            assertEquals(2, harness.overlays(service).size());

            for (int index = 0; index < 40; index++) {
                harness.mob(world, EntityType.PIG, 5 + index * 0.25D, 64, 0);
            }
            for (int drive = 0; drive < 5; drive++) {
                harness.drive(service);
                assertTrue(harness.overlays(service).containsKey(near.uuid),
                    "the nearest target must never be evicted while the cap is full");
                assertTrue(harness.overlays(service).containsKey(second.uuid),
                    "an admitted target must keep its audience stamp when the cap is full");
            }
            assertEquals(16, harness.overlays(service).size());
        }
    }

    @Test
    void animationRegistryDriftRePreparesEveryOverlay() {
        try (EntityOverlayHarness harness = new EntityOverlayHarness(dataFolder)) {
            EntityOverlayHarness.WorldState world = harness.world("world");
            harness.join("A", world, 0, 64, 0);
            harness.mob(world, EntityType.ZOMBIE, 4, 64, 0);
            EntityOverlayService service = harness.service(MOBS_ONLY);
            harness.drive(service);
            long preparations = harness.counter(service, "textPreparations");

            harness.publishAnimationClip("spin", List.of("A", "B"), "true");
            harness.drive(service);

            assertTrue(harness.counter(service, "textPreparations") > preparations,
                "a registry rebuild must re-prepare overlay text");
        }
    }

    @Test
    void unloadingTheDocumentReleasesInsightEntityReferences() {
        try (EntityOverlayHarness harness = new EntityOverlayHarness(dataFolder)) {
            EntityOverlayHarness.WorldState world = harness.world("world");
            EntityOverlayHarness.PlayerHandle a = harness.join("A", world, 0, 64, 0);
            EntityOverlayHarness.MobHandle mob = harness.mob(world, EntityType.ZOMBIE, 4, 64, 0);
            EntityOverlayService service = harness.service(MOBS_ONLY);
            service.updateInsight(owner(), a.proxy, mob.proxy, List.of("&7Speed &f0.3"), 10000L);
            harness.drive(service);
            assertEquals(1, harness.map(service, "insights").size());

            harness.unloadDocument(service);

            assertTrue(harness.map(service, "insights").isEmpty());
            assertTrue(harness.map(service, "insightTargets").isEmpty());
            assertEquals(0, harness.holograms.temporaryCount());
        }
    }

    private static Plugin owner() {
        InvocationHandler handler = (proxy, method, args) -> switch (method.getName()) {
            case "getName" -> "Owner";
            case "isEnabled" -> true;
            case "hashCode" -> System.identityHashCode(proxy);
            case "equals" -> proxy == args[0];
            default -> throw new AssertionError(method.getName());
        };
        return (Plugin) Proxy.newProxyInstance(EntityOverlayDriveTest.class.getClassLoader(),
            new Class<?>[]{Plugin.class}, handler);
    }
}
