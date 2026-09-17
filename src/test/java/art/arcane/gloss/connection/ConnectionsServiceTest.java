package art.arcane.gloss.connection;

import art.arcane.gloss.Gloss;
import art.arcane.gloss.GlossConfig;
import art.arcane.gloss.config.GlossConfigFile;
import art.arcane.gloss.doc.DataWatchdog;
import art.arcane.gloss.doc.DocumentRegistry;
import art.arcane.gloss.doc.GlossDocument;
import art.arcane.gloss.proxy.BackendProxyOwnership;
import art.arcane.gloss.proxy.OwnershipProtocol;
import art.arcane.gloss.tab.TablistService;
import art.arcane.gloss.text.TextPipeline;
import art.arcane.volmlib.util.scheduling.SchedulerRuntime;
import net.kyori.adventure.text.minimessage.MiniMessage;
import net.kyori.adventure.text.serializer.legacy.LegacyComponentSerializer;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.Server;
import org.bukkit.entity.Player;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.plugin.PluginManager;
import org.bukkit.scheduler.BukkitScheduler;
import org.bukkit.scheduler.BukkitTask;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.File;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.logging.Handler;
import java.util.logging.LogRecord;
import java.util.logging.Logger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ConnectionsServiceTest {
    private static final String VANILLA_JOIN = "§eAlex joined the game";
    private static final String VANILLA_QUIT = "§eAlex left the game";

    @TempDir
    File folder;
    private final List<Player> online = new ArrayList<>();
    private final Map<String, List<String>> received = new LinkedHashMap<>();
    private final List<Runnable> scheduled = new ArrayList<>();
    private final List<String> console = new ArrayList<>();
    private Gloss plugin;
    private BackendProxyOwnership ownership;
    private ConnectionsService service;
    private Player alex;
    private Player robin;
    private Object previousServer;
    private Logger fallbackLogger;
    private Handler consoleHandler;

    @BeforeEach
    void prepare() throws Exception {
        previousServer = field(Bukkit.class, "server").get(null);
        field(Bukkit.class, "server").set(null, server());
        plugin = allocate(Gloss.class);
        set(plugin, JavaPlugin.class, "dataFolder", folder);
        set(plugin, JavaPlugin.class, "server", Bukkit.getServer());
        set(plugin, JavaPlugin.class, "logger", Logger.getAnonymousLogger());
        set(plugin, JavaPlugin.class, "isEnabled", true);
        set(plugin, Gloss.class, "scheduler", new SchedulerRuntime(() -> plugin, Runnable::run,
            ignored -> { }, ignored -> { }, failure -> { throw new AssertionError(failure); }));
        set(plugin, Gloss.class, "watchdog", new DataWatchdog(plugin));
        configure(true);
        ownership = new BackendProxyOwnership(plugin);
        set(plugin, Gloss.class, "proxyOwnership", ownership);
        set(plugin, Gloss.class, "tablist", new TablistService(plugin));
        alex = player("Alex");
        robin = player("Robin");
        online.add(alex);
        online.add(robin);
        fallbackLogger = (Logger) field(Gloss.class, "FALLBACK_LOGGER").get(null);
        consoleHandler = new Handler() {
            @Override
            public void publish(LogRecord record) {
                console.add(record.getMessage());
            }

            @Override
            public void flush() {
            }

            @Override
            public void close() {
            }
        };
        fallbackLogger.addHandler(consoleHandler);
    }

    @AfterEach
    void cleanup() throws Exception {
        if (service != null) {
            service.disable();
        }
        fallbackLogger.removeHandler(consoleHandler);
        field(Bukkit.class, "server").set(null, previousServer);
    }

    @Test
    void enabledJoinAndQuitReplaceTheVanillaLineWithPerViewerText() throws Exception {
        document("""
            {"schemaVersion":1,"revision":1,
             "join":{"presentation":{"text":"&a+ &f{{ subject.name }} &7seen by &f{{ viewer.name }}"}},
             "leave":{"presentation":{"text":"&c- &f{{ subject.name }} &7left"}}}
            """);
        start();

        PlayerJoinEvent join = new PlayerJoinEvent(alex, VANILLA_JOIN);
        service.onJoin(join);

        assertNull(join.getJoinMessage());
        assertEquals(List.of("§a+ §fAlex §7seen by §fAlex"), messages("Alex"));
        assertEquals(List.of("§a+ §fAlex §7seen by §fRobin"), messages("Robin"));
        assertEquals(1, console.size());
        assertTrue(console.getFirst().endsWith("+ Alex seen by Alex"));

        received.clear();
        PlayerQuitEvent quit = new PlayerQuitEvent(alex, VANILLA_QUIT);
        service.onQuit(quit);

        assertNull(quit.getQuitMessage());
        assertEquals(List.of("§c- §fAlex §7left"), messages("Alex"));
        assertEquals(List.of("§c- §fAlex §7left"), messages("Robin"));
    }

    @Test
    void theHighestPriorityMatchingVariantWinsPerViewer() throws Exception {
        document("""
            {"schemaVersion":1,"revision":1,
             "join":{"presentation":{"text":"&7{{ subject.name }} joined"},
              "variants":[{"priority":1,"when":"viewer.name == 'Robin'",
                           "presentation":{"text":"&8{{ subject.name }} joined quietly"}},
                          {"priority":90,"when":"viewer.name == 'Robin' && subject.name == 'Alex'",
                           "presentation":{"text":"&6{{ subject.name }} joined loudly"}}]}}
            """);
        start();

        service.onJoin(new PlayerJoinEvent(alex, VANILLA_JOIN));

        assertEquals(List.of("§7Alex joined"), messages("Alex"));
        assertEquals(List.of("§6Alex joined loudly"), messages("Robin"));
    }

    @Test
    void aDisabledFeatureLeavesTheVanillaLinesAlone() throws Exception {
        configure(false);
        document("""
            {"schemaVersion":1,"revision":1,"join":{"presentation":{"text":"&aignored"}},
             "leave":{"presentation":{"text":"&cignored"}}}
            """);
        start();

        PlayerJoinEvent join = new PlayerJoinEvent(alex, VANILLA_JOIN);
        PlayerQuitEvent quit = new PlayerQuitEvent(alex, VANILLA_QUIT);
        service.onJoin(join);
        service.onQuit(quit);

        assertEquals(VANILLA_JOIN, join.getJoinMessage());
        assertEquals(VANILLA_QUIT, quit.getQuitMessage());
        assertTrue(received.isEmpty());
        assertTrue(console.isEmpty());
    }

    @Test
    void aDisabledSectionLeavesItsVanillaLineAlone() throws Exception {
        document("""
            {"schemaVersion":1,"revision":1,"join":{"presentation":{"text":"&a+ {{ subject.name }}"}}}
            """);
        start();

        PlayerQuitEvent quit = new PlayerQuitEvent(alex, VANILLA_QUIT);
        service.onQuit(quit);

        assertEquals(VANILLA_QUIT, quit.getQuitMessage());
        assertTrue(received.isEmpty());
    }

    @Test
    void aProxyThatOwnsConnectionsSilencesTheBackendCompletely() throws Exception {
        document("""
            {"schemaVersion":1,"revision":1,"join":{"presentation":{"text":"&a+ {{ subject.name }}"}},
             "leave":{"presentation":{"text":"&c- {{ subject.name }}"}}}
            """);
        start();
        claimConnections();

        PlayerJoinEvent join = new PlayerJoinEvent(alex, VANILLA_JOIN);
        PlayerQuitEvent quit = new PlayerQuitEvent(alex, VANILLA_QUIT);
        service.onJoin(join);
        service.onQuit(quit);

        assertNull(join.getJoinMessage());
        assertNull(quit.getQuitMessage());
        assertTrue(received.isEmpty());
        assertTrue(console.isEmpty());
        assertTrue(scheduled.isEmpty());
    }

    @Test
    void theFirstJoinHoldsTheLineUntilTheProxyClaimArrivesAndThenStaysSilent() throws Exception {
        document("""
            {"schemaVersion":1,"revision":1,"join":{"presentation":{"text":"&a+ {{ subject.name }}"}}}
            """);
        start();
        proxyChannelThatOwnedConnections();

        PlayerJoinEvent join = new PlayerJoinEvent(alex, VANILLA_JOIN);
        service.onJoin(join);

        assertNull(join.getJoinMessage());
        assertTrue(received.isEmpty());
        assertEquals(1, scheduled.size());

        runScheduled();
        assertTrue(received.isEmpty());
        assertEquals(1, scheduled.size());

        claimConnections();
        runScheduled();

        assertTrue(received.isEmpty());
        assertTrue(scheduled.isEmpty());
        assertTrue(console.isEmpty());
    }

    @Test
    void theFirstJoinAnnouncesLateWhenNoProxyClaimEverArrives() throws Exception {
        document("""
            {"schemaVersion":1,"revision":1,"join":{"presentation":{"text":"&a+ {{ subject.name }}"}}}
            """);
        start();
        proxyChannelThatOwnedConnections();

        PlayerJoinEvent join = new PlayerJoinEvent(alex, VANILLA_JOIN);
        service.onJoin(join);
        assertNull(join.getJoinMessage());

        for (int poll = 0; poll < 11; poll++) {
            runScheduled();
            assertTrue(received.isEmpty(), "announced after " + (poll + 1) + " polls");
        }
        runScheduled();

        assertEquals(List.of("§a+ Alex"), messages("Alex"));
        assertEquals(List.of("§a+ Alex"), messages("Robin"));
        assertTrue(scheduled.isEmpty());
    }

    @Test
    void theHeldVanillaLineIsReplayedWhenTheLocalFeatureIsOff() throws Exception {
        configure(false);
        document("""
            {"schemaVersion":1,"revision":1,"join":{"presentation":{"text":"&a+ {{ subject.name }}"}}}
            """);
        start();
        proxyChannelThatOwnedConnections();

        PlayerJoinEvent join = new PlayerJoinEvent(alex, VANILLA_JOIN);
        service.onJoin(join);
        assertNull(join.getJoinMessage());

        for (int poll = 0; poll < 12; poll++) {
            runScheduled();
        }

        assertEquals(List.of(VANILLA_JOIN), messages("Alex"));
        assertEquals(List.of(VANILLA_JOIN), messages("Robin"));
        assertTrue(scheduled.isEmpty());
        assertTrue(console.isEmpty());
    }

    @Test
    void aLiveProxyChannelThatNeverClaimedConnectionsAnnouncesImmediately() throws Exception {
        document("""
            {"schemaVersion":1,"revision":1,"join":{"presentation":{"text":"&a+ {{ subject.name }}"}}}
            """);
        start();
        set(ownership, BackendProxyOwnership.class, "enabled", true);

        PlayerJoinEvent join = new PlayerJoinEvent(alex, VANILLA_JOIN);
        service.onJoin(join);

        assertNull(join.getJoinMessage());
        assertEquals(List.of("\u00a7a+ Alex"), messages("Alex"));
        assertEquals(List.of("\u00a7a+ Alex"), messages("Robin"));
        assertTrue(scheduled.isEmpty());
    }

    @Test
    void aProxyWhoseLastClaimCarriedOnlyTablistDoesNotDelayJoins() throws Exception {
        document("""
            {"schemaVersion":1,"revision":1,"join":{"presentation":{"text":"&a+ {{ subject.name }}"}}}
            """);
        start();
        set(ownership, BackendProxyOwnership.class, "enabled", true);
        claim(OwnershipProtocol.TABLIST, System.currentTimeMillis() + 15_000L);
        assertFalse(ownership.proxyLastClaimedConnections());

        PlayerJoinEvent join = new PlayerJoinEvent(alex, VANILLA_JOIN);
        service.onJoin(join);

        assertNull(join.getJoinMessage());
        assertEquals(List.of("\u00a7a+ Alex"), messages("Alex"));
        assertTrue(scheduled.isEmpty());
    }

    @Test
    void theListenerStaysOffWhenNeitherTheFeatureNorTheProxyChannelIsLive() throws Exception {
        configure(false);
        start();

        assertFalse(service.enabled());
        assertFalse(ownership.enabled());
    }

    @Test
    void theLiveRegistryIsExposedUnderItsCollectionForHistoryAndTheLinter() throws Exception {
        document("""
            {"schemaVersion":1,"revision":7,"join":{"presentation":{"text":"&a+ {{ subject.name }}"}}}
            """);
        start();

        Map<String, DocumentRegistry<?>> registries = service.registries();

        assertEquals(Set.of(ConnectionsDoc.KIND), registries.keySet());
        GlossDocument<?> document = registries.get(ConnectionsDoc.KIND)
            .snapshot().get(ConnectionsDoc.KIND);
        assertEquals(7L, ((ConnectionsDoc) document.value()).revision());
    }

    private void start() {
        service = new ConnectionsService(plugin);
        service.enable();
    }

    private void configure(boolean connections) throws Exception {
        GlossConfigFile config = new GlossConfigFile();
        config.features.connections = connections;
        config.text.placeholders = false;
        config.normalize();
        set(plugin, Gloss.class, "config", GlossConfig.from(config));
        set(plugin, Gloss.class, "text", new TextPipeline(plugin));
    }

    private void document(String raw) throws Exception {
        Files.writeString(new File(folder, "connections.json").toPath(), raw, StandardCharsets.UTF_8);
    }

    private void claimConnections() throws Exception {
        claim(OwnershipProtocol.CONNECTIONS, System.currentTimeMillis() + 15_000L);
    }

    /** A proxy that claimed connection messages and whose lease has since lapsed, as after a restart. */
    private void proxyChannelThatOwnedConnections() throws Exception {
        set(ownership, BackendProxyOwnership.class, "enabled", true);
        claim(OwnershipProtocol.CONNECTIONS, System.currentTimeMillis() - 1L);
        assertFalse(ownership.ownsConnections());
        assertTrue(ownership.proxyLastClaimedConnections());
    }

    private void claim(int mask, long expiresAtMillis) throws Exception {
        invoke(ownership, "apply", new Class<?>[]{Player.class, int.class, long.class}, alex, mask,
            expiresAtMillis);
    }

    private void runScheduled() {
        List<Runnable> pending = new ArrayList<>(scheduled);
        scheduled.clear();
        for (Runnable task : pending) {
            task.run();
        }
    }

    private List<String> messages(String name) {
        return received.getOrDefault(name, List.of());
    }

    private Server server() {
        BukkitTask task = (BukkitTask) Proxy.newProxyInstance(BukkitTask.class.getClassLoader(),
            new Class<?>[]{BukkitTask.class}, (proxy, method, arguments) -> null);
        BukkitScheduler scheduler = (BukkitScheduler) Proxy.newProxyInstance(
            BukkitScheduler.class.getClassLoader(), new Class<?>[]{BukkitScheduler.class},
            (proxy, method, arguments) -> switch (method.getName()) {
                case "runTask", "runTaskLater" -> {
                    scheduled.add((Runnable) arguments[1]);
                    yield task;
                }
                case "scheduleSyncDelayedTask" -> {
                    scheduled.add((Runnable) arguments[1]);
                    yield Integer.valueOf(1);
                }
                default -> null;
            });
        PluginManager plugins = (PluginManager) Proxy.newProxyInstance(PluginManager.class.getClassLoader(),
            new Class<?>[]{PluginManager.class}, (proxy, method, arguments) -> null);
        return (Server) Proxy.newProxyInstance(Server.class.getClassLoader(), new Class<?>[]{Server.class},
            (proxy, method, arguments) -> switch (method.getName()) {
                case "isPrimaryThread", "isTickThread", "isGlobalTickThread", "isOwnedByCurrentRegion" -> true;
                case "getOnlinePlayers" -> List.copyOf(online);
                case "getScheduler" -> scheduler;
                case "getPluginManager" -> plugins;
                default -> null;
            });
    }

    private Player player(String name) {
        UUID id = UUID.randomUUID();
        return (Player) Proxy.newProxyInstance(Player.class.getClassLoader(), new Class<?>[]{Player.class},
            (proxy, method, arguments) -> switch (method.getName()) {
                case "getUniqueId" -> id;
                case "getName", "getDisplayName" -> name;
                case "getLocation" -> new Location(null, 0, 64, 0);
                case "isOnline" -> true;
                case "isOp" -> false;
                case "hasPermission" -> false;
                case "sendRichMessage" -> {
                    received.computeIfAbsent(name, ignored -> new ArrayList<>()).add(legacy((String) arguments[0]));
                    yield null;
                }
                case "sendPluginMessage" -> null;
                case "equals" -> proxy == arguments[0];
                case "hashCode" -> id.hashCode();
                case "toString" -> name;
                default -> throw new UnsupportedOperationException(method.getName());
            });
    }

    private static String legacy(String miniMessage) {
        return LegacyComponentSerializer.legacySection()
            .serialize(MiniMessage.miniMessage().deserialize(miniMessage));
    }

    private static Object invoke(Object target, String name, Class<?>[] parameterTypes, Object... arguments)
        throws Exception {
        Method method = target.getClass().getDeclaredMethod(name, parameterTypes);
        method.setAccessible(true);
        return method.invoke(target, arguments);
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
}
