package art.arcane.gloss.tab;

import art.arcane.gloss.condition.BoundedConditionErrorCallback;
import art.arcane.gloss.expr.ExprFunctions;
import art.arcane.gloss.expr.ExprScope;
import org.bukkit.entity.Player;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Proxy;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Sorting is one list-order update per changed player, broadcast when the weight cannot see the
 * viewer and sent per viewer when it can.
 */
class TablistSortServiceTest {
    private final List<String> sends = new ArrayList<>();
    private final Map<UUID, String> names = new LinkedHashMap<>();
    private final TablistSortService sorts = new TablistSortService((viewers, orders) -> {
        List<String> viewerNames = new ArrayList<>();
        for (Player viewer : viewers) {
            viewerNames.add(viewer.getName());
        }
        Map<String, Integer> named = new LinkedHashMap<>();
        orders.forEach((subject, order) -> named.put(names.get(subject), order));
        sends.add(viewerNames + " " + named);
    });

    @Test
    void aViewerIndependentWeightIsCapturedOnceAndDeliveredToEachViewer() {
        Player admin = player("admin");
        Player member = player("member");

        pass(compile("subject.op ? 1000 : 0"), List.of(admin, member), this::scope, silent());

        assertEquals(List.of("[admin] {admin=1000, member=0}", "[member] {admin=1000, member=0}"), sends);
    }

    @Test
    void layoutsReadThePublishedOrderWithoutEvaluatingAnotherScope() {
        Player admin = player("admin");
        Player member = player("member");
        assertEquals(0, sorts.publishedOrder(member.getUniqueId(), admin.getUniqueId(), false));
        pass(compile("subject.op ? 1000 : 0"), List.of(admin, member), this::scope, silent());
        assertEquals(1000, sorts.publishedOrder(member.getUniqueId(), admin.getUniqueId(), false));
        sorts.clear();
        assertEquals(0, sorts.publishedOrder(member.getUniqueId(), admin.getUniqueId(), false));
    }

    @Test
    void layoutsReadTheObserversPublishedOrder() {
        Player admin = player("admin");
        Player member = player("member");
        pass(compile("viewer.op ? 1000 : 0"), List.of(admin, member),
            this::scope, silent());
        assertEquals(1000, sorts.publishedOrder(admin.getUniqueId(), admin.getUniqueId(), true));
        assertEquals(0, sorts.publishedOrder(member.getUniqueId(), admin.getUniqueId(), true));
    }

    @Test
    void onlyChangedOrdersAreSentOnTheNextPass() {
        Player admin = player("admin");
        Player member = player("member");
        TablistRuntime runtime = compile("subject.op ? 1000 : 0");

        pass(runtime, List.of(admin, member), this::scope, silent());
        sends.clear();
        pass(runtime, List.of(admin, member), this::scope, silent());

        assertEquals(List.of(), sends, "an unchanged order must not be re-sent");
    }

    @Test
    void aChangedWeightResendsOnlyThatSubject() {
        Player admin = player("admin");
        Player member = player("member");
        TablistRuntime runtime = compile("subject.level * 10");
        levels.put("admin", 1.0D);
        levels.put("member", 2.0D);

        pass(runtime, List.of(admin, member), this::scope, silent());
        sends.clear();
        levels.put("member", 5.0D);
        pass(runtime, List.of(admin, member), this::scope, silent());

        assertEquals(List.of("[admin] {member=50}", "[member] {member=50}"), sends);
    }

    @Test
    void aViewerDependentWeightIsSentPerViewer() {
        Player admin = player("admin");
        Player member = player("member");
        TablistRuntime runtime = compile("viewer.op ? 100 : 1");

        pass(runtime, List.of(admin, member), this::scope, silent());

        assertEquals(List.of("[admin] {admin=100, member=100}", "[member] {admin=1, member=1}"), sends);
    }

    @Test
    void theRuntimeReportsWhetherTheWeightCanSeeTheViewer() {
        assertFalse(compile("subject.op ? 1 : 0").sortViewerDependent());
        assertTrue(compile("viewer.op ? 1 : 0").sortViewerDependent());
        assertFalse(compile("subject.level").sortViewerDependent());
    }

