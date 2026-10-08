package art.arcane.gloss.velocity;

import art.arcane.gloss.expr.ExprEvaluator;
import art.arcane.gloss.expr.Expr;
import art.arcane.gloss.expr.ExpressionScope;
import art.arcane.gloss.tab.PlayerInfoRewriteListener;
import art.arcane.gloss.tab.TablistLayoutDefinition;
import com.github.retrooper.packetevents.PacketEvents;
import com.github.retrooper.packetevents.event.PacketListenerCommon;
import com.github.retrooper.packetevents.manager.server.ServerVersion;
import com.github.retrooper.packetevents.protocol.player.ClientVersion;
import com.github.retrooper.packetevents.protocol.player.TextureProperty;
import com.github.retrooper.packetevents.protocol.player.User;
import com.github.retrooper.packetevents.protocol.player.UserProfile;
import com.github.retrooper.packetevents.wrapper.PacketWrapper;
import com.github.retrooper.packetevents.wrapper.play.server.WrapperPlayServerPlayerInfoRemove;
import com.github.retrooper.packetevents.wrapper.play.server.WrapperPlayServerPlayerInfoUpdate;
import com.velocitypowered.api.proxy.Player;
import com.velocitypowered.api.proxy.player.TabListEntry;
import com.velocitypowered.api.util.GameProfile;
import net.kyori.adventure.text.Component;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

public final class ProxyTablistGrid {
    private static final List<Identity> IDENTITIES = identities();
    private final ProxyText text;
    private final Map<UUID, State> states = new ConcurrentHashMap<>();
    private final Map<UUID, Set<UUID>> hidden = new ConcurrentHashMap<>();
    private final PacketListenerCommon listener;
    private volatile Set<UUID> subjects = Set.of();

    private static List<Identity> identities() {
        List<Identity> identities = new ArrayList<>(80);
        for (int index = 0; index < 80; index++) {
            identities.add(new Identity(UUID.nameUUIDFromBytes(("gloss:tab:" + index).getBytes(StandardCharsets.UTF_8)),
                TablistLayoutDefinition.SLOT_NAME_PREFIX + index, 100_000 - index));
        }
        return List.copyOf(identities);
    }

    public ProxyTablistGrid(ProxyText text) {
        this.text = text;
        this.listener = PacketEvents.getAPI().getEventManager().registerListener(new PlayerInfoRewriteListener(
            new PlayerInfoRewriteListener.Ownership(states::containsKey, id -> subjects.contains(id), this::observe)));
    }

    public void roster(ProxyRoster roster) {
        subjects = roster.subjects().keySet();
    }

    public boolean visible(Player viewer, UUID subject) {
        if (hidden.getOrDefault(viewer.getUniqueId(), Set.of()).contains(subject)) {
            return false;
        }
        TabListEntry entry = viewer.getTabList().getEntry(subject).orElse(null);
        return entry != null && entry.isListed();
    }

    public void render(Render request) {
        Player viewer = request.viewer().player();
        ExpressionScope scope = request.roster().scope(text, request.viewer(), request.viewer());
        ProxyTabLayout.Presentation presentation = request.document().layout() == null ? null
            : request.document().layout().select(scope);
        User user = PacketEvents.getAPI().getPlayerManager().getUser(viewer);
        if (presentation == null || user == null || user.getClientVersion() == null
            || !user.getClientVersion().isNewerThanOrEquals(ClientVersion.V_1_21_2)) {
            clear(viewer);
            return;
        }
        List<Cell> cells = cells(request, presentation);
        State state = states.computeIfAbsent(viewer.getUniqueId(), ignored -> new State());
        List<UUID> removed = new ArrayList<>();
        List<Cell> added = new ArrayList<>();
        List<Cell> updated = new ArrayList<>();
        for (int index = 0; index < Math.max(state.cells.size(), cells.size()); index++) {
            Cell old = index < state.cells.size() ? state.cells.get(index) : null;
            Cell next = index < cells.size() ? cells.get(index) : null;
            if (old != null && (next == null || !Objects.equals(old.texture(), next.texture()))) {
                removed.add(old.id());
            }
            if (next != null && (old == null || !Objects.equals(old.texture(), next.texture()))) {
                added.add(next);
            } else if (next != null && !next.equals(old)) {
                updated.add(next);
            }
        }
        if (!removed.isEmpty()) {
            send(viewer, new WrapperPlayServerPlayerInfoRemove(removed));
        }
        write(viewer, added, true);
        write(viewer, updated, false);
        state.cells = cells;
        Set<UUID> newlyUnlisted = new LinkedHashSet<>();
        for (ProxyRoster.Subject subject : request.roster().subjects().values()) {
            if (visible(viewer, subject.id()) && request.roster().visibility().visible(viewer.getUniqueId(), subject.id())) {
                if (state.unlisted.add(subject.id())) {
                    newlyUnlisted.add(subject.id());
                }
            } else {
                state.unlisted.remove(subject.id());
            }
        }
        listed(viewer, newlyUnlisted, false);
    }

