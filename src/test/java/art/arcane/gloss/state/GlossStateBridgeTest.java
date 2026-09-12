package art.arcane.gloss.state;

import art.arcane.gloss.api.GlossStateProvider;
import art.arcane.gloss.api.GlossStateSpec;
import org.bukkit.plugin.Plugin;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.lang.reflect.Proxy;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class GlossStateBridgeTest {
    @TempDir
    Path folder;

    private static Plugin plugin(String name) {
        return (Plugin) Proxy.newProxyInstance(Plugin.class.getClassLoader(), new Class<?>[]{Plugin.class},
            (proxy, method, args) -> switch (method.getName()) {
                case "getName" -> name;
                case "hashCode" -> name.hashCode();
                case "equals" -> proxy == args[0];
                case "toString" -> name;
                default -> throw new UnsupportedOperationException(method.getName());
            });
    }

    @Test
    void pluginsDeclareReadWriteAndEmitThroughTheProvider() {
        StateStore store = new StateStore(folder, Runnable::run, (player, task) -> task.run());
        store.declare(StateTestSupport.declarationsByDocument());
        List<String> emitted = new ArrayList<>();
        GlossStateProvider provider = new GlossStateBridge(store, (name, args) -> emitted.add(name + args));
        store.beginLoad(StateTestSupport.PLAYER);

        provider.declare(plugin("Quests"), List.of(new GlossStateSpec("quests.done", "player", "number", 0),
            new GlossStateSpec("season", "global", "string", "spring")));
        provider.set(StateTestSupport.PLAYER, "quests.done", 2);
        provider.setGlobal("season", "summer");
        provider.emit("quest.complete", Map.of("quest", "mill"));

        assertEquals(2.0D, provider.get(StateTestSupport.PLAYER, "quests.done"));
        assertEquals(0.0D, provider.get(StateTestSupport.PLAYER, "visits"));
        assertEquals("summer", provider.global("season"));
        assertEquals(List.of("quest.complete{quest=mill}"), emitted);
        assertThrows(IllegalStateException.class, () -> provider.declare(plugin("Other"),
            List.of(new GlossStateSpec("season", "global", "number", 1))));
        assertThrows(IllegalArgumentException.class, () -> provider.set(StateTestSupport.PLAYER, "season", "x"));
    }
}
