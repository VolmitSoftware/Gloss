package art.arcane.gloss.camera;

import art.arcane.gloss.Gloss;
import art.arcane.gloss.GlossConfig;
import art.arcane.gloss.config.GlossConfigFile;
import art.arcane.gloss.menu.CharacterizationSupport;
import art.arcane.gloss.state.PlayerSections;
import org.bukkit.GameMode;
import org.bukkit.Location;
import org.bukkit.Server;
import org.bukkit.World;
import org.bukkit.entity.ArmorStand;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Player;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.event.player.PlayerToggleSneakEvent;
import org.bukkit.plugin.PluginManager;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.ArrayDeque;
import java.util.Queue;
import java.util.concurrent.CompletableFuture;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;

class CameraServiceTest {
    private static final UUID RIDER = UUID.randomUUID();
    private static final List<Spline.Node> PATH = List.of(
        new Spline.Node(0, 64, 0, 0, 0, 40), new Spline.Node(0, 64, 20, 90, 0, 0));

    @TempDir
    Path dataFolder;

    private final List<String> calls = new ArrayList<>();
    private final AtomicBoolean carrierRemoved = new AtomicBoolean();
    private final AtomicBoolean quitting = new AtomicBoolean();
    private Object previousServer;
    private Gloss previousPlugin;
    private Gloss plugin;
    private Player rider;
    private World world;
    private CameraService camera;
    private PlayerSections sections;
    private final ControlledTransport transport = new ControlledTransport();

    @BeforeEach
    void setUp() throws ReflectiveOperationException {
        UUID worldId = UUID.randomUUID();
        world = (World) CharacterizationSupport.proxy(new Class<?>[]{World.class},
            (proxy, method, args) -> switch (method.getName()) {
                case "getName" -> "world";
                case "getUID" -> worldId;
                case "spawn" -> {
                    Assertions.assertSame(rider, transport.owner);
                    yield carrier();
                }
                default -> CharacterizationSupport.identity(proxy, method, args);
            });
        rider = (Player) CharacterizationSupport.proxy(new Class<?>[]{Player.class},
            (proxy, method, args) -> switch (method.getName()) {
                case "getUniqueId" -> RIDER;
                case "getName" -> "rider";
                case "isOnline" -> !quitting.get();
                case "isValid" -> true;
                case "getWorld" -> world;
                case "getLocation" -> new Location(world, 5, 64, 5, 45.0F, 0.0F);
                case "getGameMode" -> GameMode.SURVIVAL;
                case "getAllowFlight" -> false;
                case "isFlying" -> false;
                case "getVelocity" -> new org.bukkit.util.Vector();
                case "hasPermission" -> true;
                case "setGameMode" -> {
                    Assertions.assertSame(rider, transport.owner);
                    calls.add("setGameMode:" + args[0]);
                    yield null;
                }
                case "setSpectatorTarget" -> {
                    Assertions.assertSame(rider, transport.owner);
                    calls.add("setSpectatorTarget:" + (args[0] == null ? "null" : "carrier"));
                    yield null;
                }
                case "setAllowFlight" -> {
                    Assertions.assertSame(rider, transport.owner);
                    calls.add("setAllowFlight:" + args[0]);
                    yield null;
                }
                case "setFlying" -> {
                    Assertions.assertSame(rider, transport.owner);
                    calls.add("setFlying:" + args[0]);
                    yield null;
                }
                case "setVelocity" -> {
                    Assertions.assertSame(rider, transport.owner);
                    calls.add("setVelocity");
                    yield null;
                }
                case "teleport" -> {
                    calls.add("teleport");
                    yield true;
                }
                case "teleportAsync" -> {
                    if (quitting.get()) {
                        calls.add("teleportDropped");
                        yield new java.util.concurrent.CompletableFuture<Boolean>();
                    }
                    calls.add("teleport");
                    yield java.util.concurrent.CompletableFuture.completedFuture(Boolean.TRUE);
                }
                default -> CharacterizationSupport.identity(proxy, method, args);
            });
        PluginManager pluginManager = (PluginManager) CharacterizationSupport.proxy(
            new Class<?>[]{PluginManager.class}, (proxy, method, args) -> switch (method.getName()) {
                case "callEvent", "registerEvents" -> null;
                default -> CharacterizationSupport.identity(proxy, method, args);
            });
        Server server = (Server) CharacterizationSupport.proxy(new Class<?>[]{Server.class},
            (proxy, method, args) -> switch (method.getName()) {
                case "getPlayer" -> RIDER.equals(args[0]) ? rider : null;
                case "getLogger" -> CharacterizationSupport.mutedLogger();
                case "getPluginManager" -> pluginManager;
                case "getWorld" -> "world".equals(args[0]) || worldId.equals(args[0]) ? world : null;
                case "getOnlinePlayers" -> List.of(rider);
                case "isPrimaryThread" -> true;
                default -> CharacterizationSupport.identity(proxy, method, args);
            });
        previousServer = CharacterizationSupport.installServer(server);
        plugin = CharacterizationSupport.bareGloss(server);
        GlossConfigFile file = new GlossConfigFile();
        file.normalize();
        CharacterizationSupport.setField(plugin, "config", GlossConfig.from(file));
        CharacterizationSupport.setField(plugin, "laneServices", List.of());
        previousPlugin = CharacterizationSupport.installGloss(plugin);
        sections = new PlayerSections(dataFolder);
        camera = new CameraService(new CameraService.Dependencies(plugin, sections, transport));
    }