    public void clear(Player viewer) {
        State state = states.remove(viewer.getUniqueId());
        if (state == null) {
            return;
        }
        send(viewer, new WrapperPlayServerPlayerInfoRemove(state.cells.stream().map(Cell::id).toList()));
        state.unlisted.removeIf(id -> !visible(viewer, id));
        listed(viewer, state.unlisted, true);
    }

    public void forget(UUID viewer) {
        states.remove(viewer);
        hidden.remove(viewer);
        for (Set<UUID> ids : hidden.values()) {
            ids.remove(viewer);
        }
    }

    public void unregister() {
        PacketEvents.getAPI().getEventManager().unregisterListener(listener);
    }

    private void observe(UUID viewer, Set<UUID> listed, Set<UUID> removed) {
        Set<UUID> unavailable = hidden.computeIfAbsent(viewer, ignored -> ConcurrentHashMap.newKeySet());
        unavailable.addAll(removed);
        unavailable.removeAll(listed);
        State state = states.get(viewer);
        if (state != null) {
            state.unlisted.addAll(listed);
            state.unlisted.removeAll(removed);
        }
    }

    List<Cell> cells(Render request, ProxyTabLayout.Presentation presentation) {
        TablistLayoutDefinition.LayoutPresentation source = presentation.source();
        ExpressionScope scope = request.roster().scope(text, request.viewer(), request.viewer());
        List<Cell> cells = new ArrayList<>(source.entries());
        for (int index = 0; index < source.entries(); index++) {
            cells.add(cell(index, Component.empty(), 0, null, true));
        }
        for (TablistLayoutDefinition.Slot slot : source.slots()) {
            int index = slot.column() * source.rows() + slot.row();
            cells.set(index, cell(index, text.render(slot.text(), scope), slot.ping() == null ? 0 : slot.ping(),
                texture(source, slot.skin(), request.roster()), slot.hat()));
        }
        for (ProxyTabLayout.Section section : presentation.sections()) {
            List<Row> rows = new ArrayList<>(request.roster().subjects().size());
            for (ProxyRoster.Subject subject : request.roster().subjects().values()) {
                boolean local = request.viewer().server().equals(subject.server());
                if ((!request.network() && !local) || !visible(request.viewer().player(), subject.id())
                    || !request.roster().visibility().visible(request.viewer().id(), subject.id())
                    || (!section.source().includeNpcs() && request.roster().visibility().npcs().contains(subject.id()))) {
                    continue;
                }
                ExpressionScope subjectScope = request.roster().scope(text, request.viewer(), subject);
                if (!text.test(request.visibility(), subjectScope) || !text.test(section.filter(), subjectScope)) {
                    continue;
                }
                List<Object> keys = new ArrayList<>(section.sort().size());
                for (ProxyTabLayout.SortKey key : section.sort()) {
                    keys.add(key.numeric() ? ExprEvaluator.number(key.expression(), subjectScope)
                        : ExprEvaluator.string(key.expression(), subjectScope));
                }
                int order = request.document().sortWeight() == null ? 0
                    : (int) Math.clamp(Math.round(ExprEvaluator.number(request.document().sortWeight(), subjectScope)),
                        Integer.MIN_VALUE, Integer.MAX_VALUE);
                rows.add(new Row(subject, subjectScope, keys, order));
            }
            rows.sort((left, right) -> compare(left, right, section.sort()));
            int capacity = section.source().columns() * section.source().rows();
            boolean overflow = section.source().countsOverflow() && rows.size() > capacity;
            int shown = overflow ? capacity - 1 : Math.min(capacity, rows.size());
            int cursor = 0;
            for (int column = section.source().column(); column < section.source().column() + section.source().columns(); column++) {
                for (int row = section.source().row(); row < section.source().row() + section.source().rows(); row++, cursor++) {
                    int index = column * source.rows() + row;
                    if (cursor < shown) {
                        Row entry = rows.get(cursor);
                        ProxyDocuments.ListName name = text.select(request.document().listNames(), entry.scope());
                        String format = section.source().format() == null ? (name == null ? "$player" : name.format()) : section.source().format();
                        String skin = section.source().skin() == null ? entry.subject().name() : section.source().skin();
                        cells.set(index, cell(index, text.render(format, entry.scope()), entry.subject().ping(),
                            texture(source, skin, request.roster()), section.source().hat()));
                    } else if (overflow && cursor == capacity - 1) {
                        cells.set(index, cell(index, text.render(section.source().overflowFormat().replace("{count}",
                            Integer.toString(rows.size() - shown)), scope), 0, null, false));
                    }
                }
            }
        }
        return List.copyOf(cells);
    }

