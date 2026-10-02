package art.arcane.gloss.tab;

import art.arcane.gloss.condition.BoundedConditionErrorCallback;
import art.arcane.gloss.expr.ExprScope;
import org.bukkit.entity.Player;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.BiFunction;
import java.util.function.ToIntBiFunction;
import java.util.function.UnaryOperator;

/**
 * Drives one grid of client-side tab entries per viewer. The first pass sends the whole grid, later
 * passes only the rows whose rendered text changed, and real players are unlisted while a layout is
 * live so the grid is the only thing the viewer sees.
 */
public final class TablistLayoutService {

    private final LayoutSink sink;
    private final Renderers renderers;
    private final Map<UUID, ViewerLayout> states = new ConcurrentHashMap<>();

    public TablistLayoutService(LayoutSink sink, Renderers renderers) {
        this.sink = sink;
        this.renderers = renderers;
    }

    public boolean hasLayout(UUID viewerId) {
        return states.containsKey(viewerId);
    }

    public void apply(Player viewer, TablistLayoutRuntime runtime, List<Player> players, ScopeFactory scopes,
                      BoundedConditionErrorCallback errors) {
        if (runtime == null || !runtime.show().matches(scopes.scope(viewer, viewer), errors)) {
            restore(viewer);
            return;
        }
        List<SlotEntry> entries = renderEntries(viewer, runtime, players, scopes, errors);
        ViewerLayout state = states.get(viewer.getUniqueId());
        if (state != null && state.entries.size() != entries.size()) {
            restore(viewer);
            state = null;
        }
        if (state == null) {
            sink.addSlots(viewer, entries);
            states.put(viewer.getUniqueId(), new ViewerLayout(entries));
            unlist(viewer, players);
            return;
        }
        List<SlotEntry> changed = new ArrayList<>();
        List<SlotEntry> replaced = new ArrayList<>();
        List<String> removed = new ArrayList<>();
        for (int index = 0; index < entries.size(); index++) {
            SlotEntry entry = entries.get(index);
            SlotEntry previous = state.entries.get(index);
            if (!Objects.equals(entry.skin(), previous.skin())) {
                removed.add(previous.name());
                replaced.add(entry);
            } else if (!entry.equals(previous)) {
                changed.add(entry);
            }
        }
        if (!removed.isEmpty()) {
            sink.removeSlots(viewer, removed);
            sink.addSlots(viewer, replaced);
        }
        if (!changed.isEmpty()) {
            sink.updateSlots(viewer, changed);
        }
        state.entries = entries;
        unlist(viewer, players);
    }

    public void restore(Player viewer) {
        ViewerLayout state = states.remove(viewer.getUniqueId());
        if (state == null) {
            return;
        }
        List<String> names = new ArrayList<>(state.entries.size());
        for (int index = 0; index < state.entries.size(); index++) {
            names.add(TablistLayoutRuntime.slotName(index));
        }
        sink.removeSlots(viewer, names);
        if (!state.unlisted.isEmpty()) {
            sink.listReal(viewer, List.copyOf(state.unlisted), true);
            state.unlisted.clear();
        }
    }

    /** Remembers an entry the packet rewriter took off this viewer's list, so restore hands it back. */
    public void recordUnlisted(UUID viewerId, Set<UUID> entries) {
        ViewerLayout state = states.get(viewerId);
        if (state != null) {
            state.unlisted.addAll(entries);
        }
    }

    public void forget(UUID viewerId) {
        states.remove(viewerId);
    }

    /** Drops every viewer's state without touching their tablist; restore does that, one by one. */
    public void forgetAll() {
        states.clear();
    }

    private void unlist(Player viewer, List<Player> players) {
        ViewerLayout state = states.get(viewer.getUniqueId());
        if (state == null) {
            return;
        }
        Set<UUID> pending = new LinkedHashSet<>();
        for (Player subject : players) {
            if (state.unlisted.add(subject.getUniqueId())) {
                pending.add(subject.getUniqueId());
            }
        }
        if (!pending.isEmpty()) {
            sink.listReal(viewer, pending, false);
        }
    }

