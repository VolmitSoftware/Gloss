package art.arcane.gloss.beam;

import art.arcane.gloss.Gloss;
import art.arcane.gloss.api.BeamHandle;
import art.arcane.gloss.api.BeamSpec;
import art.arcane.gloss.menu.CharacterizationSupport;
import art.arcane.gloss.packets.PacketEventsStub;
import com.github.retrooper.packetevents.wrapper.play.server.WrapperPlayServerDestroyEntities;
import com.github.retrooper.packetevents.wrapper.play.server.WrapperPlayServerEntityMetadata;
import com.github.retrooper.packetevents.wrapper.play.server.WrapperPlayServerEntityTeleport;
import com.github.retrooper.packetevents.wrapper.play.server.WrapperPlayServerSpawnEntity;
import org.bukkit.Location;
import org.bukkit.Server;
import org.bukkit.World;
import org.bukkit.entity.Player;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import org.bukkit.util.Vector;

import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

class BeamServiceTest {
    private static final UUID VIEWER = UUID.randomUUID();
    private static final BeamSpec SPEC = new BeamSpec(1, 0.25D);

    private final AtomicBoolean online =
        new AtomicBoolean(true);
    private PacketEventsStub packets;
    private Object previousServer;
    private Gloss previousPlugin;
    private Gloss plugin;
    private Player viewer;
    private World world;
    private BeamService beams;

    @BeforeEach
    void setUp() throws ReflectiveOperationException {
        packets = PacketEventsStub.install();
        UUID worldId = UUID.randomUUID();
        world = (World) CharacterizationSupport.proxy(new Class<?>[]{World.class},
            (proxy, method, args) -> switch (method.getName()) {
                case "getName" -> "world";
                case "getUID" -> worldId;
                default -> CharacterizationSupport.identity(proxy, method, args);
            });
        viewer = (Player) CharacterizationSupport.proxy(new Class<?>[]{Player.class},
            (proxy, method, args) -> switch (method.getName()) {
                case "getUniqueId" -> VIEWER;
                case "getName" -> "beam-viewer";
                case "isOnline" -> online.get();
                case "getWorld" -> world;
                default -> CharacterizationSupport.identity(proxy, method, args);
            });
        Server server = (Server) CharacterizationSupport.proxy(new Class<?>[]{Server.class},
            (proxy, method, args) -> switch (method.getName()) {
                case "getPlayer" -> VIEWER.equals(args[0]) ? viewer : null;
                case "getLogger" -> CharacterizationSupport.mutedLogger();
                case "getWorld" -> worldId.equals(args[0]) ? world : null;
                case "isPrimaryThread" -> true;
                default -> CharacterizationSupport.identity(proxy, method, args);
            });
        previousServer = CharacterizationSupport.installServer(server);
        plugin = CharacterizationSupport.bareGloss(server);
        previousPlugin = CharacterizationSupport.installGloss(plugin);
        beams = new BeamService(plugin);
    }

    @AfterEach
    void tearDown() throws ReflectiveOperationException {
        CharacterizationSupport.restoreGloss(previousPlugin);
        CharacterizationSupport.restoreServer(previousServer);
        PacketEventsStub.uninstall();
    }

    @Test
    void linkingSpawnsOneDisplayForEachViewer() {
        beams.link(() -> at(0, 64, 0), () -> at(0, 64, 10), SPEC, 0L, Set.of(VIEWER));

        Assertions.assertEquals(1, packets.sentTo(viewer, WrapperPlayServerSpawnEntity.class).size());
        Assertions.assertEquals(1, packets.sentTo(viewer, WrapperPlayServerEntityMetadata.class).size());
    }

    @Test
    void aStationaryBeamSendsNothingOnTheNextTick() {
        beams.link(() -> at(0, 64, 0), () -> at(0, 64, 10), SPEC, 0L, Set.of(VIEWER));
        packets.clear();

        beams.tick();

        Assertions.assertEquals(List.of(), packets.sent());
    }

    @Test
    void aMovedAnchorReteleportsAndRetransformsTheBeam() {
        AtomicReference<Location> target = new AtomicReference<>(at(0, 64, 10));
        beams.link(() -> at(0, 64, 0), target::get, SPEC, 0L, Set.of(VIEWER));
        packets.clear();
        target.set(at(0, 64, 24));

        beams.tick();

        Assertions.assertEquals(1, packets.sentTo(viewer, WrapperPlayServerEntityTeleport.class).size());
        Assertions.assertEquals(1, packets.sentTo(viewer, WrapperPlayServerEntityMetadata.class).size());
    }

