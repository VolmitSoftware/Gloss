package art.arcane.gloss.tab;

import art.arcane.gloss.condition.BoundedConditionErrorCallback;
import art.arcane.gloss.expr.ExprFunctions;
import art.arcane.gloss.expr.ExprScope;
import org.bukkit.entity.Player;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Proxy;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.Set;
import java.util.HashSet;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * A layout is one burst of fake entries per viewer, then only the rows whose text changed. Real
 * players are unlisted while a layout is live and relisted the moment it goes away.
 */
class TablistLayoutServiceTest {
    private final List<String> sends = new ArrayList<>();
    private final Map<UUID, String> names = new LinkedHashMap<>();
    private final Map<UUID, Integer> pings = new LinkedHashMap<>();
    private final Set<UUID> hidden = new HashSet<>();
    private final List<TablistLayoutService.SlotEntry> written = new ArrayList<>();
    private final TablistLayoutService layouts = new TablistLayoutService(new RecordingSink(),
        new TablistLayoutService.Renderers((viewer, skin) -> skin, (viewer, subject, format) -> subject.getName(),
            (viewer, subject) -> 0, this::subject, ignored -> null));

    @Test
    void theFirstPassSendsTheWholeGridOnce() {
        Player viewer = player("viewer");
        TablistLayoutRuntime runtime = runtime(1, 2, List.of(
            new TablistLayoutDefinition.Slot(0, 0, "&6Staff", null, null, true),
            new TablistLayoutDefinition.Slot(0, 1, "&7Online", null, 0, true)), null);

        layouts.apply(viewer, runtime, List.of(viewer), this::scope, silent());

        assertEquals(List.of(
            "add viewer [ gloss_slot_0=&6Staff,  gloss_slot_1=&7Online]",
            "unlist viewer [viewer]"), sends);
    }

    @Test
    void aSecondPassWithNoChangeSendsNothing() {
        Player viewer = player("viewer");
        TablistLayoutRuntime runtime = runtime(1, 1, List.of(
            new TablistLayoutDefinition.Slot(0, 0, "&6Staff", null, null, true)), null);

        layouts.apply(viewer, runtime, List.of(viewer), this::scope, silent());
        sends.clear();
        layouts.apply(viewer, runtime, List.of(viewer), this::scope, silent());

        assertEquals(List.of(), sends);
    }

    @Test
    void onlyTheRowsWhoseTextChangedAreResent() {
        Player viewer = player("viewer");
        Player second = player("second");
        TablistLayoutRuntime runtime = runtime(1, 2, List.of(
            new TablistLayoutDefinition.Slot(0, 0, "&6Staff", null, null, true)),
            new TablistLayoutDefinition.Section("players", 0, 1, 1, 1, "true", null, List.of(), "hide", null, false, null, true));

        layouts.apply(viewer, runtime, List.of(viewer), this::scope, silent());
        sends.clear();
        layouts.apply(viewer, runtime, List.of(viewer, second), this::scope, silent());

        assertEquals(List.of("remove viewer [ gloss_slot_1]", "add viewer [ gloss_slot_1=second]",
            "unlist viewer [second]"), sends,
            "the player cell moved, the static row did not");
    }

    @Test
    void thePlayerCellsFillInSortedOrderAndOverflowCanCount() {
        Player viewer = player("aaa");
        Player second = player("bbb");
        Player third = player("ccc");
        TablistLayoutRuntime hide = runtime(1, 2, List.of(),
            section(0, 1, 2, "true", "hide", null));
        TablistLayoutRuntime count = runtime(1, 2, List.of(),
            section(0, 1, 2, "true", "count", "and {count} more"));

        layouts.apply(viewer, hide, List.of(third, viewer, second), this::scope, silent());
        assertEquals("add aaa [ gloss_slot_0=aaa,  gloss_slot_1=bbb]", sends.get(0));

        sends.clear();
        layouts.forget(viewer.getUniqueId());
        layouts.apply(viewer, count, List.of(third, viewer, second), this::scope, silent());
        assertEquals("add aaa [ gloss_slot_0=aaa,  gloss_slot_1=and 2 more]", sends.get(0));
    }

