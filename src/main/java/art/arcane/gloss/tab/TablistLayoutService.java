package art.arcane.gloss.tab;

import art.arcane.gloss.condition.BoundedConditionErrorCallback;
import art.arcane.gloss.expr.ExprScope;
import org.bukkit.entity.Player;

import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.UnaryOperator;

/**
 * Drives one grid of client-side tab entries per viewer. The first pass sends the whole grid, later
 * passes only the rows whose rendered text changed, and real players are unlisted while a layout is
 * live so the grid is the only thing the viewer sees.
 */
public final class TablistLayoutService {
    public static final String OVERFLOW_PREFIX = "+";

    private final LayoutSink sink;
    private final UnaryOperator<String> renderer;
    private final Map<UUID, ViewerLayout> states = new ConcurrentHashMap<>();

    public TablistLayoutService(LayoutSink sink, UnaryOperator<String> renderer) {
        this.sink = sink;
        this.renderer = renderer;
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
        String[] texts = renderTexts(viewer, runtime, players, scopes, errors);
        ViewerLayout state = states.get(viewer.getUniqueId());
        if (state == null) {
            sink.addSlots(viewer, entries(runtime, texts, allIndexes(texts.length)));
            states.put(viewer.getUniqueId(), new ViewerLayout(texts));
            unlist(viewer, players);
            return;
        }
        List<Integer> changed = new ArrayList<>();
        for (int index = 0; index < texts.length; index++) {
            if (!texts[index].equals(state.texts[index])) {
                changed.add(index);
            }
        }
        if (!changed.isEmpty()) {
            sink.updateTexts(viewer, entries(runtime, texts, changed));
            state.texts = texts;
        }
        unlist(viewer, players);
    }

    public void restore(Player viewer) {
        ViewerLayout state = states.remove(viewer.getUniqueId());
        if (state == null) {
            return;
        }
        List<String> names = new ArrayList<>(state.texts.length);
        for (int index = 0; index < state.texts.length; index++) {
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

    private String[] renderTexts(Player viewer, TablistLayoutRuntime runtime, List<Player> players,
                                 ScopeFactory scopes, BoundedConditionErrorCallback errors) {
        String[] texts = new String[runtime.size()];
        for (int index = 0; index < texts.length; index++) {
            texts[index] = render(runtime.cell(index).text());
        }
        fillPlayerCells(viewer, runtime, players, scopes, errors, texts);
        return texts;
    }

    private void fillPlayerCells(Player viewer, TablistLayoutRuntime runtime, List<Player> players,
                                 ScopeFactory scopes, BoundedConditionErrorCallback errors, String[] texts) {
        List<TablistLayoutRuntime.Cell> cells = runtime.playerCells();
        if (cells.isEmpty()) {
            return;
        }
        List<Player> listed = new ArrayList<>(players.size());
        for (Player subject : players) {
            if (runtime.playerFilter() == null
                || runtime.playerFilter().matches(scopes.scope(viewer, subject), errors)) {
                listed.add(subject);
            }
        }
        listed.sort((left, right) -> left.getName().compareToIgnoreCase(right.getName()));
        boolean counts = runtime.layout().players().countsOverflow();
        int capacity = cells.size();
        int shown = counts && listed.size() > capacity ? capacity - 1 : Math.min(capacity, listed.size());
        for (int cell = 0; cell < capacity; cell++) {
            int index = cells.get(cell).index();
            if (cell < shown) {
                texts[index] = render(listed.get(cell).getName());
            } else if (counts && listed.size() > capacity && cell == capacity - 1) {
                texts[index] = OVERFLOW_PREFIX + (listed.size() - shown);
            } else {
                texts[index] = "";
            }
        }
    }

    private String render(String raw) {
        String rendered = renderer.apply(raw);
        return rendered == null ? "" : rendered;
    }

    private static List<Integer> allIndexes(int size) {
        List<Integer> indexes = new ArrayList<>(size);
        for (int index = 0; index < size; index++) {
            indexes.add(index);
        }
        return indexes;
    }

    private static List<SlotEntry> entries(TablistLayoutRuntime runtime, String[] texts, List<Integer> indexes) {
        List<SlotEntry> entries = new ArrayList<>(indexes.size());
        for (int index : indexes) {
            TablistLayoutRuntime.Cell cell = runtime.cell(index);
            entries.add(new SlotEntry(cell.id(), cell.name(), cell.listOrder(), texts[index], cell.skin(),
                cell.ping() == null ? 0 : cell.ping()));
        }
        return entries;
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

        void updateTexts(Player viewer, List<SlotEntry> entries);

        void removeSlots(Player viewer, List<String> slotNames);

        void listReal(Player viewer, Collection<UUID> subjects, boolean listed);
    }

    private static final class ViewerLayout {
        private final Set<UUID> unlisted = ConcurrentHashMap.newKeySet();
        private volatile String[] texts;

        private ViewerLayout(String[] texts) {
            this.texts = texts;
        }
    }
}
