package art.arcane.gloss.behavior;

import art.arcane.gloss.Gloss;
import art.arcane.gloss.GlossConfig;
import art.arcane.gloss.api.HoloClickTrigger;
import art.arcane.gloss.camera.CameraService;
import art.arcane.gloss.config.GlossConfigFile;
import art.arcane.gloss.config.action.MenuActionData;
import art.arcane.gloss.doc.DocumentParsers;
import art.arcane.gloss.expr.ExprFunctions;
import art.arcane.gloss.expr.ExprScope;
import art.arcane.gloss.expr.ExprVariableContext;
import art.arcane.gloss.menu.CharacterizationSupport;
import art.arcane.gloss.menu.action.ActionContext;
import art.arcane.gloss.menu.action.ActionOutcome;
import art.arcane.gloss.menu.action.BossBarMenuAction;
import art.arcane.gloss.menu.action.MenuAction;
import art.arcane.gloss.menu.action.NavigationRequest;
import art.arcane.gloss.menu.action.NavigationResult;
import art.arcane.gloss.menu.action.TitleMenuAction;
import art.arcane.gloss.state.PlayerSections;
import art.arcane.gloss.state.StateSchema;
import art.arcane.gloss.state.StateScope;
import art.arcane.gloss.state.StateStore;
import art.arcane.gloss.state.StateStores;
import art.arcane.gloss.state.StateType;
import art.arcane.gloss.surface.SurfaceDelivery;
import art.arcane.gloss.config.action.BossBarActionData;
import art.arcane.gloss.config.action.TitleActionData;
import art.arcane.volmlib.util.hud.HudSlot;
import org.bukkit.GameMode;
import org.bukkit.Location;
import org.bukkit.Server;
import org.bukkit.World;
import org.bukkit.boss.BarColor;
import org.bukkit.boss.BarStyle;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Player;
import org.bukkit.plugin.PluginManager;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * A scene is a behavior's {@code sequence} of cues, and its cues are the same actions every other
 * surface has. Neither the behaviors lane nor the world and screen lanes could prove they compose,
 * because each was built against the seam alone; these assertions are that proof.
 */
class SceneCueTest {
    private static final UUID RIDER = UUID.nameUUIDFromBytes("scene-rider".getBytes());
    private static final String RIDE = "{\"type\":\"camera\",\"path\":["
        + "{\"x\":0,\"y\":64,\"z\":0,\"durationTicks\":40},{\"x\":0,\"y\":64,\"z\":20}]}";

    @TempDir
    Path dataFolder;

    private Object previousServer;
    private Gloss previousPlugin;
    private Gloss plugin;
    private Player rider;
    private World world;
    private CameraService camera;
    private StateStore state;

