package art.arcane.gloss.sky;

import art.arcane.gloss.Gloss;
import art.arcane.gloss.GlossConfig;
import art.arcane.gloss.config.GlossConfigFile;
import art.arcane.gloss.menu.CharacterizationSupport;
import art.arcane.gloss.packets.PacketEventsStub;
import art.arcane.gloss.state.PlayerSections;
import org.bukkit.Server;
import org.bukkit.WeatherType;
import org.bukkit.World;
import org.bukkit.entity.Player;
import org.bukkit.event.player.PlayerChangedWorldEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

class SkyServiceRestoreTest {
    private static final UUID VIEWER = UUID.randomUUID();

    @TempDir
    Path dataFolder;

    private final List<String> calls = new ArrayList<>();
    private Object previousServer;
    private Gloss previousPlugin;
    private Gloss plugin;
    private Player viewer;
    private World world;
    private SkyService sky;
    private PlayerSections sections;
    private boolean owner;
    private boolean rejectOwner;
    private boolean failRestore;
    private boolean deferIo;
    private final List<Runnable> ioTasks = new ArrayList<>();

    @BeforeEach
    void setUp() throws ReflectiveOperationException {
        PacketEventsStub.install();
        UUID worldId = UUID.randomUUID();
        world = (World) CharacterizationSupport.proxy(new Class<?>[]{World.class},
            (proxy, method, args) -> switch (method.getName()) {
                case "getName" -> "world";
                case "getUID" -> worldId;
                case "getTime" -> 1000L;
                case "getWorldBorder" -> null;
                default -> CharacterizationSupport.identity(proxy, method, args);
            });
        viewer = (Player) CharacterizationSupport.proxy(new Class<?>[]{Player.class},
            (proxy, method, args) -> switch (method.getName()) {
                case "getUniqueId" -> VIEWER;
                case "getName" -> "sky-viewer";
                case "isOnline" -> true;
                case "getWorld" -> world;
                case "getPlayerTime" -> {
                    Assertions.assertTrue(owner);
                    yield 1000L;
                }
                case "setPlayerTime" -> {
                    Assertions.assertTrue(owner);
                    calls.add("setPlayerTime:" + args[0]);
                    yield null;
                }
                case "resetPlayerTime" -> {
                    Assertions.assertTrue(owner);
                    if (failRestore) throw new IllegalStateException("restore rejected");
                    calls.add("resetPlayerTime");
                    yield null;
                }
                case "setPlayerWeather" -> {
                    Assertions.assertTrue(owner);
                    calls.add("setPlayerWeather:" + args[0]);
                    yield null;
                }
                case "resetPlayerWeather" -> {
                    Assertions.assertTrue(owner);
                    calls.add("resetPlayerWeather");
                    yield null;
                }
                case "setWorldBorder" -> {
                    calls.add("setWorldBorder:" + args[0]);
                    yield null;
                }
                default -> CharacterizationSupport.identity(proxy, method, args);
            });
        Server server = (Server) CharacterizationSupport.proxy(new Class<?>[]{Server.class},
            (proxy, method, args) -> switch (method.getName()) {
                case "getPlayer" -> VIEWER.equals(args[0]) ? viewer : null;
                case "getLogger" -> CharacterizationSupport.mutedLogger();
                case "getOnlinePlayers" -> List.of(viewer);
                case "isPrimaryThread" -> true;
                default -> CharacterizationSupport.identity(proxy, method, args);
            });
        previousServer = CharacterizationSupport.installServer(server);
        plugin = CharacterizationSupport.bareGloss(server);
        GlossConfigFile file = new GlossConfigFile();
        file.normalize();
        CharacterizationSupport.setField(plugin, "config", GlossConfig.from(file));
        previousPlugin = CharacterizationSupport.installGloss(plugin);
        sections = new PlayerSections(dataFolder);
        sky = new SkyService(new SkyService.Dependencies(plugin, sections, new BorderApplier() {
            @Override
            public void apply(Player player, SkyOverride.Border border) {
                calls.add("applyBorder");
            }

            @Override
            public void restore(Player player) {
            }
        }, (player, action, retired) -> {
            if (rejectOwner) {
                return false;
            }
            owner = true;
            try {
                action.run();
            } finally {
                owner = false;
            }
            return true;
        }, task -> {
            if (deferIo) {
                ioTasks.add(task);
            } else {
                task.run();
            }
        }));
    }

