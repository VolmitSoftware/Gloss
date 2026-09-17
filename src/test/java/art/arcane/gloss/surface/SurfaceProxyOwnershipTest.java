package art.arcane.gloss.surface;

import art.arcane.gloss.Gloss;
import art.arcane.gloss.GlossConfig;
import art.arcane.gloss.condition.BoundedConditionErrorCallback;
import art.arcane.gloss.condition.ShowCondition;
import art.arcane.gloss.config.GlossConfigFile;
import art.arcane.gloss.doc.DocumentEnvelope;
import art.arcane.gloss.proxy.BackendProxyOwnership;
import art.arcane.gloss.proxy.OwnershipProtocol;
import art.arcane.gloss.text.TextPipeline;
import art.arcane.volmlib.util.hud.HudSlot;
import art.arcane.volmlib.util.scheduling.SchedulerRuntime;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.Server;
import org.bukkit.boss.BarColor;
import org.bukkit.boss.BarStyle;
import org.bukkit.entity.Player;
import org.bukkit.plugin.java.JavaPlugin;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.File;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.logging.Logger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SurfaceProxyOwnershipTest {
    private static final byte[] KEY = "ownership-test-key-with-at-least-32-bytes".getBytes(StandardCharsets.UTF_8);

    @TempDir
    File folder;
    private final List<String> delivered = new ArrayList<>();
    private Gloss plugin;
    private BackendProxyOwnership ownership;
    private SurfaceService surfaces;
    private Player player;
    private UUID playerId;
    private Object previousServer;

    @BeforeEach
    void prepare() throws Exception {
        previousServer = field(Bukkit.class, "server").get(null);
        playerId = UUID.randomUUID();
        Server server = (Server) Proxy.newProxyInstance(Server.class.getClassLoader(), new Class<?>[]{Server.class},
            (proxy, method, arguments) -> switch (method.getName()) {
                case "isPrimaryThread", "isTickThread", "isGlobalTickThread", "isOwnedByCurrentRegion" -> true;
                case "getOnlinePlayers" -> List.of(player);
                default -> null;
            });
        field(Bukkit.class, "server").set(null, server);
        plugin = allocate(Gloss.class);
        set(plugin, JavaPlugin.class, "dataFolder", folder);
        set(plugin, JavaPlugin.class, "server", server);
        set(plugin, JavaPlugin.class, "logger", Logger.getAnonymousLogger());
        set(plugin, JavaPlugin.class, "isEnabled", true);
        GlossConfigFile config = new GlossConfigFile();
        config.text.functions = false;
        config.text.placeholders = false;
        config.normalize();
        set(plugin, Gloss.class, "config", GlossConfig.from(config));
        set(plugin, Gloss.class, "text", new TextPipeline(plugin));
        set(plugin, Gloss.class, "scheduler", new SchedulerRuntime(() -> plugin, Runnable::run,
            ignored -> {}, ignored -> {}, failure -> { throw new AssertionError(failure); }));
        ownership = new BackendProxyOwnership(plugin);
        set(ownership, BackendProxyOwnership.class, "enabled", true);
        set(ownership, BackendProxyOwnership.class, "key", KEY);
        set(plugin, Gloss.class, "proxyOwnership", ownership);
        surfaces = allocate(SurfaceService.class);
        set(surfaces, SurfaceService.class, "plugin", plugin);
        set(surfaces, SurfaceService.class, "driver",
            new SurfaceDriver(new RecordingDelivery(), (viewer, raw, scope) -> raw));
        set(surfaces, SurfaceService.class, "conditionErrors", BoundedConditionErrorCallback.silent());
        set(surfaces, SurfaceService.class, "byKind", SurfaceDriver.byKind(List.of(
            SurfaceRuntime.compile("hello", new SurfaceDoc(SurfaceDoc.CURRENT_SCHEMA_VERSION,
                DocumentEnvelope.INITIAL_REVISION, SurfaceKind.ACTIONBAR, ShowCondition.ALWAYS,
                new SurfaceDoc.Selection(10, "true"),
                new SurfaceDoc.Presentation("Hello", null, null, null, null, null, null, null, null, null,
                    null, null, null, null), List.of())),
            SurfaceRuntime.compile("event", new SurfaceDoc(SurfaceDoc.CURRENT_SCHEMA_VERSION,
                DocumentEnvelope.INITIAL_REVISION, SurfaceKind.BOSSBAR, ShowCondition.ALWAYS,
                new SurfaceDoc.Selection(10, "true"),
                new SurfaceDoc.Presentation(null, null, "Event", null, "1", "red", "solid", null, null, null,
                    null, null, null, null), List.of())))));
        set(plugin, Gloss.class, "laneServices", List.of(surfaces));
        player = (Player) Proxy.newProxyInstance(Player.class.getClassLoader(), new Class<?>[]{Player.class},
            (proxy, method, arguments) -> switch (method.getName()) {
                case "getUniqueId" -> playerId;
                case "getName" -> "Alex";
                case "getLocation" -> new Location(null, 0, 64, 0);
                case "isOnline" -> true;
                case "sendPluginMessage" -> null;
                default -> throw new UnsupportedOperationException(method.getName());
            });
    }

    @AfterEach
    void close() throws Exception {
        field(Bukkit.class, "server").set(null, previousServer);
    }

    @Test
    void aProxyClaimClearsDeliveredSurfacesAndStopsTheSweep() throws Exception {
        sweep();
        assertEquals(List.of("actionbar:gloss:surface:hello:Hello", "bossbar:gloss:surface:event:Event"), delivered);
        delivered.clear();

        claim(OwnershipProtocol.SURFACES);

        assertTrue(ownership.ownsSurfaces(playerId));
        assertEquals(List.of("clear-actionbar:gloss:surface:hello", "hide-bossbar:gloss:surface:event"), delivered);
        delivered.clear();
        sweep();
        assertTrue(delivered.isEmpty());
    }

    @Test
    void releasingTheClaimReappliesEverySurfaceImmediately() throws Exception {
        sweep();
        claim(OwnershipProtocol.SURFACES);
        delivered.clear();

        claim(0);

        assertFalse(ownership.ownsSurfaces(playerId));
        assertEquals(List.of("actionbar:gloss:surface:hello:Hello", "bossbar:gloss:surface:event:Event"), delivered);
        delivered.clear();
        sweep();
        assertEquals(List.of("actionbar:gloss:surface:hello:Hello", "bossbar:gloss:surface:event:Event"), delivered);
    }

    private void sweep() throws Exception {
        Method apply = SurfaceService.class.getDeclaredMethod("apply", Player.class);
        apply.setAccessible(true);
        apply.invoke(surfaces, player);
    }

    private void claim(int mask) throws Exception {
        Method apply = BackendProxyOwnership.class.getDeclaredMethod("apply", Player.class, int.class, long.class);
        apply.setAccessible(true);
        apply.invoke(ownership, player, mask, mask == 0 ? 0L : System.currentTimeMillis() + 15_000L);
    }

    private static Field field(Class<?> type, String name) throws Exception {
        Field field = type.getDeclaredField(name);
        field.setAccessible(true);
        return field;
    }

    private static void set(Object target, Class<?> type, String name, Object value) throws Exception {
        field(type, name).set(target, value);
    }

    private static <T> T allocate(Class<T> type) throws Exception {
        Class<?> allocatorClass = Class.forName("sun.misc.Unsafe");
        Object allocator = field(allocatorClass, "theUnsafe").get(null);
        return type.cast(allocatorClass.getMethod("allocateInstance", Class.class).invoke(allocator, type));
    }

    private final class RecordingDelivery implements SurfaceDelivery {
        @Override
        public void actionBar(Player viewer, String purpose, int priority, long ttlMillis, List<HudSlot> slots,
                              String text) {
            delivered.add("actionbar:" + purpose + ":" + text);
        }

        @Override
        public void clearActionBar(Player viewer, String purpose) {
            delivered.add("clear-actionbar:" + purpose);
        }

        @Override
        public boolean bossBar(Player viewer, String laneId, int priority, String title, double progress,
                               BarColor color, BarStyle style, long staleMillis) {
            delivered.add("bossbar:" + laneId + ":" + title);
            return true;
        }

        @Override
        public void hideBossBar(Player viewer, String laneId) {
            delivered.add("hide-bossbar:" + laneId);
        }

        @Override
        public void title(Player viewer, String purpose, int priority, String title, String subtitle,
                          int fadeInTicks, int stayTicks, int fadeOutTicks) {
            delivered.add("title:" + purpose + ":" + title);
        }

        @Override
        public void clearTitle(Player viewer, String purpose) {
            delivered.add("clear-title:" + purpose);
        }

        @Override
        public void forget(UUID viewerId) {
            delivered.add("forget:" + viewerId);
        }
    }
}
