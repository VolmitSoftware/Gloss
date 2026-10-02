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
    private final List<TablistLayoutService.SlotEntry> written = new ArrayList<>();
    private final TablistLayoutService layouts = new TablistLayoutService(new RecordingSink(),
        new TablistLayoutService.Renderers(skin -> skin, (viewer, subject) -> subject.getName(),
            (viewer, subject) -> 0));

    @Test
    void theFirstPassSendsTheWholeGridOnce() {
        Player viewer = player("viewer");
        TablistLayoutRuntime runtime = runtime(1, 2, List.of(
            new TablistDoc.Slot(0, 0, "&6Staff", null, null),
            new TablistDoc.Slot(0, 1, "&7Online", null, 0)), null);

        layouts.apply(viewer, runtime, List.of(viewer), this::scope, silent());

        assertEquals(List.of(
            "add viewer [ gloss_slot_0=&6Staff,  gloss_slot_1=&7Online]",
            "unlist viewer [viewer]"), sends);
    }

    @Test
    void aSecondPassWithNoChangeSendsNothing() {
        Player viewer = player("viewer");
        TablistLayoutRuntime runtime = runtime(1, 1, List.of(
            new TablistDoc.Slot(0, 0, "&6Staff", null, null)), null);

        layouts.apply(viewer, runtime, List.of(viewer), this::scope, silent());
        sends.clear();
        layouts.apply(viewer, runtime, List.of(viewer), this::scope, silent());

        assertEquals(List.of(), sends);
    }

    @Test
    void onlyTheRowsWhoseTextChangedAreResent() {
        Player viewer = player("viewer");
        Player second = player("second");
        TablistLayoutRuntime runtime = runtime(2, 1, List.of(
            new TablistDoc.Slot(0, 0, "&6Staff", null, null)),
            new TablistDoc.Players(1, 1, 1, "true", "hide"));

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
            new TablistDoc.Players(0, 1, 2, "true", "hide"));
        TablistLayoutRuntime count = runtime(1, 2, List.of(),
            new TablistDoc.Players(0, 1, 2, "true", "count"));

        layouts.apply(viewer, hide, List.of(third, viewer, second), this::scope, silent());
        assertEquals("add aaa [ gloss_slot_0=aaa,  gloss_slot_1=bbb]", sends.get(0));

        sends.clear();
        layouts.forget(viewer.getUniqueId());
        layouts.apply(viewer, count, List.of(third, viewer, second), this::scope, silent());
        assertEquals("add aaa [ gloss_slot_0=aaa,  gloss_slot_1=+2]", sends.get(0));
    }

    @Test
    void theFilterDecidesWhichPlayersAppear() {
        Player viewer = player("aaa");
        Player hidden = player("bbb");
        TablistLayoutRuntime runtime = runtime(1, 2, List.of(),
            new TablistDoc.Players(0, 1, 2, "subject.name != 'bbb'", "hide"));

        layouts.apply(viewer, runtime, List.of(viewer, hidden), this::scope, silent());

        assertEquals("add aaa [ gloss_slot_0=aaa,  gloss_slot_1=]", sends.get(0));
    }

    @Test
    void restoringAViewerRemovesTheGridAndRelistsRealPlayers() {
        Player viewer = player("viewer");
        TablistLayoutRuntime runtime = runtime(1, 1, List.of(
            new TablistDoc.Slot(0, 0, "&6Staff", null, null)), null);

        layouts.apply(viewer, runtime, List.of(viewer), this::scope, silent());
        sends.clear();
        layouts.restore(viewer);

        assertEquals(List.of("remove viewer [ gloss_slot_0]", "relist viewer [viewer]"), sends);
    }

    @Test
    void aLayoutThatStopsShowingForAViewerRestoresIt() {
        Player viewer = player("viewer");
        TablistLayoutRuntime runtime = runtime(1, 1, List.of(
            new TablistDoc.Slot(0, 0, "&6Staff", null, null)), null);

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
            new TablistLayoutService.Renderers(text -> text,
                (observer, subject) -> "[Staff] " + subject.getName(),
                (observer, subject) -> subject == staff ? 10 : 0));
        TablistLayoutRuntime runtime = runtime(1, 2, List.of(),
            new TablistDoc.Players(0, 1, 2, "true", "hide"));

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
            new TablistLayoutService.Renderers(text -> text, (observer, subject) -> "Same",
                (observer, subject) -> 0));
        TablistLayoutRuntime runtime = runtime(1, 1, List.of(),
            new TablistDoc.Players(0, 1, 1, "true", "hide"));
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

    @Test
    void listNamesUseThePublishedSubjectRenderWithoutReadingPlayerState() {
        Player subject = player("ListedPlayer");
        Map<UUID, String> rendered = new LinkedHashMap<>();
        assertEquals("ListedPlayer", TablistService.layoutPlayerName(subject, rendered));

        rendered.put(subject.getUniqueId(), "&a[Staff] ListedPlayer");
        assertEquals("&a[Staff] ListedPlayer", TablistService.layoutPlayerName(subject, rendered));

        rendered.put(subject.getUniqueId(), "&b[Member] ListedPlayer");
        assertEquals("&b[Member] ListedPlayer", TablistService.layoutPlayerName(subject, rendered));
    }

    private TablistLayoutRuntime runtime(int columns, int rows, List<TablistDoc.Slot> slots,
                                         TablistDoc.Players players) {
        return TablistLayoutRuntime.compile(new TablistDoc.Layout(true, columns, rows, slots, players, null));
    }

    private ExprScope scope(Player viewer, Player subject) {
        return new TestScope(Map.of("viewer.name", viewer.getName(), "viewer.bedrock", Boolean.FALSE,
            "subject.name", subject == null ? "" : subject.getName()));
    }

    private static BoundedConditionErrorCallback silent() {
        return BoundedConditionErrorCallback.silent();
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
}
