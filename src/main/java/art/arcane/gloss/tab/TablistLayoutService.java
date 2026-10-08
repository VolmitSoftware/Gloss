package art.arcane.gloss.tab;

import art.arcane.gloss.condition.BoundedConditionErrorCallback;
import art.arcane.gloss.expr.ExprScope;
import art.arcane.gloss.expr.ExprEvaluator;
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
import java.util.function.Function;
import java.util.function.ToIntBiFunction;


/**
 * Drives one grid of client-side tab entries per viewer. The first pass sends the whole grid, later
 * passes only the rows whose rendered text changed, and real players are unlisted while a layout is
 * live so the grid is the only thing the viewer sees.
 */
public final class TablistLayoutService {

    private final LayoutSink sink;
    private final Renderers renderers;
    private final Map<UUID, ViewerLayout> states = new ConcurrentHashMap<>();
    private final Map<UUID, Set<UUID>> hiddenSubjects = new ConcurrentHashMap<>();

    public TablistLayoutService(LayoutSink sink, Renderers renderers) {
        this.sink = sink;
        this.renderers = renderers;
    }

    public boolean hasLayout(UUID viewerId) {
        return states.containsKey(viewerId);
    }

    public void apply(Player viewer, TablistLayoutRuntime runtime, List<Player> players, ScopeFactory scopes,
                      BoundedConditionErrorCallback errors) {
        runtime = runtime == null ? null : runtime.select(scopes.scope(viewer, viewer), errors);
        if (runtime == null) {
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
            if (!Objects.equals(entry.skin(), previous.skin()) || !Objects.equals(entry.texture(), previous.texture())) {
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
    public void recordUnlisted(UUID viewerId, Set<UUID> entries, Set<UUID> hidden) {
        Set<UUID> unavailable = hiddenSubjects.computeIfAbsent(viewerId, ignored -> ConcurrentHashMap.newKeySet());
        unavailable.addAll(hidden);
        unavailable.removeAll(entries);
        ViewerLayout state = states.get(viewerId);
        if (state != null) {
            state.unlisted.addAll(entries);
            state.unlisted.removeAll(hidden);
        }
    }

    public boolean externallyHidden(UUID viewerId, UUID subjectId) {
        Set<UUID> hidden = hiddenSubjects.get(viewerId);
        return hidden != null && hidden.contains(subjectId);
    }

    public void forget(UUID viewerId) {
        states.remove(viewerId);
        hiddenSubjects.remove(viewerId);
        for (Set<UUID> hidden : hiddenSubjects.values()) {
            hidden.remove(viewerId);
        }
    }

    /** Drops every viewer's state without touching their tablist; restore does that, one by one. */
    public void forgetAll() {
        states.clear();
        hiddenSubjects.clear();
    }

    private void unlist(Player viewer, List<Player> players) {
        ViewerLayout state = states.get(viewer.getUniqueId());
        if (state == null) {
            return;
        }
        Set<UUID> pending = new LinkedHashSet<>();
        for (Player subject : players) {
            Subject captured = renderers.subjects().apply(viewer, subject);
            if (captured == null || !captured.visible()) {
                state.unlisted.remove(subject.getUniqueId());
                continue;
            }
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
            entries.add(entry(cell, render(viewer, cell.text()), cell.skin(), cell.ping() == null ? 0 : cell.ping(),
                cell.hat(), skin(runtime, cell.skin())));
        }
        for (TablistLayoutRuntime.Section section : runtime.sections()) {
            fillPlayerCells(viewer, runtime, section, players, scopes, errors, entries);
        }
        return List.copyOf(entries);
    }

    private void fillPlayerCells(Player viewer, TablistLayoutRuntime runtime, TablistLayoutRuntime.Section section,
                                 List<Player> players, ScopeFactory scopes, BoundedConditionErrorCallback errors,
                                 List<SlotEntry> entries) {
        List<TablistLayoutRuntime.Cell> cells = section.cells();
        List<ListedPlayer> listed = new ArrayList<>(players.size());
        for (Player subject : players) {
            Subject captured = renderers.subjects().apply(viewer, subject);
            if (captured == null || !captured.visible() || (captured.npc() && !section.source().includeNpcs())) {
                continue;
            }
            ExprScope scope = scopes.scope(viewer, subject);
            if (!section.filter().matches(scope, errors)) {
                continue;
            }
            List<Object> keys = new ArrayList<>(section.sort().size());
            for (TablistLayoutRuntime.SortKey key : section.sort()) {
                keys.add(key.number() ? ExprEvaluator.number(key.expression(), scope)
                    : ExprEvaluator.string(key.expression(), scope));
            }
            listed.add(new ListedPlayer(subject, captured, renderers.order().applyAsInt(viewer, subject), keys));
        }
        listed.sort((left, right) -> compare(left, right, section.sort()));
        boolean counts = section.source().countsOverflow();
        int capacity = cells.size();
        int shown = counts && listed.size() > capacity ? capacity - 1 : Math.min(capacity, listed.size());
        for (int cell = 0; cell < capacity; cell++) {
            TablistLayoutRuntime.Cell target = cells.get(cell);
            if (cell < shown) {
                ListedPlayer listedPlayer = listed.get(cell);
                String skin = section.source().skin() == null ? listedPlayer.subject().name() : section.source().skin();
                entries.set(target.index(), entry(target,
                    renderers.names().render(viewer, listedPlayer.player(), section.source().format()), skin,
                    listedPlayer.subject().ping(), section.source().hat(), skin(runtime, skin)));
            } else if (counts && listed.size() > capacity && cell == capacity - 1) {
                entries.set(target.index(), entry(target, render(viewer, section.source().overflowFormat()
                    .replace("{count}", Integer.toString(listed.size() - shown))), null, 0, false, null));
            } else {
                entries.set(target.index(), entry(target, "", null, 0, false, null));
            }
        }
    }

    private static int compare(ListedPlayer left, ListedPlayer right, List<TablistLayoutRuntime.SortKey> keys) {
        for (int index = 0; index < keys.size(); index++) {
            TablistLayoutRuntime.SortKey key = keys.get(index);
            int compared = key.number() ? Double.compare((Double) left.keys().get(index), (Double) right.keys().get(index))
                : ((String) left.keys().get(index)).compareTo((String) right.keys().get(index));
            if (compared != 0) {
                return key.descending() ? -compared : compared;
            }
        }
        int order = keys.isEmpty() ? Integer.compare(right.order(), left.order()) : 0;
        if (order != 0) {
            return order;
        }
        int name = String.CASE_INSENSITIVE_ORDER.compare(left.subject().name(), right.subject().name());
        return name == 0 ? left.player().getUniqueId().compareTo(right.player().getUniqueId()) : name;
    }

    private TablistLayoutDefinition.Skin skin(TablistLayoutRuntime runtime, String name) {
        TablistLayoutDefinition.Skin configured = runtime.skin(name);
        return configured != null || name == null ? configured : renderers.skins().apply(name);
    }

    private String render(Player viewer, String raw) {
        String rendered = renderers.text().apply(viewer, raw);
        return rendered == null ? "" : rendered;
    }

    private static SlotEntry entry(TablistLayoutRuntime.Cell cell, String text, String skin, int ping, boolean hat, TablistLayoutDefinition.Skin texture) {
        return new SlotEntry(cell.id(), cell.name(), cell.listOrder(), text == null ? "" : text, skin, ping, hat, texture);
    }

    public record Renderers(BiFunction<Player, String, String> text, NameRenderer names,
                            ToIntBiFunction<Player, Player> order, BiFunction<Player, Player, Subject> subjects,
                            Function<String, TablistLayoutDefinition.Skin> skins) {
    }

    /** One grid cell as the client sees it. */
    public record SlotEntry(UUID id, String name, int listOrder, String text, String skin, int ping,
                             boolean hat, TablistLayoutDefinition.Skin texture) {
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

    @FunctionalInterface
    public interface NameRenderer {
        String render(Player viewer, Player subject, String format);
    }

    public record Subject(String name, int ping, boolean visible, boolean npc) {
    }

    private record ListedPlayer(Player player, Subject subject, int order, List<Object> keys) {
    }

    private static final class ViewerLayout {
        private final Set<UUID> unlisted = ConcurrentHashMap.newKeySet();
        private volatile List<SlotEntry> entries;

        private ViewerLayout(List<SlotEntry> entries) {
            this.entries = entries;
        }
    }
}