    @BeforeEach
    void setUp() throws ReflectiveOperationException {
        UUID worldId = UUID.nameUUIDFromBytes("scene-world".getBytes());
        world = (World) CharacterizationSupport.proxy(new Class<?>[]{World.class},
            (proxy, method, args) -> switch (method.getName()) {
                case "getName" -> "world";
                case "getUID" -> worldId;
                case "spawn" -> carrier();
                default -> CharacterizationSupport.identity(proxy, method, args);
            });
        rider = (Player) CharacterizationSupport.proxy(new Class<?>[]{Player.class},
            (proxy, method, args) -> switch (method.getName()) {
                case "getUniqueId" -> RIDER;
                case "getName" -> "rider";
                case "isOnline", "isValid" -> true;
                case "getWorld" -> world;
                case "getLocation" -> new Location(world, 0, 64, 0, 0.0F, 0.0F);
                case "getGameMode" -> GameMode.SURVIVAL;
                case "getAllowFlight", "isFlying" -> false;
                case "getVelocity" -> new org.bukkit.util.Vector();
                case "hasPermission" -> true;
                case "setGameMode", "setSpectatorTarget", "setAllowFlight", "setFlying", "setVelocity" -> null;
                case "teleport" -> true;
                case "teleportAsync" -> java.util.concurrent.CompletableFuture.completedFuture(Boolean.TRUE);
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
        camera = new CameraService(plugin, new PlayerSections(dataFolder));
        CharacterizationSupport.setField(plugin, "laneServices", List.<Object>of(camera));
        previousPlugin = CharacterizationSupport.installGloss(plugin);

        state = new StateStore(dataFolder, Runnable::run, (player, task) -> task.run());
        state.declare(java.util.Map.of("scene", List.of(
            new StateSchema("cue", StateScope.PLAYER, StateType.STRING, "none"))));
        state.beginLoad(RIDER);
        StateStores.install(state);
    }

    @AfterEach
    void tearDown() {
        StateStores.install(null);
        CharacterizationSupport.restoreGloss(previousPlugin);
        try {
            CharacterizationSupport.restoreServer(previousServer);
        } catch (ReflectiveOperationException failure) {
            throw new IllegalStateException(failure);
        }
    }

    @Test
    void aCameraCueStartsTheRideAndTheLaterCuesStillFire() {
        MenuActionData scene = parse("{\"type\":\"sequence\",\"steps\":["
            + "{\"type\":\"camera\",\"atTicks\":0,\"path\":["
            + "{\"x\":0,\"y\":64,\"z\":0,\"durationTicks\":40},{\"x\":0,\"y\":64,\"z\":20}]},"
            + "{\"type\":\"setState\",\"atTicks\":20,\"key\":\"cue\",\"value\":\"'title'\"},"
            + "{\"type\":\"setState\",\"atTicks\":40,\"key\":\"cue\",\"value\":\"'done'\"}]}");

        ActionProgram.run(MenuAction.resolve(List.of(scene), "behavior:intro", "on:0"), 0, context(), immediate());

        assertTrue(camera.riding(RIDER), "the camera cue must have started the ride");
        assertEquals("done", state.get(StateScope.PLAYER, RIDER, "cue"),
            "every cue after the ride must still fire");
    }

    @Test
    void aCameraClickOutsideASceneStillEndsItsActionList() {
        List<MenuAction<?>> click = MenuAction.resolve(
            List.of(parse(RIDE), parse("{\"type\":\"setState\",\"key\":\"cue\",\"value\":\"'after'\"}")),
            "menu:lobby", "button");

        assertEquals(ActionOutcome.STOP, MenuAction.execute(click, context()));
        assertTrue(camera.riding(RIDER));
        assertEquals("none", state.get(StateScope.PLAYER, RIDER, "cue"),
            "a ride takes over the surface the click came from");
    }

    @Test
    void titleAndBossBarCuesReachTheSharedCompositorUnderTheBehaviorsPurpose() {
        List<String> published = new ArrayList<>();
        SurfaceDelivery delivery = recording(published);
        TitleActionData title = new TitleActionData("&aIntro", "&7part one", null, null, null, null, null, null, null);
        BossBarActionData bar = new BossBarActionData("&aIntro", null, null, null, null, null, null, null, null, null);

        MenuAction.execute(List.of(new TitleMenuAction(title, delivery), new BossBarMenuAction(bar, delivery)),
            context());

        assertEquals(2, published.size(), published.toString());
        assertTrue(published.get(0).startsWith("title:gloss:action:behavior:intro/on:0"), published.toString());
        assertTrue(published.get(1).startsWith("bossbar:gloss:action:behavior:intro/on:0"), published.toString());
    }

    private static MenuActionData parse(String json) {
        return DocumentParsers.GSON.fromJson(json, MenuActionData.class);
    }

    private static ActionProgram.Continuation immediate() {
        return (delayTicks, task) -> {
            task.run();
            return true;
        };
    }

    private ActionContext context() {
        ExprScope scope = new ExprScope() {
            @Override
            public Object variable(String dottedName) {
                return null;
            }

            @Override
            public Object call(String name, List<Object> args) {
                return ExprFunctions.call(name, args);
            }

            @Override
            public ExprVariableContext variableContext() {
                return new ExprVariableContext(rider, rider, null, rider.getLocation());
            }
        };
        return new ActionContext() {
            @Override
            public Player player() {
                return rider;
            }

            @Override
            public String menuId() {
                return "behavior:intro";
            }

            @Override
            public String componentId() {
                return "on:0";
            }

            @Override
            public HoloClickTrigger trigger() {
                return HoloClickTrigger.ANY;
            }

            @Override
            public NavigationResult navigate(NavigationRequest request) {
                return NavigationResult.DENIED;
            }

            @Override
            public ExprScope conditionScope() {
                return scope;
            }
        };
    }

    private static SurfaceDelivery recording(List<String> published) {
        return new SurfaceDelivery() {
            @Override
            public void actionBar(Player viewer, String purpose, int priority, long ttlMillis, List<HudSlot> slots,
                                  String text) {
                published.add("actionbar:" + purpose);
            }

            @Override
            public void clearActionBar(Player viewer, String purpose) {
            }

            @Override
            public void clearTitle(Player viewer, String purpose) {
                published.add("cleartitle:" + purpose);
            }

            @Override
            public boolean bossBar(Player viewer, String laneId, int priority, String title, double progress,
                                   BarColor color, BarStyle style, long staleMillis) {
                published.add("bossbar:" + laneId);
                return true;
            }

            @Override
            public void hideBossBar(Player viewer, String laneId) {
            }

            @Override
            public void title(Player viewer, String purpose, int priority, String title, String subtitle,
                              int fadeInTicks, int stayTicks, int fadeOutTicks) {
                published.add("title:" + purpose);
            }

            @Override
            public void forget(UUID viewerId) {
            }
        };
    }

    private Entity carrier() {
        return (Entity) CharacterizationSupport.proxy(new Class<?>[]{org.bukkit.entity.ArmorStand.class},
            (proxy, method, args) -> switch (method.getName()) {
                case "getUniqueId" -> UUID.nameUUIDFromBytes("scene-carrier".getBytes());
                case "getWorld" -> world;
                case "getLocation" -> new Location(world, 0, 64, 0);
                case "isValid" -> true;
                default -> CharacterizationSupport.identity(proxy, method, args);
            });
    }
}
