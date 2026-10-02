package art.arcane.gloss.drop;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class DropShowConfigTest {
    @Test
    void labelVisibilityAcceptsBooleansAndExpressions() {
        RealDropSettingsDoc hidden = parse("false");
        assertFalse(hidden.presentation().labels().show().isAlwaysVisible());
        assertFalse(hidden.presentation().labels().show().isDynamic());
        RealDropSettingsDoc dynamic = parse("\"world.time > 12000\"");
        assertTrue(dynamic.presentation().labels().show().isDynamic());
        assertEquals("world.time > 12000", dynamic.toConfig(true).labels().show().expression());
        assertTrue(dynamic.presentation().labels().preserveCustomNames());
    }

    @Test
    void invalidVisibilityFailsDuringParsing() {
        assertThrows(IllegalArgumentException.class, () -> parse("\"42\""));
    }

    private static RealDropSettingsDoc parse(String show) {
        return RealDropSettingsDoc.parse("default.json", "{\"schemaVersion\":4,\"revision\":1,"
            + "\"presentation\":{\"labels\":{\"show\":" + show + "}}}");
    }
}