    @Test
    void cancellingAHandleDestroysTheDisplay() {
        BeamHandle handle = beams.link(() -> at(0, 64, 0), () -> at(0, 64, 10), SPEC, 0L,
            Set.of(VIEWER));
        packets.clear();

        handle.cancel();

        Assertions.assertEquals(1, packets.sentTo(viewer, WrapperPlayServerDestroyEntities.class).size());
        Assertions.assertFalse(handle.active());
    }

    @Test
    void aLifetimeEndsTheBeamOnItsOwn() {
        BeamHandle handle = beams.link(() -> at(0, 64, 0), () -> at(0, 64, 10), SPEC,
            2L * BeamService.DRIVE_INTERVAL_TICKS, Set.of(VIEWER));
        packets.clear();

        beams.tick();
        Assertions.assertTrue(handle.active());
        beams.tick();

        Assertions.assertFalse(handle.active());
        Assertions.assertEquals(1, packets.sentTo(viewer, WrapperPlayServerDestroyEntities.class).size());
    }

    @Test
    void anAnchorThatLeavesTheViewersWorldEndsTheBeam() {
        World elsewhere = (World) CharacterizationSupport.proxy(new Class<?>[]{World.class},
            (proxy, method, args) -> switch (method.getName()) {
                case "getName" -> "nether";
                case "getUID" -> UUID.randomUUID();
                default -> CharacterizationSupport.identity(proxy, method, args);
            });
        AtomicReference<Location> target = new AtomicReference<>(at(0, 64, 10));
        BeamHandle handle = beams.link(() -> at(0, 64, 0), target::get, SPEC, 0L, Set.of(VIEWER));
        packets.clear();
        target.set(new Location(elsewhere, 0, 64, 10));

        beams.tick();

        Assertions.assertFalse(handle.active());
    }

    @Test
    void disablingTheServiceDestroysEveryBeam() {
        beams.link(() -> at(0, 64, 0), () -> at(0, 64, 10), SPEC, 0L, Set.of(VIEWER));
        packets.clear();

        beams.disable();

        Assertions.assertEquals(1, packets.sentTo(viewer, WrapperPlayServerDestroyEntities.class).size());
    }

    @Test
    void theServiceNamesItselfForTheLaneRegistry() {
        Assertions.assertEquals("beams", beams.name());
    }

    private Location at(double x, double y, double z) {
        return new Location(world, x, y, z);
    }

    @Test
    void eachTrailKeepsItsOwnResampleOrigin() {
        Vector eye = new Vector(0, 64, 0);

        Assertions.assertTrue(beams.shouldWalk(VIEWER, "near", eye));
        Assertions.assertFalse(beams.shouldWalk(VIEWER, "near", eye));
        Assertions.assertTrue(beams.shouldWalk(VIEWER, "far", eye),
            "a second marker's trail must not be skipped because the first one just walked");
        Assertions.assertFalse(beams.shouldWalk(VIEWER, "far", eye));
    }

    @Test
    void aTrailWalksAgainOnceTheViewerHasMovedFarEnough() {
        Vector eye = new Vector(0, 64, 0);
        beams.shouldWalk(VIEWER, "near", eye);

        Assertions.assertFalse(beams.shouldWalk(VIEWER, "near", new Vector(0.5D, 64, 0)));
        Assertions.assertTrue(beams.shouldWalk(VIEWER, "near", new Vector(8, 64, 0)));
    }

    @Test
    void quittingDropsEveryTrailOriginTheViewerHeld() {
        Vector eye = new Vector(0, 64, 0);
        beams.shouldWalk(VIEWER, "near", eye);
        beams.shouldWalk(VIEWER, "far", eye);

        beams.onQuit(new org.bukkit.event.player.PlayerQuitEvent(viewer, (String) null));

        Assertions.assertTrue(beams.shouldWalk(VIEWER, "near", eye));
        Assertions.assertTrue(beams.shouldWalk(VIEWER, "far", eye));
    }

    @Test
    void aViewerWhoRelogsGetsTheBeamSpawnedAgain() {
        AtomicReference<Location> from = new AtomicReference<>(new Location(world, 0, 64, 0));
        beams.link(from::get, () -> new Location(world, 0, 64, 10), SPEC, 0L, Set.of(VIEWER));
        Assertions.assertEquals(1, packets.sentTo(viewer, WrapperPlayServerSpawnEntity.class).size());
        online.set(false);
        beams.tick();
        packets.clear();

        online.set(true);
        from.set(new Location(world, 1, 64, 0));
        beams.tick();

        Assertions.assertEquals(1, packets.sentTo(viewer, WrapperPlayServerSpawnEntity.class).size(),
            "a relogged client never saw the old display, so it must be spawned again");
    }
}
