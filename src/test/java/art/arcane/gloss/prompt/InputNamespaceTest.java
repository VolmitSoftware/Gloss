package art.arcane.gloss.prompt;

import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

class InputNamespaceTest {
    @Test
    void nestedActionsRestoreTheOuterAnswerAndReleaseItAfterCompletion() {
        InputNamespace namespace = new InputNamespace();
        InputNamespace.bind(Map.of("value", "outer"), () -> {
            assertEquals("outer", namespace.resolve("value", null));
            InputNamespace.bind(Map.of("value", "inner"), () ->
                assertEquals("inner", namespace.resolve("value", null)));
            assertEquals("outer", namespace.resolve("value", null));
        });
        assertNull(namespace.resolve("value", null));
    }

    @Test
    void failedActionsReleaseTheAnswer() {
        InputNamespace namespace = new InputNamespace();
        assertThrows(IllegalStateException.class, () -> InputNamespace.bind(Map.of("value", "answer"), () -> {
            throw new IllegalStateException("action failed");
        }));
        assertNull(namespace.resolve("value", null));
    }
}
