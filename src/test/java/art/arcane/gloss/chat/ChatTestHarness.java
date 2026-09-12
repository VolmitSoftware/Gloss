package art.arcane.gloss.chat;

import art.arcane.gloss.Gloss;
import art.arcane.gloss.GlossConfig;
import art.arcane.gloss.config.GlossConfigFile;
import art.arcane.gloss.doc.DataWatchdog;
import art.arcane.gloss.menu.CharacterizationSupport;
import art.arcane.gloss.text.TextPipeline;
import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.entity.Player;

import java.io.File;
import java.lang.reflect.Proxy;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;

/** Headless Bukkit stand-ins for the chat dispatch suites. */
final class ChatTestHarness {
    private ChatTestHarness() {
    }

    static Gloss gloss(File dataFolder, Collection<Player> online) throws ReflectiveOperationException {
        Object server = server(online);
        Gloss gloss = CharacterizationSupport.bareGloss((org.bukkit.Server) server);
        CharacterizationSupport.setField(gloss, "dataFolder", dataFolder);
        CharacterizationSupport.setField(gloss, "config", config());
        CharacterizationSupport.setField(gloss, "watchdog", new DataWatchdog(gloss));
        CharacterizationSupport.setField(gloss, "chat", new ChatService(gloss));
        CharacterizationSupport.setField(gloss, "text", new TextPipeline(gloss));
        return gloss;
    }

    static GlossConfig config() {
        return configWithChannels(true);
    }

    static GlossConfig configWithChannels(boolean enabled) {
        GlossConfigFile file = new GlossConfigFile();
        file.features.channels = enabled;
        file.normalize();
        return GlossConfig.from(file);
    }

    static World world(String name) {
        return (World) Proxy.newProxyInstance(ChatTestHarness.class.getClassLoader(),
            new Class<?>[]{World.class}, (proxy, method, args) -> switch (method.getName()) {
                case "getName" -> name;
                case "getUID" -> UUID.nameUUIDFromBytes(name.getBytes());
                case "hashCode" -> System.identityHashCode(proxy);
                case "equals" -> proxy == args[0];
                case "toString" -> "World[" + name + "]";
                default -> throw new UnsupportedOperationException("world." + method.getName());
            });
    }

    static FakePlayer player(String name, World world, double x) {
        return new FakePlayer(name, world, x);
    }

    private static Player named(Collection<Player> online, String name) {
        for (Player player : online) {
            if (player.getName().equalsIgnoreCase(name)) {
                return player;
            }
        }
        return null;
    }

    private static Player byId(Collection<Player> online, UUID id) {
        for (Player player : online) {
            if (player.getUniqueId().equals(id)) {
                return player;
            }
        }
        return null;
    }

    private static Object server(Collection<Player> online) {
        Object pluginManager = Proxy.newProxyInstance(ChatTestHarness.class.getClassLoader(),
            new Class<?>[]{org.bukkit.plugin.PluginManager.class}, (proxy, method, args) -> switch (method.getName()) {
                case "registerEvents", "callEvent" -> null;
                case "isPluginEnabled" -> false;
                case "hashCode" -> System.identityHashCode(proxy);
                case "equals" -> proxy == args[0];
                case "toString" -> "PluginManager[chat-test]";
                default -> throw new UnsupportedOperationException("pluginManager." + method.getName());
            });
        return Proxy.newProxyInstance(ChatTestHarness.class.getClassLoader(),
            new Class<?>[]{org.bukkit.Server.class}, (proxy, method, args) -> switch (method.getName()) {
                case "getOnlinePlayers" -> List.copyOf(online);
                case "getPlayerExact" -> named(online, String.valueOf(args[0]));
                case "getPlayer" -> args[0] instanceof UUID id ? byId(online, id) : named(online, String.valueOf(args[0]));
                case "getPluginManager" -> pluginManager;
                case "getPluginCommand" -> null;
                case "getLogger" -> CharacterizationSupport.mutedLogger();
                case "getName", "getVersion", "getBukkitVersion" -> "chat-test";
                case "isPrimaryThread" -> true;
                case "hashCode" -> System.identityHashCode(proxy);
                case "equals" -> proxy == args[0];
                case "toString" -> "Server[chat-test]";
                default -> throw new UnsupportedOperationException("server." + method.getName());
            });
    }

    /** A player proxy that answers only what the chat path asks and records what it was sent. */
    static final class FakePlayer {
        final Player proxy;
        final UUID id = UUID.randomUUID();
        final String name;
        final Set<String> permissions = new HashSet<>();
        final Set<UUID> hidden = new HashSet<>();
        final List<String> received = new ArrayList<>();
        private final World world;
        private final double x;

        private FakePlayer(String name, World world, double x) {
            this.name = name;
            this.world = world;
            this.x = x;
            this.proxy = (Player) Proxy.newProxyInstance(ChatTestHarness.class.getClassLoader(),
                new Class<?>[]{Player.class}, (target, method, args) -> switch (method.getName()) {
                    case "getUniqueId" -> id;
                    case "getName", "getDisplayName" -> name;
                    case "getWorld" -> world;
                    case "getLocation" -> new Location(world, x, 64.0D, 0.0D);
                    case "hasPermission" -> permissions.contains(String.valueOf(args[0]));
                    case "isPermissionSet" -> permissions.contains(String.valueOf(args[0]));
                    case "canSee" -> !hidden.contains(((Player) args[0]).getUniqueId());
                    case "isOnline" -> true;
                    case "getServer" -> null;
                    case "getLocale" -> "en_us";
                    case "sendMessage", "sendRichMessage" -> {
                        received.add(String.valueOf(args[0]));
                        yield null;
                    }
                    case "hashCode" -> System.identityHashCode(target);
                    case "equals" -> target == args[0];
                    case "toString" -> "Player[" + name + "]";
                    default -> throw new UnsupportedOperationException("player." + method.getName());
                });
        }

        FakePlayer allow(String permission) {
            permissions.add(permission);
            return this;
        }

        FakePlayer hide(FakePlayer other) {
            hidden.add(other.id);
            return this;
        }
    }
}
