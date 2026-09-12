package art.arcane.gloss.paper;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The test classpath carries paper-api unrelocated, so the bridge resolves the same classes the
 * plugin's own imports would; what this proves is the runtime-name derivation and the handles.
 */
class ServerAdventureTest {
    @Test
    void resolvesTheServerComponentTypeFromABukkitSignature() {
        assertTrue(ServerAdventure.available());
        Class<?> component = ServerAdventure.componentClass();
        assertNotNull(component);
        assertEquals("Component", component.getSimpleName());
        assertTrue(component.getPackageName().endsWith(".adventure.text"));
    }

    @Test
    void miniMessageAndJsonRoundTripThroughServerHandles() {
        Object component = ServerAdventure.fromMiniMessage("<red>hello</red>");
        assertTrue(ServerAdventure.componentClass().isInstance(component));
        String json = ServerAdventure.toJson(component);
        assertTrue(json.contains("hello"));
        Object again = ServerAdventure.fromJson(json);
        assertEquals(json, ServerAdventure.toJson(again));
    }

    @Test
    void keysResolveThroughTheServerKeyFactory() {
        Object key = ServerAdventure.key("gloss:glyphs");
        assertNotNull(key);
        assertEquals("gloss:glyphs", key.toString());
    }
}
