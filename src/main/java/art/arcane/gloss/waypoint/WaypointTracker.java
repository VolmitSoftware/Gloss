package art.arcane.gloss.waypoint;

import com.github.retrooper.packetevents.wrapper.play.server.WrapperPlayServerWaypoint;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;

/**
 * What each viewer is already tracking, and the minimum set of operations that turns that into the
 * desired set. An unchanged waypoint emits nothing: the locator bar keeps drawing the last
 * position it was given, so re-sending it every pass would be pure bandwidth.
 */
public final class WaypointTracker {
    /** Blocks the anchor may drift before the client is told; the bar cannot resolve finer than this. */
    public static final double POSITION_EPSILON = 1.0D;
    /** Radians the bearing may drift in azimuth mode before the client is told, about one degree. */
    public static final double AZIMUTH_EPSILON = 0.017D;

    public record Change(WrapperPlayServerWaypoint.Operation operation, WaypointTarget target) {
        public String id() {
            return target.id();
        }
    }

    private final ConcurrentMap<UUID, Map<String, WaypointTarget>> tracked = new ConcurrentHashMap<>();

    public static List<WaypointTarget> capByDistance(List<WaypointTarget> targets, double x, double y,
                                                     double z, int maxPerViewer) {
        if (maxPerViewer <= 0) {
            return List.of();
        }
        if (targets.size() <= maxPerViewer) {
            return List.copyOf(targets);
        }
        List<WaypointTarget> sorted = new ArrayList<>(targets);
        sorted.sort(Comparator.comparingDouble((WaypointTarget target) -> target.distanceTo(x, y, z))
            .thenComparing(WaypointTarget::id));
        return List.copyOf(sorted.subList(0, maxPerViewer));
    }

    public List<Change> reconcile(UUID viewerId, List<WaypointTarget> desired) {
        Map<String, WaypointTarget> current = tracked.computeIfAbsent(viewerId,
            ignored -> new LinkedHashMap<>());
        List<Change> changes = new ArrayList<>();
        synchronized (current) {
            Map<String, WaypointTarget> next = new LinkedHashMap<>(desired.size());
            for (WaypointTarget target : desired) {
                next.put(target.id(), target);
            }
            for (Map.Entry<String, WaypointTarget> entry : next.entrySet()) {
                WaypointTarget previous = current.get(entry.getKey());
                if (previous == null) {
                    changes.add(new Change(WrapperPlayServerWaypoint.Operation.TRACK, entry.getValue()));
                } else if (changed(previous, entry.getValue())) {
                    changes.add(new Change(WrapperPlayServerWaypoint.Operation.UPDATE, entry.getValue()));
                } else {
                    next.put(entry.getKey(), previous);
                }
            }
            for (WaypointTarget previous : current.values()) {
                if (!next.containsKey(previous.id())) {
                    changes.add(new Change(WrapperPlayServerWaypoint.Operation.UNTRACK, previous));
                }
            }
            current.clear();
            current.putAll(next);
        }
        return List.copyOf(changes);
    }

    public List<Change> forget(UUID viewerId) {
        Map<String, WaypointTarget> current = tracked.remove(viewerId);
        if (current == null || current.isEmpty()) {
            return List.of();
        }
        List<Change> changes = new ArrayList<>(current.size());
        synchronized (current) {
            for (WaypointTarget target : current.values()) {
                changes.add(new Change(WrapperPlayServerWaypoint.Operation.UNTRACK, target));
            }
            current.clear();
        }
        return List.copyOf(changes);
    }

    public void clear() {
        tracked.clear();
    }

    private static boolean changed(WaypointTarget previous, WaypointTarget next) {
        if (previous.color() != next.color() || previous.style() != next.style()
            || previous.azimuth() != next.azimuth()) {
            return true;
        }
        if (next.azimuth()) {
            return Math.abs(next.azimuthRadians() - previous.azimuthRadians()) > AZIMUTH_EPSILON;
        }
        return next.distanceTo(previous.x(), previous.y(), previous.z()) > POSITION_EPSILON;
    }
}
