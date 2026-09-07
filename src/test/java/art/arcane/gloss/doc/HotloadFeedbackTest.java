package art.arcane.gloss.doc;

import art.arcane.gloss.Gloss;
import art.arcane.gloss.menu.CharacterizationSupport;
import org.bukkit.Server;
import org.bukkit.scheduler.BukkitScheduler;
import org.bukkit.entity.Player;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Hotload feedback is admin-only, so the admin test has to be the filter and not the body of a
 * scheduled task: at a full server the old shape queued one entity task per online player on every
 * watchdog pass that saw a change.
 */
class HotloadFeedbackTest {
    private Object previousServer;
    private Gloss previousInstance;
    private Gloss gloss;
    private final List<Player> online = new ArrayList<>();

    @BeforeEach
    void installHeadlessServer() throws ReflectiveOperationException {
        Server server = server();
        previousServer = CharacterizationSupport.installServer(server);
        gloss = CharacterizationSupport.bareGloss(server);
        CharacterizationSupport.setField(gloss, "isEnabled", true);
        previousInstance = CharacterizationSupport.installGloss(gloss);
    }

    @AfterEach
    void restoreStatics() throws ReflectiveOperationException {
        CharacterizationSupport.restoreGloss(previousInstance);
        CharacterizationSupport.restoreServer((Server) previousServer);
    }

    @Test
    void everyOnlinePlayerIsPermissionTestedOnTheCallingThread() {
        RecordingPlayer admin = player(true);
        RecordingPlayer bystander = player(false);
        online.add(admin.player());
        online.add(bystander.player());

        new HotloadFeedback(gloss).deliver(changes("holograms", 3));

        assertEquals(1, admin.count("hasPermission"),
            "the admin test must run once per online player");
        assertEquals(1, bystander.count("hasPermission"),
            "the admin test must run once per online player");
        assertEquals("hasPermission", admin.invoked().get(0),
            "the admin test must be the filter, not the first line of a scheduled task");
        assertEquals("hasPermission", bystander.invoked().get(0),
            "the admin test must be the filter, not the first line of a scheduled task");
    }

    @Test
    void aPlayerWithoutTheAdminNodeIsNeverScheduled() {
        RecordingPlayer bystander = player(false);
        online.add(bystander.player());

        new HotloadFeedback(gloss).deliver(changes("holograms", 3));

        assertEquals(List.of("hasPermission"), bystander.invoked(),
            "a non-admin must cost exactly one permission lookup and no scheduler hop");
    }

    @Test
    void anAdminStillReachesTheDeliveryBody() {
        RecordingPlayer admin = player(true);
        online.add(admin.player());

        new HotloadFeedback(gloss).deliver(changes("holograms", 3));

        assertTrue(admin.invoked().size() > 1,
            "an admin must still be scheduled: " + admin.invoked());
    }

    @Test
    void anEmptyBatchTouchesNobody() {
        RecordingPlayer admin = player(true);
        online.add(admin.player());

        new HotloadFeedback(gloss).deliver(new HotloadBatch().drain());

        assertEquals(List.of(), admin.invoked());
    }

    private static HotloadBatch.Snapshot changes(String kind, int count) {
        HotloadBatch batch = new HotloadBatch();
        batch.record(kind, count);
        return batch.drain();
    }

    private RecordingPlayer player(boolean admin) {
        return new RecordingPlayer(admin);
    }

    private Server server() {
        return (Server) CharacterizationSupport.proxy(new Class<?>[]{Server.class},
            (proxy, method, args) -> switch (method.getName()) {
                case "getOnlinePlayers" -> List.copyOf(online);
                case "getLogger" -> CharacterizationSupport.mutedLogger();
                case "getName", "getVersion", "getBukkitVersion" -> "characterization";
                case "isPrimaryThread" -> true;
                case "getScheduler" -> scheduler();
                case "hashCode" -> System.identityHashCode(proxy);
                case "equals" -> proxy == args[0];
                case "toString" -> "Server[hotload-feedback]";
                default -> null;
            });
    }

    private static Object scheduler() {
        return CharacterizationSupport.proxy(new Class<?>[]{BukkitScheduler.class},
            (proxy, method, args) -> {
                if (method.getName().startsWith("run") && args != null) {
                    for (Object argument : args) {
                        if (argument instanceof Runnable task) {
                            task.run();
                        }
                    }
                }
                return defaultValue(method.getReturnType());
            });
    }

    private static Object defaultValue(Class<?> type) {
        if (!type.isPrimitive() || type == void.class) {
            return null;
        }
        if (type == boolean.class) {
            return false;
        }
        if (type == long.class) {
            return 0L;
        }
        if (type == double.class) {
            return 0D;
        }
        if (type == float.class) {
            return 0F;
        }
        return 0;
    }

    /** A player proxy that records the order of every engine call made against it. */
    private static final class RecordingPlayer {
        private final List<String> invoked = new ArrayList<>();
        private final Map<String, Integer> counts = new HashMap<>();
        private final UUID id = UUID.randomUUID();
        private final Player player;

        private RecordingPlayer(boolean admin) {
            this.player = (Player) CharacterizationSupport.proxy(new Class<?>[]{Player.class},
                (proxy, method, args) -> {
                    switch (method.getName()) {
                        case "hashCode" -> {
                            return System.identityHashCode(proxy);
                        }
                        case "equals" -> {
                            return proxy == args[0];
                        }
                        case "toString" -> {
                            return "Player[recording]";
                        }
                        default -> {
                        }
                    }
                    invoked.add(method.getName());
                    counts.merge(method.getName(), 1, Integer::sum);
                    return switch (method.getName()) {
                        case "hasPermission" -> admin;
                        case "isOnline" -> true;
                        case "getUniqueId" -> id;
                        case "getName" -> admin ? "admin" : "bystander";
                        default -> defaultValue(method.getReturnType());
                    };
                });
        }

        private Player player() {
            return player;
        }

        private List<String> invoked() {
            return List.copyOf(invoked);
        }

        private int count(String method) {
            return counts.getOrDefault(method, 0);
        }
    }
}
