package art.arcane.gloss.condition;

import art.arcane.gloss.expr.ExprRoleSnapshot;
import art.arcane.gloss.expr.ExprScope;
import art.arcane.gloss.expr.ExprVariableContext;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Player;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Proxy;
import java.util.ArrayDeque;
import java.util.Collections;
import java.util.HashMap;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import java.util.Queue;
import java.util.Set;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class RoleSnapshotStoreTest {
    @Test
    void capturesOnlyDemandedFieldsOnOwnerAndKeepsPublishedViewsStable() {
        Harness runtime = new Harness();
        RoleSnapshotStore store = new RoleSnapshotStore(runtime, () -> 16);
        Player player = player(UUID.randomUUID());
        runtime.values.put("subject.health", 20.0D);
        store.refresh(player, Set.of("subject.health"));
        assertEquals(0, runtime.reads);
        runtime.drain();
        ExprRoleSnapshot first = store.view(player);
        assertEquals(20.0D, first.variable("subject.health"));
        assertEquals(1, runtime.reads);
        runtime.values.put("subject.health", 3.0D);
        store.refresh(player, Set.of("subject.health"));
        runtime.drain();
        assertEquals(20.0D, first.variable("subject.health"));
        assertEquals(3.0D, store.view(player).variable("subject.health"));
    }

    @Test
    void dynamicProviderArgumentsAreSampledOnceOnOwner() {
        Harness runtime = new Harness();
        RoleSnapshotStore store = new RoleSnapshotStore(runtime, () -> 16);
        Player player = player(UUID.randomUUID());
        ExprRoleSnapshot snapshot = store.view(player);
        List<Object> arguments = List.of("subject", "rank." + "builder");
        assertThrows(RoleSnapshotPendingException.class, () -> snapshot.call("hasPermission", arguments));
        assertThrows(RoleSnapshotPendingException.class, () -> snapshot.call("hasPermission", arguments));
        assertEquals(1, runtime.tasks.size());
        assertEquals(0, runtime.calls);
        runtime.drain();
        assertEquals(true, store.view(player).call("hasPermission", arguments));
        assertEquals(1, runtime.calls);
        assertEquals(arguments, runtime.arguments);
    }

    @Test
    void retirementAndReconnectDiscardQueuedCaptures() {
        Harness runtime = new Harness();
        RoleSnapshotStore store = new RoleSnapshotStore(runtime, () -> 16);
        UUID id = UUID.randomUUID();
        Player oldPlayer = player(id);
        store.refresh(oldPlayer, Set.of("subject.health"));
        ExprRoleSnapshot old = store.view(oldPlayer);
        store.forget(id);
        runtime.drain();
        assertEquals(0, runtime.reads);
        assertThrows(RoleSnapshotPendingException.class, () -> old.variable("subject.health"));
        Player newPlayer = player(id);
        runtime.retired.add(oldPlayer);
        runtime.values.put("subject.health", 7.0D);
        store.refresh(newPlayer, Set.of("subject.health"));
        runtime.drain();
        assertEquals(7.0D, store.view(newPlayer).variable("subject.health"));
        assertThrows(RoleSnapshotPendingException.class, () -> old.variable("subject.health"));
        assertThrows(RoleSnapshotPendingException.class, () -> store.view(oldPlayer));
        assertEquals(7.0D, store.view(newPlayer).variable("subject.health"));
    }

    @Test
    void replacementEntityWithSameUuidRetiresPreviousEntryWithoutExplicitForget() {
        Harness runtime = new Harness();
        RoleSnapshotStore store = new RoleSnapshotStore(runtime, () -> 16);
        UUID id = UUID.randomUUID();
        Player oldPlayer = player(id);
        store.refresh(oldPlayer, Set.of("subject.health"));
        ExprRoleSnapshot old = store.view(oldPlayer);
        runtime.retired.add(oldPlayer);
        Player replacement = player(id);
        runtime.values.put("subject.health", 9.0D);
        store.refresh(replacement, Set.of("subject.health"));
        runtime.drain();
        assertEquals(1, runtime.reads);
        assertEquals(9.0D, store.view(replacement).variable("subject.health"));
        assertEquals(9.0D, store.view(replacement).variable("subject.health"));
        assertThrows(RoleSnapshotPendingException.class, () -> old.variable("subject.health"));
        assertThrows(RoleSnapshotPendingException.class, () -> store.view(oldPlayer));
        assertEquals(9.0D, store.view(replacement).variable("subject.health"));
    }

    @Test
    void legacyForeignRoleAccessIsGuardedAndSnapshotAccessWorks() {
        Harness runtime = new Harness();
        RoleSnapshotStore store = new RoleSnapshotStore(runtime, () -> 16);
        Player player = player(UUID.randomUUID());
        runtime.values.put("subject.health", 8.0D);
        store.refresh(player, Set.of("subject.health"));
        runtime.drain();
        ExprVariableContext context = new ExprVariableContext(null, player, null, null,
            Map.of("subject", store.view(player)));
        assertEquals(8.0D, context.roleValue("subject", "health"));
        assertThrows(IllegalStateException.class, context::subject);
        runtime.owner = true;
        assertEquals(player, context.subject());
    }

    @Test
    void pendingSamplesDoNotBecomeFalseConditions() {
        ExprScope scope = new ExprScope() {
            @Override
            public Object variable(String name) {
                throw new RoleSnapshotPendingException();
            }

            @Override
            public Object call(String name, List<Object> arguments) {
                return null;
            }
        };
        assertThrows(RoleSnapshotPendingException.class,
            () -> ConditionCompiler.compile("subject.health > 0").matches(scope));
    }

    @Test
    void limitsDistinctDemandWithoutEvictingPublishedReads() {
        Harness runtime = new Harness();
        RoleSnapshotStore store = new RoleSnapshotStore(runtime, () -> 1);
        Player player = player(UUID.randomUUID());
        runtime.values.put("subject.health", 20.0D);
        store.refresh(player, Set.of("subject.health"));
        runtime.drain();
        ExprRoleSnapshot snapshot = store.view(player);
        assertThrows(IllegalStateException.class, () -> snapshot.variable("subject.level"));
        assertEquals(20.0D, snapshot.variable("subject.health"));
        assertFalse(snapshot.ownsCurrentThread());
    }

    private static Player player(UUID id) {
        return (Player) Proxy.newProxyInstance(Player.class.getClassLoader(), new Class<?>[]{Player.class},
            (proxy, method, arguments) -> switch (method.getName()) {
                case "getUniqueId" -> id;
                case "equals" -> proxy == arguments[0];
                case "hashCode" -> System.identityHashCode(proxy);
                default -> throw new AssertionError("Unexpected live entity access: " + method.getName());
            });
    }

    private static final class Harness implements RoleSnapshotStore.Runtime {
        private final Queue<Runnable> tasks = new ArrayDeque<>();
        private final Map<String, Object> values = new HashMap<>();
        private final Set<Entity> retired = Collections.newSetFromMap(new IdentityHashMap<>());
        private boolean owner;
        private int reads;
        private int calls;
        private List<Object> arguments;

        @Override
        public boolean dispatch(Entity entity, Runnable task, Runnable retired) {
            tasks.add(task);
            return true;
        }

        @Override
        public ExprScope scope(Entity entity) {
            assertTrue(owner);
            return new ExprScope() {
                @Override
                public Object variable(String name) {
                    assertTrue(owner);
                    reads++;
                    return values.get(name);
                }

                @Override
                public Object call(String name, List<Object> supplied) {
                    assertTrue(owner);
                    calls++;
                    arguments = supplied;
                    return true;
                }
            };
        }

        @Override
        public boolean active(Entity entity) {
            return true;
        }

        @Override
        public boolean owns(Entity entity) {
            return owner;
        }

        @Override
        public boolean current(Entity entity) {
            return !retired.contains(entity);
        }

        private void drain() {
            owner = true;
            try {
                while (!tasks.isEmpty()) {
                    tasks.remove().run();
                }
            } finally {
                owner = false;
            }
        }
    }
}
