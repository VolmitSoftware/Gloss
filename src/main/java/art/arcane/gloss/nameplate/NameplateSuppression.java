package art.arcane.gloss.nameplate;

import art.arcane.gloss.util.common.TeamAllocator;
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
    private final ConcurrentMap<UUID, ConcurrentMap<UUID, TeamAllocator.TeamHandle>> claimed =
        new ConcurrentHashMap<>();

    public NameplateSuppression(TeamAllocator teams) {
        this.teams = Objects.requireNonNull(teams, "teams");
    }

    public void admit(Player viewer, Player target, boolean bedrockViewer) {
        if (bedrockViewer) {
            return;
        }
        ConcurrentMap<UUID, TeamAllocator.TeamHandle> handles = claimed.computeIfAbsent(
            viewer.getUniqueId(), ignored -> new ConcurrentHashMap<>());
        handles.computeIfAbsent(target.getUniqueId(), ignored -> teams.claim(viewer, PURPOSE,
            target.getName(), new TeamAllocator.TeamStyle("", "", "white",
                TeamAllocator.NameTagVisibility.NEVER, TeamAllocator.CollisionRule.ALWAYS)));
    }

    public void retire(UUID viewerId, UUID targetId) {
        ConcurrentMap<UUID, TeamAllocator.TeamHandle> handles = claimed.get(viewerId);
        if (handles == null) {
            return;
        }
        TeamAllocator.TeamHandle handle = handles.remove(targetId);
        if (handle != null) {
            teams.release(handle);
        }
        if (handles.isEmpty()) {
            claimed.remove(viewerId, handles);
        }
    }

    /** Releases every viewer's claim on one subject, for the subject leaving the server. */
    public void retireSubject(UUID subjectId) {
        for (UUID viewerId : List.copyOf(claimed.keySet())) {
            retire(viewerId, subjectId);
        }
    }

    public void forget(Player viewer) {
        ConcurrentMap<UUID, TeamAllocator.TeamHandle> handles = claimed.remove(viewer.getUniqueId());
        if (handles != null && !handles.isEmpty()) {
            teams.releaseAll(viewer, PURPOSE);
        }
    }

    public int claimed(UUID viewerId) {
        Map<UUID, TeamAllocator.TeamHandle> handles = claimed.get(viewerId);
        return handles == null ? 0 : handles.size();
    }

    public void clear() {
        for (UUID viewerId : List.copyOf(claimed.keySet())) {
            ConcurrentMap<UUID, TeamAllocator.TeamHandle> handles = claimed.remove(viewerId);
            if (handles == null) {
                continue;
            }
            for (TeamAllocator.TeamHandle handle : handles.values()) {
                teams.release(handle);
            }
        }
    }
}