    @AfterEach
    void tearDown() throws ReflectiveOperationException {
        CharacterizationSupport.restoreGloss(previousPlugin);
        CharacterizationSupport.restoreServer(previousServer);
        PacketEventsStub.uninstall();
    }

    @Test
    void applyingAnOverrideSetsTimeAndWeather() {
        sky.apply(viewer, new SkyOverride("arena", 18000L, "thunder", null, 0));

        Assertions.assertTrue(calls.contains("setPlayerTime:18000"), calls.toString());
        Assertions.assertTrue(calls.contains("setPlayerWeather:" + WeatherType.DOWNFALL), calls.toString());
    }

    @Test
    void releasingTheLastPurposeRestoresTheRealSky() {
        sky.apply(viewer, new SkyOverride("arena", 18000L, "thunder", null, 0));
        calls.clear();

        sky.release(viewer, "arena");

        Assertions.assertTrue(calls.contains("resetPlayerTime"), calls.toString());
        Assertions.assertTrue(calls.contains("resetPlayerWeather"), calls.toString());
    }

    @Test
    void releasingTheTopFallsBackToTheOverrideUnderneath() {
        sky.apply(viewer, new SkyOverride("arena", 18000L, null, null, 0));
        sky.apply(viewer, new SkyOverride("quest", 6000L, null, null, 0));
        calls.clear();

        sky.release(viewer, "quest");

        Assertions.assertTrue(calls.contains("setPlayerTime:18000"), calls.toString());
        Assertions.assertFalse(calls.contains("resetPlayerTime"), calls.toString());
    }

    @Test
    void quittingRestoresTheRealSky() {
        sky.apply(viewer, new SkyOverride("arena", 18000L, "thunder", null, 0));
        calls.clear();

        sky.onQuit(new PlayerQuitEvent(viewer, (String) null));

        Assertions.assertTrue(calls.contains("resetPlayerTime"), calls.toString());
    }

    @Test
    void changingWorldRestoresTheRealSky() {
        sky.apply(viewer, new SkyOverride("arena", 18000L, "thunder", null, 0));
        calls.clear();

        sky.onWorldChange(new PlayerChangedWorldEvent(viewer, world));

        Assertions.assertTrue(calls.contains("resetPlayerTime"), calls.toString());
    }

    @Test
    void disablingRestoresEveryViewer() {
        sky.apply(viewer, new SkyOverride("arena", 18000L, "thunder", null, 0));
        calls.clear();

        sky.disable();

        Assertions.assertTrue(calls.contains("resetPlayerTime"), calls.toString());
    }

    @Test
    void anActiveOverrideIsJournalledAndClearedOnRelease() {
        sky.apply(viewer, new SkyOverride("arena", 18000L, "thunder", null, 0));

        Assertions.assertEquals(Boolean.TRUE, sections.read(VIEWER, SkyService.SECTION).get("restore"));

        sky.release(viewer, "arena");

        Assertions.assertTrue(sections.read(VIEWER, SkyService.SECTION).isEmpty());
    }

    @Test
    void aJournalLeftBehindByACrashRestoresTheRealSkyOnJoin() {
        sections.write(VIEWER, SkyService.SECTION,
            java.util.Map.of("arena", java.util.Map.of("time", 18000.0D)));
        calls.clear();

        sky.restoreJournalled(viewer);

        Assertions.assertTrue(calls.contains("resetPlayerTime"), calls.toString());
        Assertions.assertTrue(sections.read(VIEWER, SkyService.SECTION).isEmpty());
    }

    @Test
    void aFadeWalksTheTimeAcrossItsTicksInsteadOfJumping() {
        sky.apply(viewer, new SkyOverride("arena", 18000L, null, null, 40));

        Assertions.assertTrue(calls.contains("setPlayerTime:1000"), calls.toString());
        calls.clear();

        sky.tickFades(20);

        Assertions.assertEquals(List.of("setPlayerTime:21500"), calls);
    }