    @Test
    void theFilterDecidesWhichPlayersAppear() {
        Player viewer = player("aaa");
        Player hidden = player("bbb");
        TablistLayoutRuntime runtime = runtime(1, 2, List.of(),
            section(0, 1, 2, "subject.name != 'bbb'", "hide", null));

        layouts.apply(viewer, runtime, List.of(viewer, hidden), this::scope, silent());

        assertEquals("add aaa [ gloss_slot_0=aaa,  gloss_slot_1=]", sends.get(0));
    }

    @Test
    void restoringAViewerRemovesTheGridAndRelistsRealPlayers() {
        Player viewer = player("viewer");
        TablistLayoutRuntime runtime = runtime(1, 1, List.of(
            new TablistLayoutDefinition.Slot(0, 0, "&6Staff", null, null, true)), null);

        layouts.apply(viewer, runtime, List.of(viewer), this::scope, silent());
        sends.clear();
        layouts.restore(viewer);

        assertEquals(List.of("remove viewer [ gloss_slot_0]", "relist viewer [viewer]"), sends);
    }

    @Test
    void aLayoutThatStopsShowingForAViewerRestoresIt() {
        Player viewer = player("viewer");
        TablistLayoutRuntime runtime = runtime(1, 1, List.of(
            new TablistLayoutDefinition.Slot(0, 0, "&6Staff", null, null, true)), null);

        layouts.apply(viewer, runtime, List.of(viewer), this::scope, silent());
        sends.clear();
        layouts.apply(viewer, null, List.of(viewer), this::scope, silent());

        assertEquals(List.of("remove viewer [ gloss_slot_0]", "relist viewer [viewer]"), sends);
    }

    @Test
    void aViewerWithALayoutIsKnownToThePacketRewriter() {
        Player viewer = player("viewer");
        TablistLayoutRuntime runtime = runtime(1, 1, List.of(), null);

        assertTrue(!layouts.hasLayout(viewer.getUniqueId()));
        layouts.apply(viewer, runtime, List.of(viewer), this::scope, silent());
        assertTrue(layouts.hasLayout(viewer.getUniqueId()));
        layouts.forget(viewer.getUniqueId());
        assertTrue(!layouts.hasLayout(viewer.getUniqueId()));
    }

    @Test
    void playerCellsUseThePresentationOrderSkinAndLatency() {
        Player viewer = player("aaa");
        Player staff = player("zzz");
        pings.put(staff.getUniqueId(), 73);
        TablistLayoutService weighted = new TablistLayoutService(new RecordingSink(),
            new TablistLayoutService.Renderers((recipient, text) -> text,
                (observer, subject, format) -> "[Staff] " + subject.getName(),
                (observer, subject) -> subject == staff ? 10 : 0, this::subject, ignored -> null));
        TablistLayoutRuntime runtime = runtime(1, 2, List.of(),
            section(0, 1, 2, "true", "hide", null));

        weighted.apply(viewer, runtime, List.of(viewer, staff), this::scope, silent());

        TablistLayoutService.SlotEntry first = written.getFirst();
        assertEquals("[Staff] zzz", first.text());
        assertEquals("zzz", first.skin());
        assertEquals(73, first.ping());
        written.clear();
        pings.put(staff.getUniqueId(), 95);
        weighted.apply(viewer, runtime, List.of(viewer, staff), this::scope, silent());
        assertEquals(1, written.size());
        assertEquals(95, written.getFirst().ping());
    }

