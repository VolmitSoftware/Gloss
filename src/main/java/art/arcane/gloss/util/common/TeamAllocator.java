package art.arcane.gloss.util.common;

import org.bukkit.entity.Player;

import java.util.Objects;
import java.util.UUID;

/**
 * One owner for per-viewer scoreboard teams. Nametag prefixes, nameplate suppression and glow
 * colors all want something from the same entry, and a client keeps an entry in exactly one team,
 * so a claim is a layer on the one team for that (viewer, entry) rather than a team of its own.
 * The allocator composes the layers, hands out names, sends the packets, and tears everything down
 * on quit. {@link PacketTeamAllocator} is the implementation {@code Gloss} installs.
 */
public interface TeamAllocator {
    enum NameTagVisibility {
        ALWAYS,
        NEVER,
        HIDE_FOR_OTHER_TEAMS,
        HIDE_FOR_OWN_TEAM
    }

    enum CollisionRule {
        ALWAYS,
        NEVER,
        PUSH_OTHER_TEAMS,
        PUSH_OWN_TEAM
    }

    /**
     * @param prefix legacy-formatted prefix text, may be empty
     * @param suffix legacy-formatted suffix text, may be empty
     * @param color  a named text color ({@code white}, {@code red}, ...) applied to the entry name and glow
     */
    record TeamStyle(String prefix, String suffix, String color, NameTagVisibility nameTagVisibility,
                     CollisionRule collisionRule) {
        public static final TeamStyle PLAIN = new TeamStyle("", "", "white", NameTagVisibility.ALWAYS, CollisionRule.ALWAYS);

        public TeamStyle {
            prefix = prefix == null ? "" : prefix;
            suffix = suffix == null ? "" : suffix;
            color = color == null || color.isBlank() ? "white" : color;
            nameTagVisibility = Objects.requireNonNullElse(nameTagVisibility, NameTagVisibility.ALWAYS);
            collisionRule = Objects.requireNonNullElse(collisionRule, CollisionRule.ALWAYS);
        }
    }

    /**
     * @param entry the team member: a player name, or an entity UUID string for non-players
     */
    record TeamHandle(UUID viewerId, String purpose, String entry, String teamName) {
        public TeamHandle {
            Objects.requireNonNull(viewerId, "viewerId");
            Objects.requireNonNull(purpose, "purpose");
            Objects.requireNonNull(entry, "entry");
            Objects.requireNonNull(teamName, "teamName");
        }
    }

    TeamHandle claim(Player viewer, String purpose, String entry, TeamStyle style);

    void update(TeamHandle handle, TeamStyle style);

    void release(TeamHandle handle);

    void releaseAll(Player viewer, String purpose);

    void forget(UUID viewerId);
}
