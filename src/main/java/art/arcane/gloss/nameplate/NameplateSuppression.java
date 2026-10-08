package art.arcane.gloss.nameplate;

import art.arcane.gloss.util.common.TeamAllocator;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Player;

import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;

/**
 * Hides the vanilla name tag under a Gloss pane by claiming a per-viewer team whose name-tag
 * visibility is {@code NEVER}. Bedrock clients ignore that flag and would lose nothing but gain a
 * missing name, so they keep the vanilla tag and never get a claim.
 */
public final class NameplateSuppression {
    public static final String PURPOSE = "nameplate";

    private final TeamAllocator teams;
    private final String purpose;
    private final ConcurrentMap<UUID, ConcurrentMap<UUID, TeamAllocator.TeamHandle>> claimed =
        new ConcurrentHashMap<>();

    public NameplateSuppression(TeamAllocator teams, String purpose) {
        this.teams = Objects.requireNonNull(teams, "teams");
        this.purpose = Objects.requireNonNull(purpose, "purpose");
    }

    public void admit(Player viewer, Entity target, boolean bedrockViewer) {
        admit(viewer, target.getUniqueId(), target instanceof Player player ? player.getName() : target.getUniqueId().toString(), bedrockViewer);
    }

    public void admit(Player viewer, UUID target, String teamEntry, boolean bedrockViewer) {
        if (bedrockViewer) {
            return;
        }
        claimed.compute(viewer.getUniqueId(), (viewerId, existing) -> {
            ConcurrentMap<UUID, TeamAllocator.TeamHandle> handles = existing == null
                ? new ConcurrentHashMap<>() : existing;
            handles.computeIfAbsent(target, ignored -> teams.claim(viewer, purpose, teamEntry,
                new TeamAllocator.TeamStyle("", "", "white",
                    TeamAllocator.NameTagVisibility.NEVER, TeamAllocator.CollisionRule.ALWAYS)));
            return handles;
        });
    }

    public void retire(UUID viewerId, UUID targetId) {
        claimed.computeIfPresent(viewerId, (ignored, handles) -> {
            TeamAllocator.TeamHandle handle = handles.remove(targetId);
            if (handle != null) {
                teams.release(handle);
            }
            return handles.isEmpty() ? null : handles;
        });
    }

    /** Releases every viewer's claim on one subject, for the subject leaving the server. */
    public void retireSubject(UUID subjectId) {
        for (UUID viewerId : List.copyOf(claimed.keySet())) {
            retire(viewerId, subjectId);
        }
    }

    public void forget(Player viewer) {
        claimed.computeIfPresent(viewer.getUniqueId(), (ignored, handles) -> {
            teams.releaseAll(viewer, purpose);
            return null;
        });
    }

    public int claimed(UUID viewerId) {
        Map<UUID, TeamAllocator.TeamHandle> handles = claimed.get(viewerId);
        return handles == null ? 0 : handles.size();
    }

    public void clear() {
        for (UUID viewerId : List.copyOf(claimed.keySet())) {
            claimed.computeIfPresent(viewerId, (ignored, handles) -> {
                for (TeamAllocator.TeamHandle handle : handles.values()) {
                    teams.release(handle);
                }
                return null;
            });
        }
    }
}
