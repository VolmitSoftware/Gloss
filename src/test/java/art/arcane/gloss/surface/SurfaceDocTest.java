package art.arcane.gloss.surface;

import art.arcane.volmlib.util.hud.HudPriority;
import art.arcane.volmlib.util.hud.HudSlot;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SurfaceDocTest {
    private static final String ARENA = """
        {
          "schemaVersion": 1,
          "revision": 1,
          "surface": "bossbar",
          "show": "viewer.world == 'arena'",
          "select": { "priority": 10, "when": "metric('arena.active', 0) > 0" },
          "presentation": {
            "title": "&cWarmup",
            "progress": "clamp(metric('arena.time', 0) / 300, 0, 1)",
            "color": "red",
            "style": "segmented_10",
            "priority": "status",
            "ttlTicks": 40
          },
          "variants": [
            { "id": "final", "priority": 5, "when": "metric('arena.time', 999) < 30",
              "presentation": { "title": "&4&lFINAL", "progress": "1", "color": "purple", "style": "solid", "priority": "progress", "ttlTicks": 40 } }
          ]
        }
        """;

    @Test
    void theWorkedBossBarExampleParsesWithItsVariant() {
        SurfaceDoc doc = SurfaceDoc.parse("arena.json", ARENA);

        assertEquals(SurfaceKind.BOSSBAR, doc.surface());
        assertEquals(10, doc.select().priority());
        assertEquals("&cWarmup", doc.presentation().title());
        assertEquals("segmented_10", doc.presentation().style());
        assertEquals(40, doc.presentation().ttlTicks());
        assertEquals(1, doc.variants().size());
        assertEquals("final", doc.variants().get(0).id());
        assertEquals("purple", doc.variants().get(0).presentation().color());
    }

    @Test
    void anUnknownSurfaceKindIsRefused() {
        assertThrows(IllegalArgumentException.class, () -> SurfaceDoc.parse("bad.json", """
            { "schemaVersion": 1, "revision": 1, "surface": "hud", "presentation": { "text": "x" } }
            """));
    }

    @Test
    void aBossBarWithoutATitleIsRefused() {
        assertThrows(IllegalArgumentException.class, () -> SurfaceDoc.parse("bad.json", """
            { "schemaVersion": 1, "revision": 1, "surface": "bossbar", "presentation": { "progress": "1" } }
            """));
    }

    @Test
    void anActionBarWithoutTextIsRefused() {
        assertThrows(IllegalArgumentException.class, () -> SurfaceDoc.parse("bad.json", """
            { "schemaVersion": 1, "revision": 1, "surface": "actionbar", "presentation": { "slots": ["center"] } }
            """));
    }

    @Test
    void aTitlePresentationGetsTheVanillaFadeDefaults() {
        SurfaceDoc doc = SurfaceDoc.parse("welcome.json", """
            { "schemaVersion": 1, "revision": 1, "surface": "title", "presentation": { "title": "&6Hi" } }
            """);

        assertEquals(10, doc.presentation().fadeInTicks());
        assertEquals(40, doc.presentation().stayTicks());
        assertEquals(10, doc.presentation().fadeOutTicks());
        assertEquals("select", doc.presentation().trigger());
    }

    @Test
    void aProgressExpressionThatDoesNotCompileIsRefused() {
        assertThrows(IllegalArgumentException.class, () -> SurfaceDoc.parse("bad.json", """
            { "schemaVersion": 1, "revision": 1, "surface": "bossbar",
              "presentation": { "title": "x", "progress": "1 +" } }
            """));
    }

    @Test
    void ttlTicksDefaultsToNullAndIsClampedWhenPresent() {
        SurfaceDoc absent = SurfaceDoc.parse("a.json", """
            { "schemaVersion": 1, "revision": 1, "surface": "actionbar", "presentation": { "text": "x" } }
            """);
        SurfaceDoc huge = SurfaceDoc.parse("b.json", """
            { "schemaVersion": 1, "revision": 1, "surface": "actionbar", "presentation": { "text": "x", "ttlTicks": 99999 } }
            """);
        SurfaceDoc tiny = SurfaceDoc.parse("c.json", """
            { "schemaVersion": 1, "revision": 1, "surface": "actionbar", "presentation": { "text": "x", "ttlTicks": 0 } }
            """);

        assertNull(absent.presentation().ttlTicks());
        assertEquals(1200, huge.presentation().ttlTicks());
        assertEquals(1, tiny.presentation().ttlTicks());
    }

    @Test
    void slotsDefaultToCentreAndUnknownNamesAreRefused() {
        SurfaceDoc doc = SurfaceDoc.parse("a.json", """
            { "schemaVersion": 1, "revision": 1, "surface": "actionbar", "presentation": { "text": "x" } }
            """);

        assertEquals(List.of("center"), doc.presentation().slots());
        assertEquals(List.of(HudSlot.CENTER), doc.presentation().hudSlots());
        assertThrows(IllegalArgumentException.class, () -> SurfaceDoc.parse("bad.json", """
            { "schemaVersion": 1, "revision": 1, "surface": "actionbar", "presentation": { "text": "x", "slots": ["middle"] } }
            """));
    }

    @Test
    void anAbsentSelectionNeverSelects() {
        SurfaceDoc doc = SurfaceDoc.parse("a.json", """
            { "schemaVersion": 1, "revision": 1, "surface": "actionbar", "presentation": { "text": "x" } }
            """);

        assertEquals(SurfaceDoc.Selection.NEVER, doc.select());
        assertEquals("false", doc.select().when());
    }

    @Test
    void priorityNamesMapOntoTheCompositorLadderAndTyposAreRefused() {
        assertEquals(HudPriority.AMBIENT, SurfacePriorities.of("ambient"));
        assertEquals(HudPriority.NOTICE, SurfacePriorities.of("notice"));
        assertEquals(HudPriority.STATUS, SurfacePriorities.of("status"));
        assertEquals(HudPriority.PROGRESS, SurfacePriorities.of("progress"));
        assertEquals(HudPriority.INTERACTIVE, SurfacePriorities.of("interactive"));
        assertEquals(HudPriority.MODAL, SurfacePriorities.of("modal"));
        assertEquals(HudPriority.PINNED, SurfacePriorities.of("pinned"));
        assertEquals(HudPriority.STATUS, SurfacePriorities.of(null));
        assertThrows(IllegalArgumentException.class, () -> SurfacePriorities.of("urgent"));
    }

    @Test
    void aBossBarColourOrStyleTypoIsRefused() {
        assertThrows(IllegalArgumentException.class, () -> SurfaceDoc.parse("bad.json", """
            { "schemaVersion": 1, "revision": 1, "surface": "bossbar", "presentation": { "title": "x", "color": "teal" } }
            """));
        assertThrows(IllegalArgumentException.class, () -> SurfaceDoc.parse("bad.json", """
            { "schemaVersion": 1, "revision": 1, "surface": "bossbar", "presentation": { "title": "x", "style": "segmented_7" } }
            """));
    }

    @Test
    void bossBarDefaultsFillColourStyleAndProgress() {
        SurfaceDoc doc = SurfaceDoc.parse("a.json", """
            { "schemaVersion": 1, "revision": 1, "surface": "bossbar", "presentation": { "title": "x" } }
            """);

        assertEquals("white", doc.presentation().color());
        assertEquals("solid", doc.presentation().style());
        assertEquals("1", doc.presentation().progress());
    }

    @Test
    void variantsAreValidatedAgainstTheDocumentSurfaceKind() {
        assertThrows(IllegalArgumentException.class, () -> SurfaceDoc.parse("bad.json", """
            { "schemaVersion": 1, "revision": 1, "surface": "bossbar",
              "presentation": { "title": "x" },
              "variants": [ { "id": "broken", "priority": 1, "when": "true", "presentation": { "progress": "1" } } ] }
            """));
    }

    @Test
    void duplicateVariantIdsAreRefused() {
        assertThrows(IllegalArgumentException.class, () -> SurfaceDoc.parse("bad.json", """
            { "schemaVersion": 1, "revision": 1, "surface": "actionbar",
              "presentation": { "text": "x" },
              "variants": [ { "id": "a", "priority": 1, "when": "true", "presentation": { "text": "y" } },
                            { "id": "a", "priority": 2, "when": "true", "presentation": { "text": "z" } } ] }
            """));
    }

    @Test
    void anUnsupportedSchemaVersionIsRefused() {
        assertThrows(IllegalArgumentException.class, () -> SurfaceDoc.parse("bad.json", """
            { "schemaVersion": 2, "revision": 1, "surface": "actionbar", "presentation": { "text": "x" } }
            """));
    }

    @Test
    void theShippedWelcomeSurfaceIsInertUntilAnOperatorSelectsIt() {
        SurfaceDoc doc = SurfaceDoc.parse("welcome.json", SurfaceDocTest.shipped());

        assertEquals(SurfaceKind.ACTIONBAR, doc.surface());
        assertEquals("false", doc.select().when());
        assertTrue(doc.presentation().text().contains("{{ player.name }}"));
    }

    private static String shipped() {
        try (java.io.InputStream stream = SurfaceDocTest.class.getResourceAsStream("/defaults/surfaces/welcome.json")) {
            return new String(stream.readAllBytes(), java.nio.charset.StandardCharsets.UTF_8);
        } catch (java.io.IOException failure) {
            throw new IllegalStateException(failure);
        }
    }
}