    @Test
    void aFadeEndsOnItsTargetAndStopsTicking() {
        sky.apply(viewer, new SkyOverride("arena", 18000L, null, null, 40));
        sky.tickFades(40);
        calls.clear();

        sky.tickFades(40);

        Assertions.assertEquals(List.of(), calls);
    }
    @Test
    void aRejectedOwnerNeverFallsBackDirectlyAndRetriesOnTheDriver() {
        rejectOwner = true;
        sky.apply(viewer, new SkyOverride("arena", 18000L, null, null, 0));
        Assertions.assertTrue(calls.isEmpty());
        Assertions.assertFalse(sections.read(VIEWER, SkyService.SECTION).isEmpty());
        rejectOwner = false;
        sky.tickFades(2);
        Assertions.assertTrue(calls.contains("setPlayerTime:18000"));
    }

    @Test
    void failedRestorationKeepsItsJournalUntilTheRetrySucceeds() {
        sky.apply(viewer, new SkyOverride("arena", 18000L, null, null, 0));
        failRestore = true;
        sky.release(viewer, "arena");
        Assertions.assertFalse(sections.read(VIEWER, SkyService.SECTION).isEmpty());
        failRestore = false;
        sky.tickFades(2);
        Assertions.assertTrue(sections.read(VIEWER, SkyService.SECTION).isEmpty());
    }

    @Test
    void failedJournalWritePreventsEveryPlayerMutation() throws Exception {
        Files.writeString(dataFolder.resolve("state"), "blocked directory");
        sky.apply(viewer, new SkyOverride("arena", 18000L, null, null, 0));
        Assertions.assertTrue(calls.isEmpty());
        Assertions.assertTrue(sky.purposes(VIEWER).isEmpty());
    }

    @Test
    void aNewClaimWaitsForPreviousJournalCleanupBeforeItsOwnDurableWrite() {
        deferIo = true;
        sky.apply(viewer, new SkyOverride("first", 18000L, null, null, 0));
        Assertions.assertTrue(calls.isEmpty());
        ioTasks.removeFirst().run();
        Assertions.assertTrue(calls.contains("setPlayerTime:18000"));
        sky.release(viewer, "first");
        ioTasks.removeFirst().run();
        sky.apply(viewer, new SkyOverride("second", 6000L, null, null, 0));
        Assertions.assertFalse(calls.contains("setPlayerTime:6000"));
        ioTasks.removeFirst().run();
        Assertions.assertTrue(sections.read(VIEWER, SkyService.SECTION).isEmpty());
        Assertions.assertFalse(calls.contains("setPlayerTime:6000"));
        ioTasks.removeFirst().run();
        Assertions.assertTrue(calls.contains("setPlayerTime:6000"));
        Assertions.assertFalse(sections.read(VIEWER, SkyService.SECTION).isEmpty());
    }

    @Test
    void releasingATimeOnlyClaimRestoresTimeWithoutDroppingAnotherOwnersWeather() {
        sky.apply(viewer, new SkyOverride("weather", null, "rain", null, 0));
        sky.apply(viewer, new SkyOverride("time", 18000L, null, null, 0));
        calls.clear();
        sky.release(viewer, "time");
        Assertions.assertEquals(List.of("resetPlayerTime"), calls);
        Assertions.assertEquals(List.of("weather"), sky.purposes(VIEWER));
        Assertions.assertFalse(sections.read(VIEWER, SkyService.SECTION).isEmpty());
    }

    @Test
    void releasingAnUnownedPurposeDoesNotResetThePlayersSky() {
        sky.release(viewer, "unowned");
        Assertions.assertTrue(calls.isEmpty());
    }

    @Test
    void quittingCancelsARejectedPendingClaimWithoutApplyingItLater() {
        rejectOwner = true;
        sky.apply(viewer, new SkyOverride("arena", 18000L, null, null, 0));
        sky.onQuit(new PlayerQuitEvent(viewer, (String) null));
        rejectOwner = false;
        sky.tickFades(2);
        Assertions.assertTrue(calls.isEmpty());
        Assertions.assertTrue(sky.purposes(VIEWER).isEmpty());
        Assertions.assertFalse(sections.read(VIEWER, SkyService.SECTION).isEmpty());
    }

}
