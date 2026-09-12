package art.arcane.gloss.nametag;

import art.arcane.gloss.condition.BoundedConditionErrorCallback;
import art.arcane.gloss.condition.ShowCondition;
import art.arcane.gloss.doc.DocumentEnvelope;
import art.arcane.gloss.expr.ExprFunctions;
import art.arcane.gloss.expr.ExprScope;
import art.arcane.gloss.util.common.TeamAllocator;
import org.bukkit.entity.Player;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Proxy;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicLong;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Broadcast documents tag every subject for every viewer; a document that reads the viewer only
 * reaches the nearest subjects, and everything is released when a viewer goes away.
 */
class NametagServiceModeTest {
    private final RecordingAllocator teams = new RecordingAllocator();
    private final Map<String, Double> distances = new java.util.LinkedHashMap<>();
    private final NametagDriver driver = new NametagDriver(teams, (viewer, raw, scope) -> raw,
        (viewer, subject) -> distances.getOrDefault(viewer.getName() + ">" + subject.getName(), 0.0D));

    @Test
    void aBroadcastDocumentClaimsOneTeamPerViewerPerSubject() {
        Player one = player("one");
        Player two = player("two");

        driver.apply(List.of(one, two), List.of(one, two), List.of(runtime("subject.op")), this::scope, silent());

        assertEquals(List.of("claim one nametag one", "claim one nametag two",
            "claim two nametag one", "claim two nametag two"), teams.calls);
    }

    @Test
    void aSecondPassUpdatesInsteadOfClaimingAgain() {
        Player one = player("one");

        driver.apply(List.of(one), List.of(one), List.of(runtime("subject.op")), this::scope, silent());
        teams.calls.clear();
        driver.apply(List.of(one), List.of(one), List.of(runtime("subject.op")), this::scope, silent());

        assertEquals(List.of("update one nametag one"), teams.calls);
    }

    @Test
    void aPerViewerDocumentSkipsSubjectsOutOfRange() {
        Player viewer = player("viewer");
        Player near = player("near");
        Player far = player("far");
        distances.put("viewer>far", 100_000.0D);

        driver.apply(List.of(viewer), List.of(viewer, near, far), List.of(runtime("viewer.op")),
            this::scope, silent());

        assertEquals(List.of("claim viewer nametag viewer", "claim viewer nametag near"), teams.calls);
    }

    @Test
    void aPerViewerDocumentKeepsOnlyTheNearestSubjects() {
        Player viewer = player("viewer");
        List<Player> subjects = new ArrayList<>();
        for (int index = 0; index < NametagDriver.PER_VIEWER_SUBJECT_CAP + 8; index++) {
            Player subject = player("s" + index);
            distances.put("viewer>s" + index, index * 1.0D);
            subjects.add(subject);
        }

        driver.apply(List.of(viewer), subjects, List.of(runtime("viewer.op")), this::scope, silent());

        assertEquals(NametagDriver.PER_VIEWER_SUBJECT_CAP, teams.calls.size());
        assertTrue(teams.calls.get(0).endsWith("s0"));
    }

    @Test
    void aSubjectThatStopsMatchingIsReleased() {
        Player viewer = player("viewer");
        Player subject = player("subject");

        driver.apply(List.of(viewer), List.of(viewer, subject), List.of(runtime("subject.op")),
            this::scope, silent());
        teams.calls.clear();
        driver.apply(List.of(viewer), List.of(viewer, subject), List.of(runtime("!subject.op")),
            this::scope, silent());

        assertTrue(teams.calls.contains("release viewer nametag subject"), teams.calls.toString());
    }

    @Test
    void releasingAViewerDropsEveryTeamItOwned() {
        Player viewer = player("viewer");
        Player subject = player("subject");
        driver.apply(List.of(viewer), List.of(viewer, subject), List.of(runtime("subject.op")),
            this::scope, silent());
        teams.calls.clear();

        driver.releaseAll(viewer);

        assertEquals(List.of("releaseAll viewer nametag"), teams.calls);
    }

    @Test
    void forgettingAViewerAlsoForgetsItInTheAllocator() {
        Player viewer = player("viewer");
        driver.apply(List.of(viewer), List.of(viewer), List.of(runtime("subject.op")), this::scope, silent());
        teams.calls.clear();

        driver.forget(viewer.getUniqueId());

        assertEquals(List.of("forget " + viewer.getUniqueId()), teams.calls);
    }

    private ExprScope scope(Player viewer, Player subject) {
        return new TestScope(Map.of("viewer.op", Boolean.TRUE, "subject.op", Boolean.TRUE,
            "subject.name", subject.getName(), "viewer.name", viewer.getName()));
    }

    private static NametagRuntime runtime(String when) {
        return NametagRuntime.compile("tags", new NametagDoc(NametagDoc.CURRENT_SCHEMA_VERSION,
            DocumentEnvelope.INITIAL_REVISION, ShowCondition.ALWAYS, new NametagDoc.Selection(0, when),
            new NametagDoc.Presentation("&7", "", "white", "always", "always"), List.of()));
    }

    private static BoundedConditionErrorCallback silent() {
        return BoundedConditionErrorCallback.silent();
    }

    private static Player player(String name) {
        UUID id = UUID.nameUUIDFromBytes(name.getBytes(StandardCharsets.UTF_8));
        return (Player) Proxy.newProxyInstance(Player.class.getClassLoader(), new Class<?>[]{Player.class},
            (proxy, method, args) -> switch (method.getName()) {
                case "getUniqueId" -> id;
                case "getName" -> name;
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

    private static final class RecordingAllocator implements TeamAllocator {
        private final List<String> calls = new ArrayList<>();
        private final AtomicLong sequence = new AtomicLong();

        @Override
        public TeamHandle claim(Player viewer, String purpose, String entry, TeamStyle style) {
            calls.add("claim " + viewer.getName() + " " + purpose + " " + entry);
            return new TeamHandle(viewer.getUniqueId(), purpose, entry,
                "gls_" + sequence.incrementAndGet());
        }

        @Override
        public void update(TeamHandle handle, TeamStyle style) {
            calls.add("update " + name(handle.viewerId()) + " " + handle.purpose() + " " + handle.entry());
        }

        @Override
        public void release(TeamHandle handle) {
            calls.add("release " + name(handle.viewerId()) + " " + handle.purpose() + " " + handle.entry());
        }

        @Override
        public void releaseAll(Player viewer, String purpose) {
            calls.add("releaseAll " + viewer.getName() + " " + purpose);
        }

        @Override
        public void forget(UUID viewerId) {
            calls.add("forget " + viewerId);
        }

        private static String name(UUID viewerId) {
            for (String candidate : List.of("one", "two", "viewer", "near", "far", "subject")) {
                if (UUID.nameUUIDFromBytes(candidate.getBytes(StandardCharsets.UTF_8)).equals(viewerId)) {
                    return candidate;
                }
            }
            return viewerId.toString();
        }
    }
}