    private List<SlotEntry> renderEntries(Player viewer, TablistLayoutRuntime runtime, List<Player> players,
                                         ScopeFactory scopes, BoundedConditionErrorCallback errors) {
        List<SlotEntry> entries = new ArrayList<>(runtime.size());
        for (int index = 0; index < runtime.size(); index++) {
            TablistLayoutRuntime.Cell cell = runtime.cell(index);
            entries.add(entry(cell, render(cell.text()), cell.skin(), cell.ping() == null ? 0 : cell.ping()));
        }
        fillPlayerCells(viewer, runtime, players, scopes, errors, entries);
        return List.copyOf(entries);
    }

    private void fillPlayerCells(Player viewer, TablistLayoutRuntime runtime, List<Player> players,
                                 ScopeFactory scopes, BoundedConditionErrorCallback errors, List<SlotEntry> entries) {
        List<TablistLayoutRuntime.Cell> cells = runtime.playerCells();
        if (cells.isEmpty()) {
            return;
        }
        List<ListedPlayer> listed = new ArrayList<>(players.size());
        for (Player subject : players) {
            if (runtime.playerFilter() == null
                || runtime.playerFilter().matches(scopes.scope(viewer, subject), errors)) {
                listed.add(new ListedPlayer(subject, renderers.order().applyAsInt(viewer, subject)));
            }
        }
        listed.sort(Comparator.comparingInt(ListedPlayer::order).reversed()
            .thenComparing(listedPlayer -> listedPlayer.player().getName(), String.CASE_INSENSITIVE_ORDER));
        boolean counts = runtime.layout().players().countsOverflow();
        int capacity = cells.size();
        int shown = counts && listed.size() > capacity ? capacity - 1 : Math.min(capacity, listed.size());
        for (int cell = 0; cell < capacity; cell++) {
            TablistLayoutRuntime.Cell target = cells.get(cell);
            int index = target.index();
            if (cell < shown) {
                Player subject = listed.get(cell).player();
                entries.set(index, entry(target, renderers.names().apply(viewer, subject),
                    subject.getName(), subject.getPing()));
            } else if (counts && listed.size() > capacity && cell == capacity - 1) {
                entries.set(index, entry(target, render(runtime.layout().players().overflowFormat().replace("{count}", Integer.toString(listed.size() - shown))), null, 0));
            } else {
                entries.set(index, entry(target, "", null, 0));
            }
        }
    }

    private String render(String raw) {
        String rendered = renderers.text().apply(raw);
        return rendered == null ? "" : rendered;
    }

    private static SlotEntry entry(TablistLayoutRuntime.Cell cell, String text, String skin, int ping) {
        return new SlotEntry(cell.id(), cell.name(), cell.listOrder(), text == null ? "" : text, skin, ping);
    }

    public record Renderers(UnaryOperator<String> text, BiFunction<Player, Player, String> names,
                            ToIntBiFunction<Player, Player> order) {
    }

    /** One grid cell as the client sees it. */
    public record SlotEntry(UUID id, String name, int listOrder, String text, String skin, int ping) {
    }

    /** The viewer/subject pair a player filter is evaluated against. */
    @FunctionalInterface
    public interface ScopeFactory {
        ExprScope scope(Player viewer, Player subject);
    }

    /** Where the layout's entries go; the production implementation writes player-info packets. */
    public interface LayoutSink {
        void addSlots(Player viewer, List<SlotEntry> entries);

        void updateSlots(Player viewer, List<SlotEntry> entries);

        void removeSlots(Player viewer, List<String> slotNames);

        void listReal(Player viewer, Collection<UUID> subjects, boolean listed);
    }

    private record ListedPlayer(Player player, int order) {
    }

    private static final class ViewerLayout {
        private final Set<UUID> unlisted = ConcurrentHashMap.newKeySet();
        private volatile List<SlotEntry> entries;

        private ViewerLayout(List<SlotEntry> entries) {
            this.entries = entries;
        }
    }
}