    @AfterEach
    void tearDown() throws ReflectiveOperationException {
        CharacterizationSupport.restoreGloss(previousPlugin);
        CharacterizationSupport.restoreServer(previousServer);
    }

    @Test
    void startingARideTakesTheRiderIntoSpectatorOnTheCarrier() {
        Assertions.assertTrue(camera.ride(rider, PATH, options(true)));

        Assertions.assertTrue(calls.contains("setGameMode:SPECTATOR"), calls.toString());
        Assertions.assertTrue(calls.contains("setSpectatorTarget:carrier"), calls.toString());
        Assertions.assertTrue(camera.riding(RIDER));
    }

    @Test
    void aRideJournalsWhereTheRiderReallyWas() {
        camera.ride(rider, PATH, options(true));

        CameraJournal.Entry entry = new CameraJournal(sections).read(RIDER).orElseThrow();
        Assertions.assertEquals("world", entry.world());
        Assertions.assertEquals(5.0D, entry.x());
        Assertions.assertEquals("SURVIVAL", entry.gameMode());
    }

    @Test
    void aRiderCannotStartASecondRide() {
        camera.ride(rider, PATH, options(true));

        Assertions.assertFalse(camera.ride(rider, PATH, options(true)));
    }

    @Test
    void theRideEndsOnItsOwnAtTheEndOfThePath() {
        camera.ride(rider, PATH, options(true));
        calls.clear();

        for (int tick = 0; tick <= 41; tick++) {
            camera.tick();
        }

        Assertions.assertFalse(camera.riding(RIDER));
        assertRestored();
    }

    @Test
    void sneakingSkipsASkippableRide() {
        camera.ride(rider, PATH, options(true));
        calls.clear();

        camera.onSneak(new PlayerToggleSneakEvent(rider, true));

        Assertions.assertFalse(camera.riding(RIDER));
        assertRestored();
    }

    @Test
    void sneakingDoesNotSkipARideThatForbidsIt() {
        camera.ride(rider, PATH, options(false));

        camera.onSneak(new PlayerToggleSneakEvent(rider, true));

        Assertions.assertTrue(camera.riding(RIDER));
    }

