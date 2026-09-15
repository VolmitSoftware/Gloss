package art.arcane.gloss.proxy;

import art.arcane.gloss.Gloss;
import art.arcane.gloss.GlossConfig;
import art.arcane.gloss.board.BoardService;
import art.arcane.gloss.board.GlossBoardMeta;
import art.arcane.gloss.config.GlossConfigFile;
import art.arcane.gloss.motd.MotdService;
import art.arcane.gloss.tab.TablistDoc;
import art.arcane.gloss.tab.TablistService;
import art.arcane.gloss.text.TextPipeline;
import art.arcane.volmlib.util.board.Board;
import art.arcane.volmlib.util.board.BoardManager;
import art.arcane.volmlib.util.scheduling.SchedulerRuntime;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.Server;
import org.bukkit.entity.Player;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.event.server.ServerListPingEvent;
import org.bukkit.plugin.java.JavaPlugin;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.File;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.net.InetAddress;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.logging.Logger;
import java.util.logging.Handler;
import java.util.logging.LogRecord;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class BackendProxyOwnershipTest {
    private static final byte[] KEY = "ownership-test-key-with-at-least-32-bytes".getBytes(StandardCharsets.UTF_8);

    @TempDir
    File folder;
    private Gloss plugin;
    private BackendProxyOwnership ownership;
    private TablistService tabs;
    private BoardService boards;
    private RecordingManager manager;
    private Player player;
    private UUID playerId;
    private Object previousServer;
    private String listName;
    private final List<String> writes = new ArrayList<>();
    private final List<byte[]> messages = new ArrayList<>();

    @BeforeEach
    void prepare() throws Exception {
        previousServer = field(Bukkit.class, "server").get(null);
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
        tabs = new TablistService(plugin);
        boards = new BoardService(plugin);
        set(plugin, Gloss.class, "tablist", tabs);
        set(plugin, Gloss.class, "boards", boards);
        manager = allocate(RecordingManager.class);
        set(boards, BoardService.class, "ordinaryManager", manager);
        TablistDoc document = TablistDoc.parse("tablist.json", """
            {"schemaVersion":2,"revision":1,
             "headerFooter":{"enabled":true,"presentation":{"header":"Backend","footer":"Footer"}},
             "listNames":{"enabled":true,"presentation":{"format":"Local $player"}}}
            """);
        set(tabs, TablistService.class, "activeDoc", document);
        Method compile = Class.forName("art.arcane.gloss.tab.TablistRuntime")
            .getDeclaredMethod("compile", TablistDoc.class);
        compile.setAccessible(true);
        set(tabs, TablistService.class, "activeRuntime", compile.invoke(null, document));
        playerId = UUID.randomUUID();
        listName = "Alex";
        player = (Player) Proxy.newProxyInstance(Player.class.getClassLoader(), new Class<?>[]{Player.class},
            (proxy, method, arguments) -> switch (method.getName()) {
                case "getUniqueId" -> playerId;
                case "getName" -> "Alex";
                case "getLocation" -> new Location(null, 0, 64, 0);
                case "getPlayerListName" -> listName;
                case "isOnline" -> true;
                case "setPlayerListHeaderFooter" -> {
                    writes.add("header:" + arguments[0]);
                    yield null;
                }
                case "setPlayerListName" -> {
                    listName = arguments[0] == null ? "Alex" : (String) arguments[0];
                    writes.add("name:" + listName);
                    yield null;
                }
                case "sendPluginMessage" -> {
                    messages.add(((byte[]) arguments[2]).clone());
                    writes.add("message");
                    yield null;
                }
                default -> throw new UnsupportedOperationException(method.getName());
            });
    }

    @AfterEach
    void close() throws Exception {
        if (boards != null) {
            Object storage = field(BoardService.class, "storage").get(boards);
            invoke(storage, "shutdown", new Class<?>[0]);
        }
        field(Bukkit.class, "server").set(null, previousServer);
    }

    @Test
    void authenticatedClaimClearsBackendTabBeforeAcknowledgingAndBlocksRefreshes() throws Exception {
        renderTab();
        assertEquals(List.of("header:Backend", "name:Local Alex"), writes);
        byte[] reply = reply(OwnershipProtocol.TABLIST);
        writes.clear();

        ownership.onPluginMessageReceived(OwnershipProtocol.CHANNEL, player, reply);

        assertTrue(ownership.ownsTablist(playerId));
        assertEquals(List.of("header:", "name:Alex", "message"), writes);
        assertArrayEquals(reply, Arrays.copyOf(messages.getLast(), reply.length));
        assertEquals(reply.length + 1, messages.getLast().length);
        assertEquals(1, messages.getLast()[reply.length]);
        writes.clear();
        renderTab();
        tabs.refreshProxyOwnership(player);
        ownership.onPluginMessageReceived(OwnershipProtocol.CHANNEL, player, reply);
        assertTrue(writes.isEmpty());

        apply(0, 0L);
        renderTab();
        assertFalse(ownership.ownsTablist(playerId));
        assertEquals(List.of("header:Backend", "name:Local Alex"), writes);
    }

    @Test
    void unchangedRenewalAcknowledgesWithoutRequestingProxyReassertion() throws Exception {
        apply(OwnershipProtocol.TABLIST, System.currentTimeMillis() + 15_000L);
        byte[] reply = reply(OwnershipProtocol.TABLIST);
        writes.clear();

        ownership.onPluginMessageReceived(OwnershipProtocol.CHANNEL, player, reply);

        assertEquals(List.of("message"), writes);
        assertEquals(0, messages.getLast()[reply.length]);
        assertTrue(ownership.ownsTablist(playerId));
    }

    @Test
    void forgedAndWrongChannelRepliesCannotSuppressLocalFeatures() throws Exception {
        byte[] reply = reply(OwnershipProtocol.TABLIST | OwnershipProtocol.SCOREBOARD);
        writes.clear();
        ownership.onPluginMessageReceived("other:channel", player, reply);
        reply[reply.length - 1] ^= 1;
        ownership.onPluginMessageReceived(OwnershipProtocol.CHANNEL, player, reply);
        assertFalse(ownership.ownsTablist(playerId));
        assertFalse(ownership.ownsScoreboard(playerId));
        assertTrue(writes.isEmpty());
    }

    @Test
    void scoreboardClaimRemovesOnceAndReleaseRestoresExplicitSelection() throws Exception {
        GlossBoardMeta meta = new GlossBoardMeta("local");
        Map<String, GlossBoardMeta> metas = boardMetas();
        metas.put(meta.id(), meta);
        boards.setBoard(player, "local");
        assertEquals(1, manager.setups);
        assertTrue(manager.present);

        apply(OwnershipProtocol.SCOREBOARD, System.currentTimeMillis() + 15_000L);
        boards.setBoard(player, "local");
        boards.refreshProxyOwnership(player);
        assertEquals(1, manager.removals);
        assertEquals(1, manager.setups);
        assertFalse(manager.present);
        assertFalse(ownership.ownsTablist(playerId));

        apply(0, 0L);
        assertEquals(2, manager.setups);
        assertTrue(manager.present);
    }

    @Test
    void expiredClaimReleasesOnPlayerRefreshAndRequestsNewProof() throws Exception {
        apply(OwnershipProtocol.TABLIST | OwnershipProtocol.MOTD, System.currentTimeMillis() - 1L);
        assertTrue(ownership.ownsTablist(playerId));
        assertFalse(ownership.ownsMotd());

        invoke(ownership, "refresh", new Class<?>[]{Player.class}, player);

        assertFalse(ownership.ownsTablist(playerId));
        assertEquals(1, messages.size());
        assertEquals(playerId, OwnershipProtocol.decodeRequest(messages.getFirst()).playerId());
    }

    @Test
    void proxyMotdLeavesBackendPingUnmodifiedAndQuitDropsOwnership() throws Exception {
        apply(OwnershipProtocol.MOTD, System.currentTimeMillis() + 15_000L);
        MotdService motd = new MotdService(plugin);
        ServerListPingEvent ping = new ServerListPingEvent("example.test", InetAddress.getLoopbackAddress(), "Original", 20) {};

        invoke(motd, "handlePing", new Class<?>[]{ServerListPingEvent.class}, ping);

        assertTrue(ownership.ownsMotd());
        assertEquals("Original", ping.getMotd());
        assertEquals(20, ping.getMaxPlayers());
        ownership.onQuit(new PlayerQuitEvent(player, ""));
        assertFalse(ownership.ownsMotd());
    }

    @Test
    void consoleReportsOwnershipChangesWithoutRepeatingRenewals() throws Exception {
        List<String> reports = new ArrayList<>();
        Logger logger = (Logger) field(Gloss.class, "FALLBACK_LOGGER").get(null);
        Handler handler = new Handler() {
            @Override
            public void publish(LogRecord record) {
                reports.add(record.getMessage());
            }

            @Override
            public void flush() {
            }

            @Override
            public void close() {
            }
        };
        logger.addHandler(handler);
        try {
            invoke(ownership, "reportOwnership", new Class<?>[0]);
            assertTrue(reports.isEmpty());
            apply(OwnershipProtocol.TABLIST | OwnershipProtocol.MOTD, System.currentTimeMillis() + 15_000L);
            invoke(ownership, "reportOwnership", new Class<?>[0]);
            assertEquals(1, reports.size());
            assertTrue(reports.getLast().contains("suspended: tablists (proxy-owned players), MOTD"));

            apply(OwnershipProtocol.TABLIST | OwnershipProtocol.MOTD, System.currentTimeMillis() + 15_000L);
            invoke(ownership, "reportOwnership", new Class<?>[0]);
            assertEquals(1, reports.size());

            apply(OwnershipProtocol.TABLIST, System.currentTimeMillis() + 15_000L);
            invoke(ownership, "reportOwnership", new Class<?>[0]);
            assertEquals(2, reports.size());
            assertTrue(reports.getLast().contains("returned to local configuration: MOTD"));

            ownership.onQuit(new PlayerQuitEvent(player, ""));
            invoke(ownership, "reportOwnership", new Class<?>[0]);
            assertEquals(3, reports.size());
            assertTrue(reports.getLast().contains("suspended: none"));
            assertTrue(reports.getLast().contains("returned to local configuration: tablists (proxy-owned players)"));
        } finally {
            logger.removeHandler(handler);
        }
    }

    private byte[] reply(int mask) throws Exception {
        invoke(ownership, "refresh", new Class<?>[]{Player.class}, player);
        OwnershipProtocol.Request request = OwnershipProtocol.decodeRequest(messages.getLast());
        return OwnershipProtocol.reply(request, mask, System.currentTimeMillis() + 15_000L, KEY);
    }

    private void apply(int mask, long expires) throws Exception {
        invoke(ownership, "apply", new Class<?>[]{Player.class, int.class, long.class}, player, mask, expires);
    }

    private void renderTab() throws Exception {
        Class<?> heartbeat = Class.forName("art.arcane.gloss.tab.TablistService$HeaderFooterHeartbeatCycle");
        invoke(tabs, "apply", new Class<?>[]{Player.class, heartbeat}, player, null);
    }

    @SuppressWarnings("unchecked")
    private Map<String, GlossBoardMeta> boardMetas() throws Exception {
        return (Map<String, GlossBoardMeta>) field(BoardService.class, "metas").get(boards);
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

    private static final class RecordingManager extends BoardManager<Board> {
        private boolean present;
        private int setups;
        private int removals;

        private RecordingManager() {
            super(null, null, null);
        }

        @Override
        public boolean hasBoard(Player player) {
            return present;
        }

        @Override
        public void setup(Player player) {
            present = true;
            setups++;
        }

        @Override
        public void remove(Player player) {
            if (present) {
                present = false;
                removals++;
            }
        }
    }
}
