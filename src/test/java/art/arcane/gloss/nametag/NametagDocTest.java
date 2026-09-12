package art.arcane.gloss.nametag;

import art.arcane.gloss.util.common.TeamAllocator;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class NametagDocTest {
    @Test
    void theWorkedExampleParsesWithItsVariants() {
        NametagDoc doc = NametagDoc.parse("default.json", """
            { "schemaVersion": 1, "revision": 1, "show": "true",
              "select": { "priority": 0, "when": "true" },
              "presentation": { "prefix": "&7[{{ subject.group }}] ", "suffix": "", "color": "white",
                                "nameTagVisibility": "always", "collision": "always" },
              "variants": [
                { "id": "staff", "priority": 10, "when": "hasPermission('subject', 'server.staff')",
                  "presentation": { "prefix": "&c[Staff] ", "suffix": " &c*", "color": "red",
                                    "nameTagVisibility": "always", "collision": "never" } } ] }
            """);

        assertEquals("&7[{{ subject.group }}] ", doc.presentation().prefix());
        assertEquals(1, doc.variants().size());
        assertEquals("red", doc.variants().get(0).presentation().color());
        assertEquals("never", doc.variants().get(0).presentation().collision());
    }

    @Test
    void theOptionalPresentationFieldsFallBackToAPlainTag() {
        NametagDoc doc = NametagDoc.parse("a.json", """
            { "schemaVersion": 1, "revision": 1, "presentation": { "prefix": "&7" } }
            """);

        assertEquals("", doc.presentation().suffix());
        assertEquals("white", doc.presentation().color());
        assertEquals("always", doc.presentation().nameTagVisibility());
        assertEquals("always", doc.presentation().collision());
        assertEquals(NametagDoc.Selection.NEVER, doc.select());
    }

    @Test
    void anUnknownVisibilityOrCollisionRuleIsRefused() {
        assertThrows(IllegalArgumentException.class, () -> NametagDoc.parse("bad.json", """
            { "schemaVersion": 1, "revision": 1, "presentation": { "nameTagVisibility": "sometimes" } }
            """));
        assertThrows(IllegalArgumentException.class, () -> NametagDoc.parse("bad.json", """
            { "schemaVersion": 1, "revision": 1, "presentation": { "collision": "bounce" } }
            """));
    }

    @Test
    void aPresentationMapsOntoTheAllocatorStyle() {
        NametagDoc.Presentation presentation = new NametagDoc.Presentation("&7[VIP] ", " *", "red",
            "hide_for_other_teams", "never");

        TeamAllocator.TeamStyle style = presentation.style("[VIP] ", " *");

        assertEquals("[VIP] ", style.prefix());
        assertEquals(TeamAllocator.NameTagVisibility.HIDE_FOR_OTHER_TEAMS, style.nameTagVisibility());
        assertEquals(TeamAllocator.CollisionRule.NEVER, style.collisionRule());
        assertEquals("red", style.color());
    }

    @Test
    void duplicateVariantIdsAreRefused() {
        assertThrows(IllegalArgumentException.class, () -> NametagDoc.parse("bad.json", """
            { "schemaVersion": 1, "revision": 1, "presentation": {},
              "variants": [ { "id": "a", "priority": 1, "when": "true", "presentation": {} },
                            { "id": "a", "priority": 2, "when": "true", "presentation": {} } ] }
            """));
    }

    @Test
    void anUnsupportedSchemaVersionIsRefused() {
        assertThrows(IllegalArgumentException.class, () -> NametagDoc.parse("bad.json", """
            { "schemaVersion": 2, "revision": 1, "presentation": {} }
            """));
    }

    @Test
    void theShippedDefaultIsInertUntilAnOperatorSelectsIt() {
        NametagDoc doc = NametagDoc.parse("default.json", shipped());

        assertEquals("false", doc.select().when());
        assertEquals(1, doc.variants().size());
    }

    private static String shipped() {
        try (InputStream stream = NametagDocTest.class.getResourceAsStream("/defaults/nametags/default.json")) {
            return new String(stream.readAllBytes(), StandardCharsets.UTF_8);
        } catch (IOException failure) {
            throw new IllegalStateException(failure);
        }
    }
}
