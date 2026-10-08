package art.arcane.gloss.util.common;

import art.arcane.gloss.service.GlossTelemetry;
import com.github.retrooper.packetevents.PacketEvents;
import com.github.retrooper.packetevents.event.PacketListenerAbstract;
import com.github.retrooper.packetevents.event.PacketListenerCommon;
import com.github.retrooper.packetevents.event.PacketListenerPriority;
import com.github.retrooper.packetevents.event.PacketSendEvent;
import com.github.retrooper.packetevents.protocol.packettype.PacketType;
import com.github.retrooper.packetevents.wrapper.play.server.WrapperPlayServerTeams;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import org.bukkit.entity.Player;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Set;
import java.util.Objects;
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

    public enum ForeignPolicy { YIELD, OVERRIDE }
    public enum Composition { INTERSECTION, PRIORITY }

    public record Policy(ForeignPolicy foreignPolicy, Map<String, Integer> layerPriorities,
                         Composition visibilityPolicy, Composition collisionPolicy, boolean whiteIsUnspecified) {
        public static final Policy DEFAULTS = new Policy(ForeignPolicy.YIELD,
            Map.of("glow", 3, "nameplate", 2, "nametag", 1, "entity-overlay", 2),
            Composition.INTERSECTION, Composition.INTERSECTION, true);

        public Policy {
            Objects.requireNonNull(foreignPolicy);
            Objects.requireNonNull(visibilityPolicy);
            Objects.requireNonNull(collisionPolicy);
            layerPriorities = Map.copyOf(layerPriorities);
            for (Map.Entry<String, Integer> layer : layerPriorities.entrySet()) {
                if (layer.getKey().isBlank() || layer.getValue() < -1000000 || layer.getValue() > 1000000) {
                    throw new IllegalArgumentException("Team layer priorities require a nonempty purpose and a value in -1000000..1000000");
                }
            }
        }
    }

    private final PacketSink sink;
    private final AtomicLong sequence = new AtomicLong();
    private final Map<UUID, ViewerClaims> claims = new ConcurrentHashMap<>();
    private final Map<UUID, ForeignTeams> foreign = new ConcurrentHashMap<>();
    private volatile Policy policy = Policy.DEFAULTS;
    private PacketListenerCommon listener;

    public PacketTeamAllocator() {
        this(PacketTeamAllocator::sendPacket);
    }

    PacketTeamAllocator(PacketSink sink) {
        this.sink = sink;
    }

    public void enable() {
        if (listener == null && PacketEvents.getAPI() != null) {
            listener = PacketEvents.getAPI().getEventManager().registerListener(new PacketListenerAbstract(PacketListenerPriority.MONITOR) {
                @Override
                public void onPacketSend(PacketSendEvent event) {
                    observe(event);
                }
            });
        }
    }

    public void disable() {
        if (listener != null && PacketEvents.getAPI() != null) {
            PacketEvents.getAPI().getEventManager().unregisterListener(listener);
            listener = null;
        }
        for (ViewerClaims viewer : claims.values()) {
            for (Map.Entry<String, Team> entry : viewer.teams().entrySet()) {
                synchronized (entry.getValue()) {
                    remove(viewer, entry.getValue(), entry.getKey());
                }
            }
        }
        claims.clear();
        foreign.clear();
    }

    public void configure(Policy replacement) {
        policy = Objects.requireNonNull(replacement);
        for (ViewerClaims viewer : claims.values()) {
            for (Map.Entry<String, Team> entry : viewer.teams().entrySet()) {
                synchronized (entry.getValue()) {
                    publish(viewer.viewer(), entry.getValue(), entry.getKey());
                }
            }
        }
    }

    @Override
    public TeamHandle claim(Player viewer, String purpose, String entry, TeamStyle style) {
        ViewerClaims mine = claims.computeIfAbsent(viewer.getUniqueId(), key -> new ViewerClaims(viewer));
        Team team = mine.teams().computeIfAbsent(entry, key -> new Team(teamName()));
        synchronized (team) {
            TeamStyle previous = team.layers.put(purpose, style);
            if (!Objects.equals(previous, style)) {
                publish(mine.viewer(), team, entry);
            }
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
            TeamStyle previous = team.layers.put(handle.purpose(), style);
            if (!Objects.equals(previous, style)) {
                publish(mine.viewer(), team, handle.entry());
            }
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
        foreign.remove(viewerId);
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
        remove(mine, team, entry);
    }

    private void remove(ViewerClaims mine, Team team, String entry) {
        if (team.created) {
            sink.send(mine.viewer(), new WrapperPlayServerTeams(team.name, WrapperPlayServerTeams.TeamMode.REMOVE,
                (WrapperPlayServerTeams.ScoreBoardTeamInfo) null, List.of()));
        }
        String previous = foreignTeam(mine.viewer().getUniqueId(), entry);
        if (team.joined && previous != null) {
            sink.send(mine.viewer(), membership(previous, WrapperPlayServerTeams.TeamMode.ADD_ENTITIES, entry));
        }
        team.created = false;
        team.joined = false;
    }

    /** Sends the composed team, once as a CREATE and afterwards only when the composition moved. */
    private void publish(Player viewer, Team team, String entry) {
        Policy current = policy;
        String external = foreignTeam(viewer.getUniqueId(), entry);
        if (current.foreignPolicy() == ForeignPolicy.YIELD && external != null) {
            if (team.joined) {
                sink.send(viewer, membership(external, WrapperPlayServerTeams.TeamMode.ADD_ENTITIES, entry));
                team.joined = false;
            }
            return;
        }
        TeamStyle composed = compose(team.layers, current);
        if (!team.created) {
            team.created = true;
            team.joined = true;
            team.style = composed;
            sink.send(viewer, new WrapperPlayServerTeams(team.name, WrapperPlayServerTeams.TeamMode.CREATE,
                info(composed), List.of(entry)));
            return;
        }
        if (!team.joined) {
            sink.send(viewer, membership(team.name, WrapperPlayServerTeams.TeamMode.ADD_ENTITIES, entry));
            team.joined = true;
        }
        if (composed.equals(team.style)) {
            return;
        }
        team.style = composed;
        sink.send(viewer, new WrapperPlayServerTeams(team.name, WrapperPlayServerTeams.TeamMode.UPDATE,
            info(composed), List.of()));
    }

    static TeamStyle compose(Map<String, TeamStyle> layers) {
        return compose(layers, Policy.DEFAULTS);
    }

    static TeamStyle compose(Map<String, TeamStyle> layers, Policy policy) {
        List<Map.Entry<String, TeamStyle>> ordered = new ArrayList<>(layers.entrySet());
        ordered.sort(Comparator.<Map.Entry<String, TeamStyle>>comparingInt(
            entry -> policy.layerPriorities().getOrDefault(entry.getKey(), 0)).reversed()
            .thenComparing(Map.Entry::getKey));
        String prefix = "";
        String suffix = "";
        String color = DEFAULT_COLOR;
        boolean colored = false;
        NameTagVisibility visibility = NameTagVisibility.ALWAYS;
        CollisionRule collision = CollisionRule.ALWAYS;
        boolean first = true;
        for (Map.Entry<String, TeamStyle> entry : ordered) {
            TeamStyle layer = entry.getValue();
            if (prefix.isEmpty()) {
                prefix = layer.prefix();
            }
            if (suffix.isEmpty()) {
                suffix = layer.suffix();
            }
            if (!colored && (!policy.whiteIsUnspecified() || !DEFAULT_COLOR.equals(layer.color()))) {
                color = layer.color();
                colored = true;
            }
            if (first || policy.visibilityPolicy() == Composition.INTERSECTION) {
                visibility = intersect(visibility, layer.nameTagVisibility());
            }
            if (first || policy.collisionPolicy() == Composition.INTERSECTION) {
                collision = intersect(collision, layer.collisionRule());
            }
            first = false;
        }
        return new TeamStyle(prefix, suffix, color, visibility, collision);
    }

    private static NameTagVisibility intersect(NameTagVisibility left, NameTagVisibility right) {
        if (left == NameTagVisibility.ALWAYS) {
            return right;
        }
        if (right == NameTagVisibility.ALWAYS || left == right) {
            return left;
        }
        return NameTagVisibility.NEVER;
    }

    private static CollisionRule intersect(CollisionRule left, CollisionRule right) {
        if (left == CollisionRule.ALWAYS) {
            return right;
        }
        if (right == CollisionRule.ALWAYS || left == right) {
            return left;
        }
        return CollisionRule.NEVER;
    }

    private void observe(PacketSendEvent event) {
        if (event.isCancelled() || event.getUser() == null || event.getUser().getUUID() == null
            || event.getPacketType() != PacketType.Play.Server.TEAMS) {
            return;
        }
        UUID viewerId = event.getUser().getUUID();
        WrapperPlayServerTeams packet = new WrapperPlayServerTeams(event);
        Set<String> changed = observe(viewerId, packet);
        if (!changed.isEmpty()) {
            event.getTasksAfterSend().add(() -> reconcile(viewerId, changed));
        }
    }

    Set<String> observe(UUID viewerId, WrapperPlayServerTeams packet) {
        ViewerClaims mine = claims.get(viewerId);
        if (packet.getTeamName().startsWith(PREFIX)) {
            return Set.of();
        }
        ForeignTeams state = foreign.computeIfAbsent(viewerId, ignored -> new ForeignTeams());
        Set<String> changed = new HashSet<>();
        synchronized (state) {
            String team = packet.getTeamName();
            switch (packet.getTeamMode()) {
                case CREATE, ADD_ENTITIES -> {
                    Set<String> members = state.teams.computeIfAbsent(team, ignored -> new HashSet<>());
                    for (String entry : packet.getPlayers()) {
                        String previous = state.entries.put(entry, team);
                        if (previous != null && !previous.equals(team)) {
                            Set<String> old = state.teams.get(previous);
                            if (old != null) {
                                old.remove(entry);
                            }
                        }
                        members.add(entry);
                        changed.add(entry);
                    }
                }
                case REMOVE -> {
                    Set<String> members = state.teams.remove(team);
                    if (members != null) {
                        for (String entry : members) {
                            if (state.entries.remove(entry, team)) {
                                changed.add(entry);
                            }
                        }
                    }
                }
                case REMOVE_ENTITIES -> {
                    Set<String> members = state.teams.get(team);
                    for (String entry : packet.getPlayers()) {
                        if (members != null) {
                            members.remove(entry);
                        }
                        if (state.entries.remove(entry, team)) {
                            changed.add(entry);
                        }
                    }
                }
                default -> { }
            }
        }
        if (mine == null) {
            return Set.of();
        }
        changed.retainAll(mine.teams().keySet());
        for (String entry : changed) {
            Team owned = mine.teams().get(entry);
            if (owned != null) {
                synchronized (owned) {
                    owned.joined = false;
                }
            }
        }
        return changed;
    }

    void reconcile(UUID viewerId, Set<String> changed) {
        ViewerClaims viewer = claims.get(viewerId);
        if (viewer == null) {
            return;
        }
        for (String entry : changed) {
            Team team = viewer.teams().get(entry);
            if (team != null) {
                synchronized (team) {
                    if (!team.layers.isEmpty()) {
                        publish(viewer.viewer(), team, entry);
                    }
                }
            }
        }
    }

    private String foreignTeam(UUID viewerId, String entry) {
        ForeignTeams state = foreign.get(viewerId);
        if (state == null) {
            return null;
        }
        synchronized (state) {
            return state.entries.get(entry);
        }
    }

    private static WrapperPlayServerTeams membership(String team, WrapperPlayServerTeams.TeamMode mode, String entry) {
        return new WrapperPlayServerTeams(team, mode, (WrapperPlayServerTeams.ScoreBoardTeamInfo) null, List.of(entry));
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
        PacketEvents.getAPI().getPlayerManager().sendPacketSilently(viewer, packet);
        GlossTelemetry.countPackets(1L);
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
        private boolean joined;

        private Team(String name) {
            this.name = name;
        }
    }

    private static final class ForeignTeams {
        private final Map<String, Set<String>> teams = new HashMap<>();
        private final Map<String, String> entries = new HashMap<>();
    }

    private record ViewerClaims(Player viewer, Map<String, Team> teams) {
        private ViewerClaims(Player viewer) {
            this(viewer, new ConcurrentHashMap<>());
        }
    }
}