    @Test
    void replacingAPlayerWithTheSameDisplayNameRefreshesItsSkin() {
        Player viewer = player("aaa");
        Player replacement = player("bbb");
        TablistLayoutService identicalNames = new TablistLayoutService(new RecordingSink(),
            new TablistLayoutService.Renderers((recipient, text) -> text, (observer, subject, format) -> "Same",
                (observer, subject) -> 0, this::subject, ignored -> null));
        TablistLayoutRuntime runtime = runtime(1, 1, List.of(),
            section(0, 1, 1, "true", "hide", null));
        identicalNames.apply(viewer, runtime, List.of(viewer), this::scope, silent());
        sends.clear();
        written.clear();

        identicalNames.apply(viewer, runtime, List.of(replacement), this::scope, silent());

        assertEquals("remove aaa [ gloss_slot_0]", sends.getFirst());
        assertEquals("bbb", written.getFirst().skin());
    }

    @Test
    void resizingTheLayoutReplacesTheGrid() {
        Player viewer = player("viewer");
        layouts.apply(viewer, runtime(1, 1, List.of(), null), List.of(viewer), this::scope, silent());
        sends.clear();

        layouts.apply(viewer, runtime(1, 2, List.of(), null), List.of(viewer), this::scope, silent());

        assertEquals(List.of("remove viewer [ gloss_slot_0]", "relist viewer [viewer]",
            "add viewer [ gloss_slot_0=,  gloss_slot_1=]", "unlist viewer [viewer]"), sends);
    }

    private TablistLayoutRuntime runtime(int columns, int rows, List<TablistLayoutDefinition.Slot> slots,
                                         TablistLayoutDefinition.Section players) {
        return TablistLayoutRuntime.compile(new TablistDoc.Layout(true, columns * rows, slots, players == null ? List.of() : List.of(players), null, Map.of(), List.of()));
    }

    private ExprScope scope(Player viewer, Player subject) {
        return new TestScope(Map.of("viewer.name", viewer.getName(), "viewer.bedrock", Boolean.FALSE,
            "subject.name", subject == null ? "" : subject.getName()));
    }

    private static BoundedConditionErrorCallback silent() {
        return BoundedConditionErrorCallback.silent();
    }

    @Test
    void fixedCellsRenderInTheirViewersContext() {
        Player viewer = player("reader");
        TablistLayoutService personal = new TablistLayoutService(new RecordingSink(),
            new TablistLayoutService.Renderers((reader, text) -> reader.getName() + ":" + text,
                (reader, subject, format) -> subject.getName(), (reader, subject) -> 0, this::subject, ignored -> null));
        personal.apply(viewer, runtime(1, 1, List.of(new TablistLayoutDefinition.Slot(0, 0, "cell", null, 0, true)), null),
            List.of(viewer), this::scope, silent());
        assertEquals("reader:cell", written.getFirst().text());
    }

    @Test
    void hiddenPlayersAreExcludedAndNotRelistedDuringRestore() {
        Player viewer = player("reader");
        Player other = player("hidden");
        TablistLayoutRuntime runtime = runtime(1, 2, List.of(),
            section(0, 1, 2, "true", "hide", null));
        layouts.apply(viewer, runtime, List.of(viewer, other), this::scope, silent());
        hidden.add(other.getUniqueId());
        layouts.apply(viewer, runtime, List.of(viewer, other), this::scope, silent());
        sends.clear();
        layouts.restore(viewer);
        assertEquals("relist reader [reader]", sends.getLast());
    }

    @Test
    void externalUnlistingRelinquishesRestorationOwnership() {
        Player viewer = player("reader");
        Player other = player("other");
        layouts.apply(viewer, runtime(1, 1, List.of(), null), List.of(viewer, other), this::scope, silent());
        layouts.recordUnlisted(viewer.getUniqueId(), Set.of(), Set.of(other.getUniqueId()));
        assertTrue(layouts.externallyHidden(viewer.getUniqueId(), other.getUniqueId()));
        sends.clear();
        layouts.restore(viewer);
        assertEquals("relist reader [reader]", sends.getLast());
    }

