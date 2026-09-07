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
        EntityOverlayDoc minimal = EntityOverlayDoc.parse("default.json", "{\"schemaVersion\":2,\"revision\":1}");
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
            {"schemaVersion":2,"revision":1,"range":10000,"updateIntervalTicks":0,
             "maxEntitiesPerViewer":9999,"healthSegments":0,"hitHighlightMs":999999}
            """);
        assertEquals(64, settings.range());
        assertEquals(1, settings.updateIntervalTicks());
        assertEquals(256, settings.maxEntitiesPerViewer());
        assertEquals(1, settings.healthSegments());
        assertEquals(10000L, settings.hitHighlightMs());
        assertEquals(0.75F, settings.style().scaleX());
    }

    @Test
    void admissionBudgetsDefaultAndClamp() {
        assertEquals(1024, EntityOverlayDoc.DEFAULTS.maxActiveOverlays());
        assertEquals(16, EntityOverlayDoc.DEFAULTS.maxEntitiesPerViewer());
        assertEquals(16, EntityOverlayDoc.parse("default.json",
            "{\"schemaVersion\":2,\"revision\":1,\"maxActiveOverlays\":0}").maxActiveOverlays());
        assertEquals(16384, EntityOverlayDoc.parse("default.json",
            "{\"schemaVersion\":2,\"revision\":1,\"maxActiveOverlays\":999999}").maxActiveOverlays());
        assertEquals(1, EntityOverlayDoc.parse("default.json",
            "{\"schemaVersion\":2,\"revision\":1,\"maxEntitiesPerViewer\":0}").maxEntitiesPerViewer());
    }

    @Test
    void invalidCurrentDocumentsFailValidation() {
        assertThrows(IllegalArgumentException.class, () -> EntityOverlayDoc.parse("default.json", "{}"));
        assertInvalid("\"lines\":[{\"id\":\"too-long\",\"text\":\"" + "x".repeat(4097) + "\"}]");
        assertInvalid("\"lines\":[{\"id\":\"same\"},{\"id\":\"same\"}]");
        assertInvalid("\"lines\":[{\"id\":\"invalid id\"}]");
        assertInvalid("\"lines\":[{\"id\":\"bad\",\"type\":\"unknown\"}]");
        assertInvalid("\"lines\":[{\"id\":\"bad\",\"show\":42}]");
        assertInvalid("\"lines\":[{\"id\":\"bad\",\"text\":\"<particles:unclosed>\"}]");
        assertInvalid("\"style\":{\"scaleX\":0}");
        assertInvalid("\"show\":{}");
    }

    @Test
    void lineLimitAndEmptyLayoutAreExplicit() {
        StringBuilder lines = new StringBuilder("\"lines\":[");
        for (int index = 0; index < 65; index++) {
            if (index > 0) {
                lines.append(',');
            }
            lines.append("{\"id\":\"line-").append(index).append("\"}");
        }
        assertInvalid(lines.append(']').toString());
        assertEquals(0, EntityOverlayDoc.parse("default.json",
            "{\"schemaVersion\":2,\"revision\":1,\"lines\":[]}").lines().size());
    }

    private static void assertInvalid(String fields) {
        assertThrows(IllegalArgumentException.class, () -> EntityOverlayDoc.parse("default.json",
            "{\"schemaVersion\":2,\"revision\":1," + fields + "}"));
    }
}