    @Test
    void aRiderWhoQuitsMidRideKeepsTheJournalBecauseTheTeleportIsDropped() {
        camera.ride(rider, PATH, options(true));
        camera.tick();
        quitting.set(true);
        calls.clear();

        camera.onQuit(new PlayerQuitEvent(rider, (String) null));

        Assertions.assertFalse(camera.riding(RIDER));
        Assertions.assertFalse(calls.contains("teleportDropped"), calls.toString());
        CameraJournal.Entry entry = new CameraJournal(sections).read(RIDER).orElseThrow();
        Assertions.assertEquals("world", entry.world());
        Assertions.assertEquals(5.0D, entry.x());
        Assertions.assertEquals("SURVIVAL", entry.gameMode());
    }

    @Test
    void theJournalAQuitLeftBehindPutsTheRiderBackOnTheNextJoin() {
        camera.ride(rider, PATH, options(true));
        camera.tick();
        quitting.set(true);
        camera.onQuit(new PlayerQuitEvent(rider, (String) null));
        quitting.set(false);
        calls.clear();

        camera.restoreJournalled(rider);

        Assertions.assertTrue(calls.contains("setGameMode:SURVIVAL"), calls.toString());
        Assertions.assertTrue(calls.contains("teleport"), calls.toString());
        Assertions.assertTrue(new CameraJournal(sections).read(RIDER).isEmpty());
    }

    @Test
    void aRiderStillOnlineWhenTheRideEndsIsPutBackAndTheJournalIsCleared() {
        camera.ride(rider, PATH, options(true));
        calls.clear();

        camera.stop(rider, CameraService.EndReason.END);

        Assertions.assertFalse(camera.riding(RIDER));
        assertRestored();
    }

    @Test
    void disablingEndsEveryRide() {
        camera.ride(rider, PATH, options(true));
        calls.clear();

        camera.disable();

        Assertions.assertFalse(camera.riding(RIDER));
        assertRestored();
    }

    @Test
    void aRideLongerThanTheConfiguredCeilingIsCutShort() {
        List<Spline.Node> endless = List.of(
            new Spline.Node(0, 64, 0, 0, 0, 20 * 60 * 60), new Spline.Node(0, 64, 1, 0, 0, 0));
        camera.ride(rider, endless, options(true));
        calls.clear();

        for (int tick = 0; tick <= plugin.cfg().modules().camera().maxRideSeconds() * 20 + 1; tick++) {
            camera.tick();
        }

        Assertions.assertFalse(camera.riding(RIDER));
        assertRestored();
    }

    @Test
    void endingARideRemovesTheCarrier() {
        camera.ride(rider, PATH, options(true));

        camera.stop(rider, CameraService.EndReason.END);

        Assertions.assertTrue(carrierRemoved.get());
    }

    @Test
    void aJournalLeftByACrashPutsTheRiderBackOnJoin() {
        new CameraJournal(sections).write(RIDER, "world", 1, 64, 2, 30.0F, 0.0F, "CREATIVE");

        camera.restoreJournalled(rider);

        Assertions.assertTrue(calls.contains("setGameMode:CREATIVE"), calls.toString());
        Assertions.assertTrue(calls.contains("teleport"), calls.toString());
        Assertions.assertEquals(Optional.empty(), new CameraJournal(sections).read(RIDER));
    }


    @Test
    void restorationKeepsTheJournalUntilTheTeleportAndOwnerCallbackFinish() {
        camera.ride(rider, PATH, options(true));
        CompletableFuture<Boolean> restored = new CompletableFuture<>();
        transport.playerTeleport = restored;
        calls.clear();

        camera.stop(rider, CameraService.EndReason.END);

        Assertions.assertTrue(new CameraJournal(sections).read(RIDER).isPresent());
        Assertions.assertFalse(calls.contains("setVelocity"));
        Assertions.assertFalse(camera.ride(rider, PATH, options(true)));
        transport.queued = true;
        restored.complete(true);
        Assertions.assertTrue(new CameraJournal(sections).read(RIDER).isPresent());
        transport.drain();
        Assertions.assertTrue(new CameraJournal(sections).read(RIDER).isEmpty());
        Assertions.assertTrue(calls.contains("setVelocity"));
    }

