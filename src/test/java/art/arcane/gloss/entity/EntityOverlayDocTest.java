package art.arcane.gloss.entity;

import org.junit.jupiter.api.Test;

import java.io.InputStream;
import java.nio.charset.StandardCharsets;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

class EntityOverlayDocTest {
    @Test
    void omittedOptionsUseCompleteShippedDefaults() throws Exception {
        EntityOverlayDoc minimal = EntityOverlayDoc.parse("default.json", "{\"schemaVersion\":1,\"revision\":1}");
        try (InputStream input = getClass().getResourceAsStream("/defaults/entity-overlays/default.json")) {
            assertNotNull(input);
            assertEquals(EntityOverlayDoc.DEFAULTS, minimal);
            assertEquals(minimal, EntityOverlayDoc.parse("default.json",
                new String(input.readAllBytes(), StandardCharsets.UTF_8)));
        }
    }

    @Test
    void boundsPreventUnboundedEntityAndSegmentWork() {
        EntityOverlayDoc settings = EntityOverlayDoc.parse("default.json", """
            {"schemaVersion":1,"revision":1,"range":10000,"updateIntervalTicks":0,
             "maxEntitiesPerViewer":9999,"healthSegments":0,"hitHighlightMs":999999,"scale":-1}
            """);
        assertEquals(64, settings.range());
        assertEquals(1, settings.updateIntervalTicks());
        assertEquals(256, settings.maxEntitiesPerViewer());
        assertEquals(1, settings.healthSegments());
        assertEquals(10000L, settings.hitHighlightMs());
        assertEquals(0.1, settings.scale());
    }

    @Test
    void invalidCurrentDocumentsFailValidation() {
        assertThrows(IllegalArgumentException.class, () -> EntityOverlayDoc.parse("default.json", "{}"));
        assertThrows(IllegalArgumentException.class, () -> EntityOverlayDoc.parse("default.json",
            "{\"schemaVersion\":1,\"revision\":1,\"nameFormat\":\"" + "x".repeat(1025) + "\"}"));
    }
}
