package art.arcane.gloss.util.common;

import com.github.retrooper.packetevents.PacketEvents;
import com.github.retrooper.packetevents.wrapper.play.server.WrapperPlayServerTeams;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import org.bukkit.entity.Player;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.TreeMap;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;

/**
 * The one owner of per-viewer scoreboard teams. A client keeps a scoreboard entry in exactly one
 * team: adding it to a second team silently drops it out of the first. So there is one team per
 * (viewer, entry) here, and every purpose that wants something from that entry contributes a layer
 * to it. The team the client holds is the composition of all the layers, which is why a glowing
 * player can still have their vanilla tag hidden and their nametag prefix drawn at the same time.
 *
 * <p>Layers compose strongest first: the glow colour beats a nametag colour, a nametag prefix and
 * suffix are the only ones anybody sets, and the name-tag visibility and collision rule take the
 * most restrictive value any layer asked for, so nameplate suppression always wins over a glow
 * that asks for the vanilla tag. A layer that asks for the default white is treated as asking for
 * no colour, since white is the protocol default and is what the two colourless purposes pass.
 */
public final class PacketTeamAllocator implements TeamAllocator {
    private static final String PREFIX = "gls_t_";
    private static final int MAX_TEAM_NAME_LENGTH = 16;
    private static final String DEFAULT_COLOR = "white";

    /** Owned by {@code GlowService}, {@code NameplateSuppression} and {@code NametagDriver}. */
    private static final Map<String, Integer> LAYER_STRENGTH = Map.of(
        "glow", 3,
        "nameplate", 2,
        "nametag", 1);

    private final PacketSink sink;
    private final AtomicLong sequence = new AtomicLong();
    private final Map<UUID, ViewerClaims> claims = new ConcurrentHashMap<>();

    public PacketTeamAllocator() {
        this(PacketTeamAllocator::sendPacket);
    }

    PacketTeamAllocator(PacketSink sink) {
        this.sink = sink;
    }

    @Override
    public TeamHandle claim(Player viewer, String purpose, String entry, TeamStyle style) {
        ViewerClaims mine = claims.computeIfAbsent(viewer.getUniqueId(), key -> new ViewerClaims(viewer));
        Team team = mine.teams().computeIfAbsent(entry, key -> new Team(teamName()));
        synchronized (team) {
            team.layers.put(purpose, style);
            publish(mine.viewer(), team, entry);
        }
        return new TeamHandle(viewer.getUniqueId(), purpose, entry, team.name);
    }

    @Override
    public void update(TeamHandle handle, TeamStyle style) {
        ViewerClaims mine = claims.get(handle.viewerId());
        if (mine == null) {
            return;
        }
        Team team = mine.teams().get(handle.entry());
        if (team == null) {
            return;
        }
        synchronized (team) {
            if (!team.layers.containsKey(handle.purpose())) {
                return;
            }
            team.layers.put(handle.purpose(), style);
            publish(mine.viewer(), team, handle.entry());
        }
    }

    @Override
    public void release(TeamHandle handle) {
        ViewerClaims mine = claims.get(handle.viewerId());
        if (mine == null) {
            return;
        }
        Team team = mine.teams().get(handle.entry());
        if (team == null) {
            return;
        }
        synchronized (team) {
            drop(mine, team, handle.purpose(), handle.entry());
        }
    }

    @Override
    public void releaseAll(Player viewer, String purpose) {
        ViewerClaims mine = claims.get(viewer.getUniqueId());
        if (mine == null) {
            return;
        }
        for (Map.Entry<String, Team> entry : List.copyOf(mine.teams().entrySet())) {
            Team team = entry.getValue();
            synchronized (team) {
                drop(mine, team, purpose, entry.getKey());
            }
        }
    }

    @Override
    public void forget(UUID viewerId) {
        claims.remove(viewerId);
    }

    /** Drops one layer: the team is removed when it was the last, and recomposed when it was not. */
    private void drop(ViewerClaims mine, Team team, String purpose, String entry) {
        if (team.layers.remove(purpose) == null) {
            return;
        }
        if (!team.layers.isEmpty()) {
            publish(mine.viewer(), team, entry);
            return;
        }
        mine.teams().remove(entry, team);
        if (!team.created) {
            return;
        }
        sink.send(mine.viewer(), new WrapperPlayServerTeams(team.name, WrapperPlayServerTeams.TeamMode.REMOVE,
            (WrapperPlayServerTeams.ScoreBoardTeamInfo) null, List.of()));
    }

    /** Sends the composed team, once as a CREATE and afterwards only when the composition moved. */
    private void publish(Player viewer, Team team, String entry) {
        TeamStyle composed = compose(team.layers);
        if (!team.created) {
            team.created = true;
            team.style = composed;
            sink.send(viewer, new WrapperPlayServerTeams(team.name, WrapperPlayServerTeams.TeamMode.CREATE,
                info(composed), List.of(entry)));
            return;
        }
        if (composed.equals(team.style)) {
            return;
        }
        team.style = composed;
        sink.send(viewer, new WrapperPlayServerTeams(team.name, WrapperPlayServerTeams.TeamMode.UPDATE,
            info(composed), List.of()));
    }