    @Test
    void rejectedRestorationSchedulingRetainsRecoveryWithoutUnsafeFallback() {
        camera.ride(rider, PATH, options(true));
        transport.rejectPlayer = true;
        calls.clear();

        camera.stop(rider, CameraService.EndReason.END);

        Assertions.assertTrue(new CameraJournal(sections).read(RIDER).isPresent());
        Assertions.assertTrue(calls.isEmpty(), calls.toString());
        Assertions.assertTrue(carrierRemoved.get());
    }

    @Test
    void rejectedCarrierCleanupIsRetriedOnItsOwner() {
        camera.ride(rider, PATH, options(true));
        transport.rejectCarrier = true;

        camera.stop(rider, CameraService.EndReason.END);

        Assertions.assertFalse(carrierRemoved.get());
        transport.rejectCarrier = false;
        camera.tick();
        Assertions.assertTrue(carrierRemoved.get());
    }

    @Test
    void unfinishedRestorationDoesNotBlockRecoveryAfterRejoining() {
        camera.ride(rider, PATH, options(true));
        CompletableFuture<Boolean> previous = new CompletableFuture<>();
        transport.playerTeleport = previous;
        camera.stop(rider, CameraService.EndReason.END);
        quitting.set(true);
        camera.onQuit(new PlayerQuitEvent(rider, (String) null));
        quitting.set(false);
        transport.playerTeleport = null;

        camera.restoreJournalled(rider);

        Assertions.assertTrue(new CameraJournal(sections).read(RIDER).isEmpty());
        calls.clear();
        previous.complete(true);
        Assertions.assertTrue(calls.isEmpty(), calls.toString());
    }

    @Test
    void failedRestorationTeleportKeepsRecoveryForRetry() {
        camera.ride(rider, PATH, options(true));
        transport.playerTeleport = CompletableFuture.completedFuture(false);

        camera.stop(rider, CameraService.EndReason.END);

        Assertions.assertTrue(new CameraJournal(sections).read(RIDER).isPresent());
        transport.playerTeleport = null;
        camera.restoreJournalled(rider);
        Assertions.assertTrue(new CameraJournal(sections).read(RIDER).isEmpty());
    }

    @Test
    void stoppingDuringCarrierTransferWaitsBeforeRemovalAndRestoration() {
        camera.ride(rider, PATH, options(true));
        CompletableFuture<Boolean> moved = new CompletableFuture<>();
        transport.carrierTeleport = moved;
        camera.tick();
        int moveCount = transport.carrierMoves;
        camera.tick();
        Assertions.assertEquals(moveCount, transport.carrierMoves);
        calls.clear();

        camera.stop(rider, CameraService.EndReason.END);

        Assertions.assertFalse(carrierRemoved.get());
        Assertions.assertFalse(calls.contains("teleport"));
        Assertions.assertTrue(new CameraJournal(sections).read(RIDER).isPresent());
        moved.complete(true);
        Assertions.assertTrue(carrierRemoved.get());
        assertRestored();
    }

    @Test
    void stoppingDuringInitialPlayerTransferCannotRestoreBeforeThatTransferCompletes() {
        CompletableFuture<Boolean> moved = new CompletableFuture<>();
        transport.playerTeleport = moved;
        camera.ride(rider, PATH, options(true));
        calls.clear();

        camera.stop(rider, CameraService.EndReason.END);

        Assertions.assertFalse(calls.contains("setGameMode:SURVIVAL"));
        transport.playerTeleport = null;
        moved.complete(true);
        Assertions.assertFalse(calls.contains("setSpectatorTarget:carrier"));
        assertRestored();
    }

