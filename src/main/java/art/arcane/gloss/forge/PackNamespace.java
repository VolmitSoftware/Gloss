package art.arcane.gloss.forge;

import art.arcane.gloss.expr.ExprVariableContext;
import art.arcane.gloss.expr.ExprVariableNamespace;
import org.bukkit.entity.Player;

import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * What each viewer has done with the current pack. Statuses are keyed to the sha1 they were
 * reported for, so a rebuild drops every viewer back to "not loaded" until the client confirms the
 * new download: rendering glyphs a client has not fetched is what produces boxes on screen.
 */
public final class PackNamespace implements ExprVariableNamespace {
    public static final String PREFIX = "pack";
    public static final String LOADED = "successfully_loaded";
    public static final String NONE = "none";

    private static volatile PackNamespace active;

    private final Map<UUID, Status> statuses = new ConcurrentHashMap<>();
    private volatile String sha1 = "";

    private record Status(String value, String sha1) {
    }

    /** True when this viewer has the current pack loaded; false whenever the lane is off. */
    public static boolean loaded(Player viewer) {
        PackNamespace namespace = active;
        return namespace != null && viewer != null && namespace.loaded(viewer.getUniqueId());
    }

    public static PackNamespace active() {
        return active;
    }

    public void install() {
        active = this;
    }

    public void uninstall() {
        if (active == this) {
            active = null;
        }
        statuses.clear();
        sha1 = "";
    }

    @Override
    public String prefix() {
        return PREFIX;
    }

    @Override
    public Object resolve(String suffix, ExprVariableContext context) {
        Player viewer = context == null ? null : context.viewer();
        UUID playerId = viewer == null ? null : viewer.getUniqueId();
        if (suffix == null) {
            return null;
        }
        return switch (suffix) {
            case "loaded" -> loaded(playerId);
            case "status" -> status(playerId);
            case "sha1" -> sha1;
            default -> null;
        };
    }

    /** Records the sha1 the delivery is now advertising; every earlier status stops counting. */
    public void publish(String sha1Hex) {
        sha1 = sha1Hex == null ? "" : sha1Hex;
    }

    public String sha1() {
        return sha1;
    }

    public void record(UUID playerId, String status) {
        if (playerId == null || status == null) {
            return;
        }
        statuses.put(playerId, new Status(status.toLowerCase(Locale.ROOT), sha1));
    }

    public void forget(UUID playerId) {
        if (playerId != null) {
            statuses.remove(playerId);
        }
    }

    public boolean loaded(UUID playerId) {
        return LOADED.equals(status(playerId));
    }

    public String status(UUID playerId) {
        if (playerId == null) {
            return NONE;
        }
        Status status = statuses.get(playerId);
        return status == null || !Objects.equals(status.sha1(), sha1) ? NONE : status.value();
    }

    public int loadedCount() {
        int count = 0;
        for (UUID playerId : statuses.keySet()) {
            if (loaded(playerId)) {
                count++;
            }
        }
        return count;
    }
}