    static TeamStyle compose(Map<String, TeamStyle> layers) {
        List<Map.Entry<String, TeamStyle>> ordered = new ArrayList<>(layers.entrySet());
        ordered.sort(Comparator.<Map.Entry<String, TeamStyle>>comparingInt(
            entry -> -LAYER_STRENGTH.getOrDefault(entry.getKey(), 0)).thenComparing(Map.Entry::getKey));
        String prefix = "";
        String suffix = "";
        String color = DEFAULT_COLOR;
        int visibility = 0;
        int collision = 0;
        for (Map.Entry<String, TeamStyle> entry : ordered) {
            TeamStyle layer = entry.getValue();
            if (prefix.isEmpty()) {
                prefix = layer.prefix();
            }
            if (suffix.isEmpty()) {
                suffix = layer.suffix();
            }
            if (DEFAULT_COLOR.equals(color) && !DEFAULT_COLOR.equals(layer.color())) {
                color = layer.color();
            }
            visibility = Math.max(visibility, rank(layer.nameTagVisibility()));
            collision = Math.max(collision, rank(layer.collisionRule()));
        }
        return new TeamStyle(prefix, suffix, color, visibilityOf(visibility), collisionOf(collision));
    }

    /** Higher is more restrictive, so the strictest layer wins whatever order the claims arrived in. */
    private static int rank(NameTagVisibility visibility) {
        return switch (visibility) {
            case ALWAYS -> 0;
            case HIDE_FOR_OTHER_TEAMS -> 1;
            case HIDE_FOR_OWN_TEAM -> 2;
            case NEVER -> 3;
        };
    }

    private static NameTagVisibility visibilityOf(int rank) {
        return switch (rank) {
            case 1 -> NameTagVisibility.HIDE_FOR_OTHER_TEAMS;
            case 2 -> NameTagVisibility.HIDE_FOR_OWN_TEAM;
            case 3 -> NameTagVisibility.NEVER;
            default -> NameTagVisibility.ALWAYS;
        };
    }

    private static int rank(CollisionRule rule) {
        return switch (rule) {
            case ALWAYS -> 0;
            case PUSH_OTHER_TEAMS -> 1;
            case PUSH_OWN_TEAM -> 2;
            case NEVER -> 3;
        };
    }

    private static CollisionRule collisionOf(int rank) {
        return switch (rank) {
            case 1 -> CollisionRule.PUSH_OTHER_TEAMS;
            case 2 -> CollisionRule.PUSH_OWN_TEAM;
            case 3 -> CollisionRule.NEVER;
            default -> CollisionRule.ALWAYS;
        };
    }

    /**
     * {@code gls_t_<base36 sequence>}: unique per viewer-entry, inside the protocol's 16-character
     * team name limit, and never the {@code gls_nc_} prefix display entities own.
     */
    private String teamName() {
        String name = PREFIX + Long.toUnsignedString(sequence.incrementAndGet(), 36);
        return name.length() <= MAX_TEAM_NAME_LENGTH ? name : name.substring(0, MAX_TEAM_NAME_LENGTH);
    }

    private static WrapperPlayServerTeams.ScoreBoardTeamInfo info(TeamStyle style) {
        return new WrapperPlayServerTeams.ScoreBoardTeamInfo(
            Component.empty(),
            TextUtils.parse(style.prefix()),
            TextUtils.parse(style.suffix()),
            visibility(style.nameTagVisibility()),
            collision(style.collisionRule()),
            color(style.color()),
            WrapperPlayServerTeams.OptionData.NONE);
    }

    private static WrapperPlayServerTeams.NameTagVisibility visibility(NameTagVisibility visibility) {
        return switch (visibility) {
            case ALWAYS -> WrapperPlayServerTeams.NameTagVisibility.ALWAYS;
            case NEVER -> WrapperPlayServerTeams.NameTagVisibility.NEVER;
            case HIDE_FOR_OTHER_TEAMS -> WrapperPlayServerTeams.NameTagVisibility.HIDE_FOR_OTHER_TEAMS;
            case HIDE_FOR_OWN_TEAM -> WrapperPlayServerTeams.NameTagVisibility.HIDE_FOR_OWN_TEAM;
        };
    }

    private static WrapperPlayServerTeams.CollisionRule collision(CollisionRule rule) {
        return switch (rule) {
            case ALWAYS -> WrapperPlayServerTeams.CollisionRule.ALWAYS;
            case NEVER -> WrapperPlayServerTeams.CollisionRule.NEVER;
            case PUSH_OTHER_TEAMS -> WrapperPlayServerTeams.CollisionRule.PUSH_OTHER_TEAMS;
            case PUSH_OWN_TEAM -> WrapperPlayServerTeams.CollisionRule.PUSH_OWN_TEAM;
        };
    }

    private static NamedTextColor color(String name) {
        NamedTextColor resolved = NamedTextColor.NAMES.value(name.toLowerCase(Locale.ROOT));
        return resolved == null ? NamedTextColor.WHITE : resolved;
    }

    private static void sendPacket(Player viewer, WrapperPlayServerTeams packet) {
        if (PacketEvents.getAPI() == null) {
            return;
        }
        PacketUtils.send(viewer, packet);
    }

    @FunctionalInterface
    interface PacketSink {
        void send(Player viewer, WrapperPlayServerTeams packet);
    }

    private static final class Team {
        private final String name;
        private final Map<String, TeamStyle> layers = new TreeMap<>();
        private TeamStyle style;
        private boolean created;

        private Team(String name) {
            this.name = name;
        }
    }

    private record ViewerClaims(Player viewer, Map<String, Team> teams) {
        private ViewerClaims(Player viewer) {
            this(viewer, new ConcurrentHashMap<>());
        }
    }
}