    @Test
    void ownerQueuesSeparateCarrierMovementFromRiderRestoration() {
        transport.queued = true;
        camera.ride(rider, PATH, options(true));
        transport.drain();
        Assertions.assertTrue(calls.contains("setSpectatorTarget:carrier"));
        camera.tick();
        transport.drain();
        camera.stop(rider, CameraService.EndReason.END);
        Assertions.assertTrue(new CameraJournal(sections).read(RIDER).isPresent());
        transport.drain();
        assertRestored();
    }

    @Test
    void failedDurableJournalWriteDoesNotMutateThePlayerOrSpawnACarrier() throws Exception {
        java.nio.file.Files.createFile(dataFolder.resolve("state"));

        Assertions.assertFalse(camera.ride(rider, PATH, options(true)));

        Assertions.assertTrue(calls.isEmpty(), calls.toString());
        Assertions.assertFalse(camera.riding(RIDER));
        Assertions.assertTrue(new CameraJournal(sections).read(RIDER).isEmpty());
    }

    private final class ControlledTransport implements CameraService.Transport {
        private final Queue<Runnable> tasks = new ArrayDeque<>();
        private Entity owner;
        private boolean queued;
        private boolean rejectPlayer;
        private boolean rejectCarrier;
        private CompletableFuture<Boolean> playerTeleport;
        private CompletableFuture<Boolean> carrierTeleport;
        private int carrierMoves;

        @Override
        public boolean execute(Entity entity, Runnable action, Runnable retired) {
            if (entity == rider ? rejectPlayer : rejectCarrier) {
                return false;
            }
            Runnable owned = () -> {
                Entity previous = owner;
                owner = entity;
                try {
                    if (entity.isValid()) {
                        action.run();
                    } else {
                        retired.run();
                    }
                } finally {
                    owner = previous;
                }
            };
            if (queued && owner != entity) {
                tasks.add(owned);
            } else {
                owned.run();
            }
            return true;
        }

        @Override
        public CompletableFuture<Boolean> teleport(Entity entity, Location destination) {
            Assertions.assertSame(entity, owner);
            if (entity == rider) {
                calls.add("teleport");
                return playerTeleport == null ? CompletableFuture.completedFuture(true) : playerTeleport;
            }
            carrierMoves++;
            return carrierTeleport == null ? CompletableFuture.completedFuture(true) : carrierTeleport;
        }

        private void drain() {
            int remaining = 100;
            while (!tasks.isEmpty()) {
                Assertions.assertTrue(remaining-- > 0);
                tasks.remove().run();
            }
        }
    }

    private void assertRestored() {
        Assertions.assertTrue(calls.contains("setSpectatorTarget:null"), calls.toString());
        Assertions.assertTrue(calls.contains("setGameMode:SURVIVAL"), calls.toString());
        Assertions.assertTrue(calls.contains("teleport"), calls.toString());
        Assertions.assertTrue(new CameraJournal(sections).read(RIDER).isEmpty());
    }

    private static CameraService.Options options(boolean skippable) {
        return new CameraService.Options(skippable, false);
    }

    private Entity carrier() {
        return (ArmorStand) CharacterizationSupport.proxy(new Class<?>[]{ArmorStand.class},
            (proxy, method, args) -> switch (method.getName()) {
                case "getUniqueId" -> UUID.randomUUID();
                case "isValid" -> !carrierRemoved.get();
                case "remove" -> {
                    Assertions.assertSame(proxy, transport.owner);
                    carrierRemoved.set(true);
                    yield null;
                }
                case "teleport" -> true;
                case "teleportAsync" -> java.util.concurrent.CompletableFuture.completedFuture(Boolean.TRUE);
                case "getLocation" -> new Location(world, 0, 64, 0);
                case "setGravity", "setInvisible", "setMarker", "setSilent", "setPersistent",
                     "setInvulnerable", "setCollidable", "setVisibleByDefault", "setBasePlate",
                     "setSmall", "setCustomNameVisible", "setCanMove", "setCanTick" -> null;
                default -> CharacterizationSupport.identity(proxy, method, args);
            });
    }
}
