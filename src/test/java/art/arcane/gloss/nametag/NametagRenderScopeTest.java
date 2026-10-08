package art.arcane.gloss.nametag;

import art.arcane.gloss.condition.BoundedConditionErrorCallback;
import art.arcane.gloss.condition.ShowCondition;
import art.arcane.gloss.doc.DocumentEnvelope;
import art.arcane.gloss.expr.ExprFunctions;
import art.arcane.gloss.expr.ExprScope;
import art.arcane.gloss.util.common.TeamAllocator;
import org.bukkit.entity.Player;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import art.arcane.gloss.condition.RoleSnapshotPendingException;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * A prefix is authored against the pair the tag is for: the shipped default and the doc page both
 * show {@code {{ subject.group }}}. These pin that the text is rendered against that pair's scope
 * and not as a static string with no viewer and no subject.
 */
class NametagRenderScopeTest {

    @Test
    void twoSubjectsRenderTheirOwnPrefix() {
        RecordingTeams teams = new RecordingTeams();
        NametagDriver driver = new NametagDriver(teams, NametagRenderScopeTest::render, (viewer, subject) -> 0.0D);
        Player viewer = player("Viewer");
        Player steve = player("Steve");
        Player alex = player("Alex");

        driver.apply(List.of(viewer), List.of(steve, alex), List.of(runtime("[{{ subject.group }}] ")),
            (pairViewer, subject) -> scope(Map.of("subject.group", "Steve".equals(subject.getName())
                ? "staff" : "default")),
            BoundedConditionErrorCallback.silent());

        assertEquals(List.of("Steve=[staff] ", "Alex=[default] "), teams.claimed);
    }

    @Test
    void theViewerReachesTheRenderer() {
        RecordingTeams teams = new RecordingTeams();
        List<String> viewers = new ArrayList<>();
        NametagDriver driver = new NametagDriver(teams, (viewer, raw, scope) -> {
            viewers.add(viewer == null ? "none" : viewer.getName());
            return raw;
        }, (viewer, subject) -> 0.0D);

        driver.apply(List.of(player("Viewer")), List.of(player("Steve")), List.of(runtime("&7tag")),
            (pairViewer, subject) -> scope(Map.of()), BoundedConditionErrorCallback.silent());

        assertEquals(List.of("Viewer"), viewers);
    }

    @Test
    void subjectOnlyPrefixesRenderOncePerSubjectForAllViewers() {
        RecordingTeams teams = new RecordingTeams();
        AtomicInteger renders = new AtomicInteger();
        NametagDriver driver = new NametagDriver(teams, (viewer, raw, scope) -> {
            renders.incrementAndGet();
            return render(viewer, raw, scope);
        }, (viewer, subject) -> 0.0D);
        List<Player> players = new ArrayList<>();
        for (int index = 0; index < 64; index++) {
            players.add(player("p" + index));
        }
        driver.apply(players, players, List.of(runtime("{{ subject.group }}")),
            (viewer, subject) -> scope(Map.of("subject.group", subject.getName())),
            BoundedConditionErrorCallback.silent());
        assertEquals(64, renders.get());
        assertEquals(64 * 64, teams.claimed.size());
    }

    @Test
    void unknownNamespacePrefixesRemainViewerSpecific() {
        RecordingTeams teams = new RecordingTeams();
        AtomicInteger renders = new AtomicInteger();
        NametagDriver driver = new NametagDriver(teams, (viewer, raw, scope) -> {
            renders.incrementAndGet();
            return viewer.getName();
        }, (viewer, subject) -> 0.0D);
        List<Player> players = List.of(player("a"), player("b"));
        driver.apply(players, players, List.of(runtime("{{ custom.rank }}")),
            (viewer, subject) -> scope(Map.of()), BoundedConditionErrorCallback.silent());
        assertEquals(4, renders.get());
    }

    @Test
    void awaitingAnOwnerSampleKeepsTheExistingPresentation() {
        RecordingTeams teams = new RecordingTeams();
        NametagDriver driver = new NametagDriver(teams, (viewer, raw, scope) -> {
            if (raw.contains("viewer.name")) {
                throw new RoleSnapshotPendingException();
            }
            return raw;
        }, (viewer, subject) -> 0.0D);
        Player player = player("viewer");
        driver.apply(List.of(player), List.of(player), List.of(runtime("known")),
            (viewer, subject) -> scope(Map.of()), BoundedConditionErrorCallback.silent());
        teams.claimed.clear();
        driver.apply(List.of(player), List.of(player), List.of(runtime("{{ viewer.name }}")),
            (viewer, subject) -> scope(Map.of()), BoundedConditionErrorCallback.silent());
        assertEquals(List.of(), teams.claimed);
    }

    private static String render(Player viewer, String raw, ExprScope scope) {
        String out = raw;
        int start = out.indexOf("{{");
        while (start >= 0) {
            int end = out.indexOf("}}", start);
            if (end < 0) {
                break;
            }
            Object value = scope.variable(out.substring(start + 2, end).trim());
            out = out.substring(0, start) + (value == null ? "" : value) + out.substring(end + 2);
            start = out.indexOf("{{");
        }
        return out;
    }

    private static NametagRuntime runtime(String prefix) {
        return NametagRuntime.compile("tags", new NametagDoc(NametagDoc.CURRENT_SCHEMA_VERSION,
            DocumentEnvelope.INITIAL_REVISION, ShowCondition.ALWAYS, new NametagDoc.Selection(0, "true", ""),
            new NametagDoc.Presentation(prefix, "", "white", "always", "always"), List.of()));
    }

    private static ExprScope scope(Map<String, Object> variables) {
        return new TestScope(variables);
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

    private static final class RecordingTeams implements TeamAllocator {
        private final List<String> claimed = new ArrayList<>();

        @Override
        public TeamHandle claim(Player viewer, String purpose, String entry, TeamStyle style) {
            claimed.add(entry + "=" + style.prefix());
            return new TeamHandle(viewer.getUniqueId(), purpose, entry, "gls_na_" + claimed.size());
        }

        @Override
        public void update(TeamHandle handle, TeamStyle style) {
            claimed.add(handle.entry() + "=" + style.prefix());
        }

        @Override
        public void release(TeamHandle handle) {
            claimed.add("release " + handle.entry());
        }

        @Override
        public void releaseAll(Player viewer, String purpose) {
        }

        @Override
        public void forget(UUID viewerId) {
        }
    }

    private static Player player(String name) {
        UUID id = UUID.nameUUIDFromBytes(name.getBytes());
        return (Player) Proxy.newProxyInstance(Player.class.getClassLoader(), new Class<?>[]{Player.class},
            (Object proxy, Method method, Object[] args) -> switch (method.getName()) {
                case "getUniqueId" -> id;
                case "getName" -> name;
                case "isOnline" -> true;
                case "hashCode" -> id.hashCode();
                case "equals" -> proxy == args[0];
                case "toString" -> "Player[" + name + "]";
                default -> throw new UnsupportedOperationException(method.getName());
            });
    }
}
