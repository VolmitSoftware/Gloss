package art.arcane.gloss.state;

import art.arcane.gloss.expr.ExprVariableContext;
import art.arcane.gloss.expr.ExprVariableNamespaces;
import org.bukkit.World;
import org.bukkit.entity.Player;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

class StateNamespaceTest {
    @TempDir
    Path folder;

    @AfterEach
    void cleanUp() {
        ExprVariableNamespaces.global().unregister(StateNamespace.PREFIX);
    }

    @Test
    void stateVariablesResolveByDeclaredScopeThroughTheGlobalRegistry() {
        StateStore store = new StateStore(folder, Runnable::run, (player, task) -> task.run());
        store.declare(StateTestSupport.declarationsByDocument());
        ExprVariableNamespaces.global().register(new StateNamespace(store));
        World world = StateTestSupport.world(StateTestSupport.WORLD);
        Player viewer = StateTestSupport.player(StateTestSupport.PLAYER, world);
        store.beginLoad(StateTestSupport.PLAYER);
        store.set(StateScope.PLAYER, StateTestSupport.PLAYER, "visits", 3);
        store.set(StateScope.WORLD, StateTestSupport.WORLD, "weather", "rain");
        store.set(StateScope.GLOBAL, null, "event", "spring");
        ExprVariableContext withViewer = ExprVariableContext.viewer(viewer);

        assertEquals(3.0D, ExprVariableNamespaces.global().resolve("state.visits", withViewer));
        assertEquals(false, ExprVariableNamespaces.global().resolve("state.welcomed", withViewer));
        assertEquals("rain", ExprVariableNamespaces.global().resolve("state.weather", withViewer));
        assertEquals("spring", ExprVariableNamespaces.global().resolve("state.event", withViewer));
        assertEquals("spring", ExprVariableNamespaces.global().resolve("state.event", ExprVariableContext.empty()));
        assertNull(ExprVariableNamespaces.global().resolve("state.visits", ExprVariableContext.empty()));
        assertNull(ExprVariableNamespaces.global().resolve("state.weather", ExprVariableContext.empty()));
        assertNull(ExprVariableNamespaces.global().resolve("state.unknown", withViewer));
    }

    @Test
    void anUnloadedPlayerReadsTheDeclaredDefault() {
        StateStore store = new StateStore(folder, task -> {
        }, (player, task) -> {
        });
        store.declare(StateTestSupport.declarationsByDocument());
        ExprVariableNamespaces.global().register(new StateNamespace(store));
        Player viewer = StateTestSupport.player(StateTestSupport.OTHER, StateTestSupport.world(StateTestSupport.WORLD));

        assertEquals(0.0D, ExprVariableNamespaces.global().resolve("state.visits", ExprVariableContext.viewer(viewer)));
    }
}
