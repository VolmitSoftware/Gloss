package art.arcane.gloss.motd;

import art.arcane.gloss.Gloss;
import art.arcane.gloss.GlossConfig;
import art.arcane.gloss.config.GlossConfigFile;
import art.arcane.gloss.doc.DocumentRegistry;
import art.arcane.gloss.proxy.BackendProxyOwnership;
import art.arcane.gloss.proxy.OwnershipProtocol;
import art.arcane.gloss.text.TextPipeline;
import org.bukkit.Bukkit;
import org.bukkit.Server;
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
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.logging.Logger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Server links fold under MOTD ownership: while the proxy owns the MOTD the backend publishes no
 * links of its own, so the proxy's per-player list is never overwritten by a server-wide one.
 */
class MotdProxyLinksTest {
    private static final byte[] KEY = "ownership-test-key-with-at-least-32-bytes".getBytes(StandardCharsets.UTF_8);

    @TempDir
    File folder;
    private Gloss plugin;
    private BackendProxyOwnership ownership;
    private MotdService motd;
    private RecordingPublisher publisher;
    private Player player;
    private UUID playerId;
    private Object previousServer;

    @BeforeEach
    void prepare() throws Exception {
        previousServer = field(Bukkit.class, "server").get(null);
        Server server = (Server) Proxy.newProxyInstance(Server.class.getClassLoader(), new Class<?>[]{Server.class},
            (proxy, method, arguments) -> switch (method.getName()) {
                case "isPrimaryThread", "isTickThread", "isGlobalTickThread", "isOwnedByCurrentRegion" -> true;
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
        ownership = new BackendProxyOwnership(plugin);
        set(ownership, BackendProxyOwnership.class, "enabled", true);
        set(ownership, BackendProxyOwnership.class, "key", KEY);
        set(plugin, Gloss.class, "proxyOwnership", ownership);
        Files.writeString(new File(folder, "motd.json").toPath(), """
            {"schemaVersion":1,"revision":4,"entries":[{"lines":["&dA glossy server"]}],
             "links":[{"type":"website","url":"https://example.org"},
                      {"label":"Discord","url":"https://discord.gg/example"}]}
            """);
        motd = new MotdService(plugin);
        registry().reload();
        publisher = new RecordingPublisher();
        set(motd, MotdService.class, "serverLinks", publisher);
        set(plugin, Gloss.class, "motd", motd);
        playerId = UUID.randomUUID();
        player = (Player) Proxy.newProxyInstance(Player.class.getClassLoader(), new Class<?>[]{Player.class},
            (proxy, method, arguments) -> switch (method.getName()) {
                case "getUniqueId" -> playerId;
                case "isOnline" -> true;
                default -> throw new UnsupportedOperationException(method.getName());
            });
    }

    @AfterEach
    void close() throws Exception {
        registry().close();
        field(Bukkit.class, "server").set(null, previousServer);
    }

    @Test
    void backendPublishesItsOwnLinksWhileNoProxyOwnsTheMotd() {
        motd.refreshProxyOwnership();

        assertFalse(ownership.ownsMotd());
        assertEquals(1, publisher.published.size());
        assertEquals(0, publisher.clears);
        List<MotdDoc.MotdLink> links = publisher.published.getLast();
        assertEquals(2, links.size());
        assertEquals("website", links.getFirst().type());
        assertEquals("https://example.org", links.getFirst().url());
        assertTrue(links.getLast().isLabelled());
        assertEquals("Discord", links.getLast().label());
    }

    @Test
    void proxyOwnedMotdClearsTheBackendLinksAndReleasingRepublishesThem() throws Exception {
        motd.refreshProxyOwnership();
        assertEquals(1, publisher.published.size());

        apply(OwnershipProtocol.MOTD, System.currentTimeMillis() + 15_000L);

        assertTrue(ownership.ownsMotd());
        assertEquals(1, publisher.clears);
        assertEquals(1, publisher.published.size());
        motd.refreshProxyOwnership();
        assertEquals(2, publisher.clears);
        assertEquals(1, publisher.published.size());

        apply(0, 0L);

        assertFalse(ownership.ownsMotd());
        assertEquals(2, publisher.published.size());
        assertEquals(2, publisher.clears);
    }

    private void apply(int mask, long expires) throws Exception {
        Method method = BackendProxyOwnership.class.getDeclaredMethod("apply", Player.class, int.class, long.class);
        method.setAccessible(true);
        method.invoke(ownership, player, mask, expires);
    }

    private DocumentRegistry<?> registry() {
        return motd.registries().get("motd");
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

    private static final class RecordingPublisher implements ServerLinksPublisher {
        private final List<List<MotdDoc.MotdLink>> published = new ArrayList<>();
        private int clears;

        @Override
        public void publish(List<MotdDoc.MotdLink> links) {
            published.add(List.copyOf(links));
        }

        @Override
        public void clear() {
            clears++;
        }
    }
}