    private static int compare(Row left, Row right, List<ProxyTabLayout.SortKey> sort) {
        for (int index = 0; index < sort.size(); index++) {
            ProxyTabLayout.SortKey key = sort.get(index);
            int order = key.numeric() ? Double.compare((Double) left.keys().get(index), (Double) right.keys().get(index))
                : ((String) left.keys().get(index)).compareTo((String) right.keys().get(index));
            if (order != 0) {
                return key.descending() ? -order : order;
            }
        }
        int order = sort.isEmpty() ? Integer.compare(right.order(), left.order()) : 0;
        if (order != 0) {
            return order;
        }
        int name = String.CASE_INSENSITIVE_ORDER.compare(left.subject().name(), right.subject().name());
        return name == 0 ? left.subject().id().compareTo(right.subject().id()) : name;
    }

    private static TablistLayoutDefinition.Skin texture(TablistLayoutDefinition.LayoutPresentation source, String name, ProxyRoster roster) {
        return name == null ? null : source.skins().getOrDefault(name, roster.textures().get(name));
    }

    private static Cell cell(int index, Component text, int ping, TablistLayoutDefinition.Skin texture, boolean hat) {
        Identity identity = IDENTITIES.get(index);
        return new Cell(identity.id(), identity.name(), identity.order(), text, ping, texture, hat);
    }

    private static void listed(Player viewer, Set<UUID> ids, boolean listed) {
        if (ids.isEmpty()) {
            return;
        }
        List<WrapperPlayServerPlayerInfoUpdate.PlayerInfo> entries = new ArrayList<>(ids.size());
        for (UUID id : ids) {
            WrapperPlayServerPlayerInfoUpdate.PlayerInfo entry = new WrapperPlayServerPlayerInfoUpdate.PlayerInfo(new UserProfile(id, null));
            entry.setListed(listed);
            entries.add(entry);
        }
        send(viewer, new WrapperPlayServerPlayerInfoUpdate(WrapperPlayServerPlayerInfoUpdate.Action.UPDATE_LISTED, entries));
    }

    private static void write(Player viewer, List<Cell> cells, boolean added) {
        if (cells.isEmpty()) {
            return;
        }
        EnumSet<WrapperPlayServerPlayerInfoUpdate.Action> actions = EnumSet.of(
            WrapperPlayServerPlayerInfoUpdate.Action.UPDATE_DISPLAY_NAME, WrapperPlayServerPlayerInfoUpdate.Action.UPDATE_LATENCY);
        if (added) {
            actions.add(WrapperPlayServerPlayerInfoUpdate.Action.ADD_PLAYER);
            actions.add(WrapperPlayServerPlayerInfoUpdate.Action.UPDATE_LISTED);
            actions.add(WrapperPlayServerPlayerInfoUpdate.Action.UPDATE_LIST_ORDER);
        }
        User user = PacketEvents.getAPI().getPlayerManager().getUser(viewer);
        if (user != null && user.getClientVersion().isNewerThanOrEquals(ClientVersion.V_1_21_4)
            && PacketEvents.getAPI().getServerManager().getVersion().isNewerThanOrEquals(ServerVersion.V_1_21_4)) {
            actions.add(WrapperPlayServerPlayerInfoUpdate.Action.UPDATE_HAT);
        }
        List<WrapperPlayServerPlayerInfoUpdate.PlayerInfo> entries = new ArrayList<>(cells.size());
        for (Cell cell : cells) {
            WrapperPlayServerPlayerInfoUpdate.PlayerInfo entry = new WrapperPlayServerPlayerInfoUpdate.PlayerInfo(
                new UserProfile(cell.id(), cell.name(), cell.texture() == null ? List.of()
                    : List.of(new TextureProperty("textures", cell.texture().value(), cell.texture().signature()))));
            entry.setListed(true);
            entry.setListOrder(cell.order());
            entry.setDisplayName(cell.text());
            entry.setLatency(cell.ping());
            entry.setShowHat(cell.hat());
            entries.add(entry);
        }
        send(viewer, new WrapperPlayServerPlayerInfoUpdate(actions, entries));
    }

    private static void send(Player viewer, PacketWrapper<?> packet) {
        PacketEvents.getAPI().getPlayerManager().sendPacketSilently(viewer, packet);
    }

    public record Render(ProxyRoster.Subject viewer, ProxyRoster roster, ProxyDocuments.Tablist document,
                         boolean network, Expr visibility) {
    }

    record Cell(UUID id, String name, int order, Component text, int ping, TablistLayoutDefinition.Skin texture, boolean hat) {
    }

    private record Identity(UUID id, String name, int order) {
    }

    private record Row(ProxyRoster.Subject subject, ExpressionScope scope, List<Object> keys, int order) {
    }

    private static final class State {
        private final Set<UUID> unlisted = ConcurrentHashMap.newKeySet();
        private List<Cell> cells = List.of();
    }
}
