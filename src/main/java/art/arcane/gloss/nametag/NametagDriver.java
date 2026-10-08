package art.arcane.gloss.nametag;

import art.arcane.gloss.condition.BoundedConditionErrorCallback;
import art.arcane.gloss.condition.RoleSnapshotPendingException;
import art.arcane.gloss.expr.ExprScope;
import art.arcane.gloss.util.common.TeamAllocator;
import org.bukkit.Location;
import org.bukkit.entity.Player;
import org.bukkit.World;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Set;
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
    private volatile Limits limits = new Limits(PER_VIEWER_RANGE, PER_VIEWER_SUBJECT_CAP);

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
        Map<UUID, TeamAllocator.TeamStyle> shared = null;
        if (!anyViewerDependent(runtimes)) {
            shared = new HashMap<>();
            for (Player subject : subjects) {
                shared.put(subject.getUniqueId(), prepare(viewers.getFirst(), subject, runtimes, scopes, errors));
            }
        }
        Map<UUID, String> names = new HashMap<>(subjects.size());
        for (Player subject : subjects) {
            names.put(subject.getUniqueId(), subject.getName());
        }
        ViewerPass pass = new ViewerPass(subjects, runtimes, shared, names, false);
        for (Player viewer : viewers) {
            applyViewer(viewer, pass, scopes, errors);
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
            TeamAllocator.TeamHandle removed = mine.remove(viewerId);
            if (removed != null) {
                teams.release(removed);
            }
        }
        teams.forget(viewerId);
    }

    public void clear() {
        handles.clear();
    }

    public void configure(double range, int subjects) {
        if (!Double.isFinite(range) || range <= 0.0D || subjects < 1) {
            throw new IllegalArgumentException("Nametag range and subject count must be positive");
        }
        limits = new Limits(range, subjects);
    }

    public TeamAllocator.TeamStyle prepare(Player viewer, Player subject, List<NametagRuntime> runtimes,
                                           ScopeFactory scopes, BoundedConditionErrorCallback errors) {
        ExprScope scope = scopes.scope(viewer, subject);
        NametagRuntime runtime = NametagRuntime.pick(runtimes, scope, errors).orElse(null);
        if (runtime == null) {
            return null;
        }
        NametagRuntime.Profile profile = runtime.profile(scope, errors);
        return profile.presentation().style(render(viewer, profile.presentation().prefix(), scope),
            render(viewer, profile.presentation().suffix(), scope));
    }

    public void applyViewer(Player viewer, ViewerPass pass, ScopeFactory scopes, BoundedConditionErrorCallback errors) {
        List<NametagRuntime> runtimes = pass.runtimes();
        Map<UUID, TeamAllocator.TeamStyle> shared = pass.shared();
        Map<UUID, TeamAllocator.TeamHandle> mine = handles.computeIfAbsent(viewer.getUniqueId(),
            key -> new ConcurrentHashMap<>());
        List<Player> candidates = pass.selected() ? pass.subjects() : candidates(viewer, pass.subjects(), runtimes);
        Set<UUID> seen = new HashSet<>(candidates.size());
        for (Player subject : candidates) {
            UUID subjectId = subject.getUniqueId();
            if (!pass.names().containsKey(subjectId) || shared != null && !shared.containsKey(subjectId)) {
                seen.add(subjectId);
                continue;
            }
            TeamAllocator.TeamStyle style;
            try {
                style = shared == null ? prepare(viewer, subject, runtimes, scopes, errors)
                    : shared.get(subject.getUniqueId());
            } catch (RoleSnapshotPendingException pending) {
                seen.add(subject.getUniqueId());
                continue;
            }
            if (style == null) {
                continue;
            }
            TeamAllocator.TeamHandle existing = mine.get(subject.getUniqueId());
            if (existing == null) {
                mine.put(subject.getUniqueId(), teams.claim(viewer, PURPOSE, pass.names().get(subject.getUniqueId()), style));
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
        Limits configured = limits;
        List<Nearby> nearby = new ArrayList<>(Math.min(subjects.size(), configured.subjects()));
        double range = configured.range() * configured.range();
        for (Player subject : subjects) {
            double distance = proximity.distanceSquared(viewer, subject);
            if (distance >= 0.0D && distance <= range) {
                nearby.add(new Nearby(subject, distance));
            }
        }
        nearby.sort(Comparator.comparingDouble(Nearby::distance));
        int count = Math.min(nearby.size(), configured.subjects());
        List<Player> selected = new ArrayList<>(count);
        for (int index = 0; index < count; index++) {
            selected.add(nearby.get(index).player());
        }
        return selected;
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

    public record ViewerPass(List<Player> subjects, List<NametagRuntime> runtimes,
                             Map<UUID, TeamAllocator.TeamStyle> shared, Map<UUID, String> names, boolean selected) { }

    private record Limits(double range, int subjects) {
    }

    private record Nearby(Player player, double distance) {
    }
}
