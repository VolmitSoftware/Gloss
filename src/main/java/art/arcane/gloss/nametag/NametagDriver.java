package art.arcane.gloss.nametag;

import art.arcane.gloss.condition.BoundedConditionErrorCallback;
import art.arcane.gloss.expr.ExprScope;
import art.arcane.gloss.util.common.TeamAllocator;
import org.bukkit.Location;
import org.bukkit.entity.Player;
import org.bukkit.World;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Applies nametag documents through the shared team allocator. A document that reads only
 * {@code subject.*} resolves once per subject and is claimed for every viewer; one that reads
 * {@code viewer.*} resolves per pair, for the nearest subjects only.
 */
public final class NametagDriver {
    public static final String PURPOSE = "nametag";
    public static final double PER_VIEWER_RANGE = 64.0D;
    public static final int PER_VIEWER_SUBJECT_CAP = 32;

    private final TeamAllocator teams;
    private final NametagRenderer renderer;
    private final Proximity proximity;
    private final Map<UUID, Map<UUID, TeamAllocator.TeamHandle>> handles = new ConcurrentHashMap<>();

    public NametagDriver(TeamAllocator teams, NametagRenderer renderer) {
        this(teams, renderer, NametagDriver::distanceSquared);
    }

    public NametagDriver(TeamAllocator teams, NametagRenderer renderer, Proximity proximity) {
        this.teams = teams;
        this.renderer = renderer;
        this.proximity = proximity;
    }

    public void apply(List<Player> viewers, List<Player> subjects, List<NametagRuntime> runtimes,
                      ScopeFactory scopes, BoundedConditionErrorCallback errors) {
        if (runtimes.isEmpty() || viewers.isEmpty()) {
            return;
        }
        for (Player viewer : viewers) {
            applyViewer(viewer, subjects, runtimes, scopes, errors);
        }
    }

    public void release(Player viewer, Player subject) {
        Map<UUID, TeamAllocator.TeamHandle> mine = handles.get(viewer.getUniqueId());
        if (mine == null) {
            return;
        }
        TeamAllocator.TeamHandle handle = mine.remove(subject.getUniqueId());
        if (handle != null) {
            teams.release(handle);
        }
    }

    public void releaseAll(Player viewer) {
        handles.remove(viewer.getUniqueId());
        teams.releaseAll(viewer, PURPOSE);
    }

    public void forget(UUID viewerId) {
        handles.remove(viewerId);
        for (Map<UUID, TeamAllocator.TeamHandle> mine : handles.values()) {
            mine.remove(viewerId);
        }
        teams.forget(viewerId);
    }

    public void clear() {
        handles.clear();
    }

    private void applyViewer(Player viewer, List<Player> subjects, List<NametagRuntime> runtimes,
                             ScopeFactory scopes, BoundedConditionErrorCallback errors) {
        Map<UUID, TeamAllocator.TeamHandle> mine = handles.computeIfAbsent(viewer.getUniqueId(),
            key -> new ConcurrentHashMap<>());
        List<Player> candidates = candidates(viewer, subjects, runtimes);
        List<UUID> seen = new ArrayList<>(candidates.size());
        for (Player subject : candidates) {
            ExprScope scope = scopes.scope(viewer, subject);
            NametagRuntime runtime = NametagRuntime.pick(runtimes, scope, errors).orElse(null);
            if (runtime == null) {
                continue;
            }
            NametagRuntime.Profile profile = runtime.profile(scope, errors);
            TeamAllocator.TeamStyle style = profile.presentation().style(
                render(viewer, profile.presentation().prefix(), scope),
                render(viewer, profile.presentation().suffix(), scope));
            TeamAllocator.TeamHandle existing = mine.get(subject.getUniqueId());
            if (existing == null) {
                mine.put(subject.getUniqueId(), teams.claim(viewer, PURPOSE, subject.getName(), style));
            } else {
                teams.update(existing, style);
            }
            seen.add(subject.getUniqueId());
        }
        for (UUID tracked : List.copyOf(mine.keySet())) {
            if (!seen.contains(tracked)) {
                TeamAllocator.TeamHandle handle = mine.remove(tracked);
                if (handle != null) {
                    teams.release(handle);
                }
            }
        }
    }

    /**
     * Broadcast documents tag every subject; a per-viewer document only reaches the nearest
     * subjects, so a thousand viewers never cost a thousand squared team packets.
     */
    private List<Player> candidates(Player viewer, List<Player> subjects, List<NametagRuntime> runtimes) {
        if (!anyViewerDependent(runtimes)) {
            return subjects;
        }
        List<Player> nearby = new ArrayList<>(Math.min(subjects.size(), PER_VIEWER_SUBJECT_CAP));
        double range = PER_VIEWER_RANGE * PER_VIEWER_RANGE;
        for (Player subject : subjects) {
            double distance = proximity.distanceSquared(viewer, subject);
            if (distance >= 0.0D && distance <= range) {
                nearby.add(subject);
            }
        }
        nearby.sort(Comparator.comparingDouble(subject -> proximity.distanceSquared(viewer, subject)));
        return nearby.size() <= PER_VIEWER_SUBJECT_CAP ? nearby : nearby.subList(0, PER_VIEWER_SUBJECT_CAP);
    }

    private static boolean anyViewerDependent(List<NametagRuntime> runtimes) {
        for (NametagRuntime runtime : runtimes) {
            if (runtime.viewerDependent()) {
                return true;
            }
        }
        return false;
    }

    private String render(Player viewer, String raw, ExprScope scope) {
        if (raw == null || raw.isEmpty()) {
            return "";
        }
        String rendered = renderer.render(viewer, raw, scope);
        return rendered == null ? "" : rendered;
    }

    /** Negative when the two are not comparable, which excludes the subject from a per-viewer pass. */
    private static double distanceSquared(Player viewer, Player subject) {
        Location left = viewer.getLocation();
        Location right = subject.getLocation();
        World world = left.getWorld();
        if (world == null || !world.equals(right.getWorld())) {
            return -1.0D;
        }
        return left.distanceSquared(right);
    }

    /**
     * Prefix and suffix text, rendered against the same viewer and pair scope the selection was
     * resolved against, so {@code subject.*}, {@code viewer.*} and placeholders all resolve.
     */
    @FunctionalInterface
    public interface NametagRenderer {
        String render(Player viewer, String raw, ExprScope scope);
    }

    /** The viewer/subject pair a document is resolved against. */
    @FunctionalInterface
    public interface ScopeFactory {
        ExprScope scope(Player viewer, Player subject);
    }

    @FunctionalInterface
    public interface Proximity {
        double distanceSquared(Player viewer, Player subject);
    }
}