    @Test
    void playerRowsReadCapturedIdentityAndLatencyWithoutForeignEntityGetters() {
        UUID id = UUID.randomUUID();
        Player foreign = (Player) Proxy.newProxyInstance(Player.class.getClassLoader(), new Class<?>[]{Player.class},
            (proxy, method, arguments) -> {
                if (method.getName().equals("getUniqueId")) {
                    return id;
                }
                throw new AssertionError("Foreign getter: " + method.getName());
            });
        Player viewer = player("reader");
        TablistLayoutService captured = new TablistLayoutService(new RecordingSink(),
            new TablistLayoutService.Renderers((reader, text) -> text, (reader, subject, format) -> "Captured",
                (reader, subject) -> 0, (reader, subject) -> new TablistLayoutService.Subject("Captured", 91, true, false), ignored -> null));
        captured.apply(viewer, runtime(1, 1, List.of(),
            section(0, 1, 1, "true", "hide", null)), List.of(foreign),
            (reader, subject) -> new TestScope(Map.of("viewer.bedrock", false)), silent());
        assertEquals("Captured", written.getFirst().skin());
        assertEquals(91, written.getFirst().ping());
    }

    @Test
    void conditionalLayoutsUsePriorityThenIdentifierAndNamedSkins() {
        Player viewer = player("reader");
        TablistLayoutDefinition.Skin skin = new TablistLayoutDefinition.Skin("texture-value", "signature");
        TablistLayoutDefinition.LayoutPresentation selected = new TablistLayoutDefinition.LayoutPresentation(1,
            List.of(new TablistLayoutDefinition.Slot(0, 0, "selected", "brand", 3, false)), List.of(), Map.of("brand", skin));
        TablistDoc.Layout layout = new TablistDoc.Layout(true, 1, List.of(), List.of(), null, Map.of(), List.of(
            new TablistLayoutDefinition.LayoutVariant("z", 9, "true", new TablistLayoutDefinition.LayoutPresentation(2, List.of(), List.of(), Map.of())),
            new TablistLayoutDefinition.LayoutVariant("a", 9, "viewer.name == 'reader'", selected)));
        layouts.apply(viewer, TablistLayoutRuntime.compile(layout), List.of(viewer), this::scope, silent());
        assertEquals(1, written.size());
        assertEquals("selected", written.getFirst().text());
        assertEquals(skin, written.getFirst().texture());
        assertEquals(false, written.getFirst().hat());
    }

    @Test
    void independentSectionsSortMultipleKeysAndStartAtTheirAuthoredRows() {
        Player viewer = player("reader");
        Player first = player("aaa");
        Player second = player("bbb");
        TablistLayoutDefinition.Section ascending = new TablistLayoutDefinition.Section("first", 0, 1, 1, 2, "subject.name != 'reader'",
            null, List.of(new TablistLayoutDefinition.SortKey("1", "number", "descending"),
                new TablistLayoutDefinition.SortKey("subject.name", "text", "ascending")), "hide", null, false, null, true);
        TablistLayoutDefinition.Section descending = new TablistLayoutDefinition.Section("second", 0, 4, 1, 2, "subject.name != 'reader'",
            null, List.of(new TablistLayoutDefinition.SortKey("subject.name", "text", "descending")), "hide", null, false, null, true);
        TablistDoc.Layout layout = new TablistDoc.Layout(true, 6, List.of(), List.of(ascending, descending), null, Map.of(), List.of());
        layouts.apply(viewer, TablistLayoutRuntime.compile(layout), List.of(second, viewer, first), this::scope, silent());
        assertEquals("", written.get(0).text());
        assertEquals("aaa", written.get(1).text());
        assertEquals("bbb", written.get(2).text());
        assertEquals("bbb", written.get(4).text());
        assertEquals("aaa", written.get(5).text());
    }

