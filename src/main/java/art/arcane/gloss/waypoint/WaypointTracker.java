package art.arcane.gloss.waypoint;

import com.github.retrooper.packetevents.wrapper.play.server.WrapperPlayServerWaypoint;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.function.Predicate;
import java.util.function.Supplier;

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
    private final Supplier<Thresholds> thresholds;

    public WaypointTracker() {
        this(() -> new Thresholds(POSITION_EPSILON, AZIMUTH_EPSILON));
    }

    public WaypointTracker(Supplier<Thresholds> thresholds) {
        this.thresholds = Objects.requireNonNull(thresholds);
    }

    public record Thresholds(double position, double azimuth) {
        public Thresholds {
            if (!Double.isFinite(position) || !Double.isFinite(azimuth) || position < 0 || azimuth < 0) {
                throw new IllegalArgumentException("Waypoint thresholds must be finite and nonnegative");
            }
        }
    }

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
        return reconcile(viewerId, desired, changes -> true);
    }

    public List<Change> reconcile(UUID viewerId, List<WaypointTarget> desired,
                                  Predicate<List<Change>> delivery) {
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
                if (previous == null || requiresReplacement(previous, entry.getValue())) {
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
            if (changes.isEmpty() || delivery.test(List.copyOf(changes))) {
                current.clear();
                current.putAll(next);
            }
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

    private boolean requiresReplacement(WaypointTarget previous, WaypointTarget next) {
        return previous.color() != next.color() || !previous.styleKey().equals(next.styleKey())
            || previous.azimuth() != next.azimuth();
    }

    private boolean changed(WaypointTarget previous, WaypointTarget next) {
        if (next.azimuth()) {
            double delta = next.azimuthRadians() - previous.azimuthRadians();
            return Math.abs(Math.atan2(Math.sin(delta), Math.cos(delta))) > thresholds.get().azimuth();
        }
        return next.distanceTo(previous.x(), previous.y(), previous.z()) > thresholds.get().position();
    }
}
