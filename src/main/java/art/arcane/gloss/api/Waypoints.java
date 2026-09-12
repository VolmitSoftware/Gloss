package art.arcane.gloss.api;

import org.bukkit.entity.Player;
import org.bukkit.plugin.Plugin;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;

/**
 * Locator-bar waypoints other plugins ask Gloss to show. A spec stays tracked until its owner
 * untracks it or the viewer leaves; {@code WaypointService} reads this registry on every pass and
 * turns it into packets, so a caller never touches the protocol itself.
 */
public final class Waypoints {
    private record Owned(Plugin owner, WaypointSpec spec) {
    }

    private static final ConcurrentMap<UUID, Map<String, Owned>> TRACKED = new ConcurrentHashMap<>();

    private Waypoints() {
    }

    public static void track(Plugin owner, Player viewer, WaypointSpec spec) {
        Objects.requireNonNull(owner, "owner");
        Objects.requireNonNull(viewer, "viewer");
        Objects.requireNonNull(spec, "spec");
        Map<String, Owned> owned = TRACKED.computeIfAbsent(viewer.getUniqueId(),
            ignored -> new LinkedHashMap<>());
        synchronized (owned) {
            owned.put(spec.id(), new Owned(owner, spec));
        }
    }

    public static void untrack(Plugin owner, Player viewer, String id) {
        Objects.requireNonNull(owner, "owner");
        Objects.requireNonNull(viewer, "viewer");
        Map<String, Owned> owned = TRACKED.get(viewer.getUniqueId());
        if (owned == null) {
            return;
        }
        synchronized (owned) {
            Owned current = owned.get(id);
            if (current != null && current.owner() == owner) {
                owned.remove(id);
            }
        }
    }

    public static List<WaypointSpec> tracked(UUID viewerId) {
        Map<String, Owned> owned = TRACKED.get(viewerId);
        if (owned == null) {
            return List.of();
        }
        synchronized (owned) {
            List<WaypointSpec> specs = new ArrayList<>(owned.size());
            for (Owned entry : owned.values()) {
                specs.add(entry.spec());
            }
            return List.copyOf(specs);
        }
    }

    public static void forget(UUID viewerId) {
        TRACKED.remove(viewerId);
    }

    public static void unregister(Plugin owner) {
        Objects.requireNonNull(owner, "owner");
        for (Map<String, Owned> owned : TRACKED.values()) {
            synchronized (owned) {
                owned.values().removeIf(entry -> entry.owner() == owner);
            }
        }
    }

    public static void clear() {
        TRACKED.clear();
    }
}