    @Test
    void changedCapturedTextureReplacesSlotEvenWhenAccountNameIsUnchanged() {
        Player viewer = player("reader");
        Map<String, TablistLayoutDefinition.Skin> textures = new LinkedHashMap<>();
        textures.put("reader", new TablistLayoutDefinition.Skin("first", "signature"));
        TablistLayoutService captured = new TablistLayoutService(new RecordingSink(),
            new TablistLayoutService.Renderers((reader, text) -> text, (reader, subject, format) -> subject.getName(),
                (reader, subject) -> 0, this::subject, textures::get));
        TablistLayoutRuntime runtime = runtime(1, 1, List.of(), section(0, 1, 1, "true", "hide", null));
        captured.apply(viewer, runtime, List.of(viewer), this::scope, silent());
        sends.clear();
        textures.put("reader", new TablistLayoutDefinition.Skin("second", "signature"));
        captured.apply(viewer, runtime, List.of(viewer), this::scope, silent());
        assertEquals(List.of("remove reader [ gloss_slot_0]", "add reader [ gloss_slot_0=reader]"), sends);
    }

    private TablistLayoutService.Subject subject(Player viewer, Player player) {
        return new TablistLayoutService.Subject(player.getName(), pings.getOrDefault(player.getUniqueId(), 0),
            !hidden.contains(player.getUniqueId()), false);
    }

    private Player player(String name) {
        UUID id = UUID.nameUUIDFromBytes(name.getBytes(StandardCharsets.UTF_8));
        names.put(id, name);
        return (Player) Proxy.newProxyInstance(Player.class.getClassLoader(), new Class<?>[]{Player.class},
            (proxy, method, args) -> switch (method.getName()) {
                case "getUniqueId" -> id;
                case "getName" -> name;
                case "getPing" -> pings.getOrDefault(id, 0);
                case "hashCode" -> System.identityHashCode(proxy);
                case "equals" -> proxy == args[0];
                case "toString" -> name;
                default -> throw new UnsupportedOperationException(method.getName());
            });
    }

    private record TestScope(Map<String, Object> variables) implements ExprScope {
        @Override
        public Object variable(String dottedName) {
            return variables.get(dottedName);
        }

        @Override
        public Object call(String name, List<Object> args) {
            return ExprFunctions.call(name, args);
        }
    }

    private final class RecordingSink implements TablistLayoutService.LayoutSink {
        @Override
        public void addSlots(Player viewer, List<TablistLayoutService.SlotEntry> entries) {
            written.addAll(entries);
            sends.add("add " + viewer.getName() + " " + render(entries));
        }

        @Override
        public void updateSlots(Player viewer, List<TablistLayoutService.SlotEntry> entries) {
            written.addAll(entries);
            sends.add("text " + viewer.getName() + " " + render(entries));
        }

        @Override
        public void removeSlots(Player viewer, List<String> slotNames) {
            sends.add("remove " + viewer.getName() + " " + slotNames);
        }

        @Override
        public void listReal(Player viewer, Collection<UUID> subjects, boolean listed) {
            List<String> subjectNames = new ArrayList<>();
            for (UUID subject : subjects) {
                subjectNames.add(names.get(subject));
            }
            sends.add((listed ? "relist " : "unlist ") + viewer.getName() + " " + subjectNames);
        }

        private String render(List<TablistLayoutService.SlotEntry> entries) {
            List<String> parts = new ArrayList<>(entries.size());
            for (TablistLayoutService.SlotEntry entry : entries) {
                parts.add(entry.name() + "=" + entry.text());
            }
            return parts.toString();
        }
    }
    private static TablistLayoutDefinition.Section section(int column, int columns, int rows, String filter, String overflow,
                                               String overflowFormat) {
        return new TablistLayoutDefinition.Section("players", column, 0, columns, rows, filter, null, List.of(), overflow,
            overflowFormat, false, null, true);
    }

}