    @Test
    void sortingIsSkippedWhenTheBlockIsAbsentOrDisabled() {
        Player admin = player("admin");

        pass(TablistRuntime.compile(TablistDoc.DEFAULTS), List.of(admin), this::scope, silent());

        assertEquals(List.of(), sends);
    }

    @Test
    void aQuitPlayerIsForgottenSoRejoiningResendsItsOrder() {
        Player admin = player("admin");
        TablistRuntime runtime = compile("subject.op ? 1000 : 0");

        pass(runtime, List.of(admin), this::scope, silent());
        sends.clear();
        sorts.forget(admin.getUniqueId());
        pass(runtime, List.of(admin), this::scope, silent());

        assertEquals(List.of("[admin] {admin=1000}"), sends);
    }

    @Test
    void aWeightIsRoundedAndClampedIntoTheProtocolRange() {
        assertEquals(3, TablistSortService.listOrder(2.6D));
        assertEquals(-3, TablistSortService.listOrder(-2.6D));
        assertEquals(Integer.MAX_VALUE, TablistSortService.listOrder(1.0E30D));
        assertEquals(Integer.MIN_VALUE, TablistSortService.listOrder(-1.0E30D));
        assertEquals(0, TablistSortService.listOrder(Double.NaN));
    }

    @Test
    void newlyArrivingViewerReceivesExistingSharedOrdersWithoutSubjectReevaluation() {
        Player admin = player("admin");
        Player reader = player("reader");
        TablistRuntime runtime = compile("subject.op ? 1000 : 0");
        sorts.captureSubject(runtime, admin, scope(admin, admin), silent());
        sorts.applyViewer(runtime, admin, List.of(admin), (viewer, subject) -> {
            throw new AssertionError("Shared weight must use the captured subject result");
        }, silent());
        sends.clear();
        sorts.applyViewer(runtime, reader, List.of(admin), (viewer, subject) -> {
            throw new AssertionError("New viewer must not recapture shared subject weight");
        }, silent());
        assertEquals(List.of("[reader] {admin=1000}"), sends);
        sends.clear();
        sorts.applyViewer(runtime, reader, List.of(admin), this::scope, silent());
        assertTrue(sends.isEmpty());
    }

    @Test
    void providerCallsRemainViewerSpecificEvenWithoutExplicitViewerVariables() {
        assertTrue(compile("hasPermission('subject', 'rank.staff') ? 1 : 0").sortViewerDependent());
    }

    private void pass(TablistRuntime runtime, List<Player> players, TablistSortService.ScopeFactory scopes,
                      BoundedConditionErrorCallback errors) {
        for (Player subject : players) {
            sorts.captureSubject(runtime, subject, scopes.scope(subject, subject), errors);
        }
        for (Player viewer : players) {
            sorts.applyViewer(runtime, viewer, players, scopes, errors);
        }
    }

    private final Map<String, Double> levels = new LinkedHashMap<>();

    private TablistRuntime compile(String weight) {
        return TablistRuntime.compile(new TablistDoc(TablistDoc.CURRENT_SCHEMA_VERSION, 1L, null,
            TablistDoc.HeaderFooter.DEFAULTS, TablistDoc.ListNames.DEFAULTS,
            new TablistDoc.Sort(true, weight), null));
    }

    private ExprScope scope(Player viewer, Player subject) {
        return new TestScope(Map.of(
            "viewer.op", viewer.getName().equals("admin"),
            "subject.op", subject.getName().equals("admin"),
            "subject.level", levels.getOrDefault(subject.getName(), 0.0D)));
    }

    private static BoundedConditionErrorCallback silent() {
        return BoundedConditionErrorCallback.silent();
    }

    private Player player(String name) {
        UUID id = UUID.nameUUIDFromBytes(name.getBytes(java.nio.charset.StandardCharsets.UTF_8));
        names.put(id, name);
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
}
