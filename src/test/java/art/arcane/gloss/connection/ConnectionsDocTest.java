package art.arcane.gloss.connection;

import art.arcane.gloss.condition.ShowCondition;
import art.arcane.gloss.doc.DocumentParsers;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ConnectionsDocTest {
    @Test
    void shippedDefaultParsesWithBothSectionsOnAndNoVariants() {
        ConnectionsDoc document = parse("""
            {"schemaVersion":1,"revision":1,"show":true,
             "join":{"enabled":true,"show":true,"audience":"network",
              "presentation":{"text":"&a+ &f{{ subject.name }} &7joined"},"variants":[]},
             "leave":{"enabled":true,"show":true,"audience":"network",
              "presentation":{"text":"&c- &f{{ subject.name }} &7left"},"variants":[]}}
            """);

        assertTrue(document.join().active());
        assertTrue(document.leave().active());
        assertSame(ShowCondition.ALWAYS, document.show());
        assertEquals(ConnectionsDoc.AUDIENCE_NETWORK, document.join().audience());
        assertEquals("&a+ &f{{ subject.name }} &7joined", document.join().presentation().text());
        assertTrue(document.join().variants().isEmpty());
    }

    @Test
    void aPresentSectionDefaultsToEnabledAndAMissingSectionIsDisabled() {
        ConnectionsDoc document = parse("""
            {"schemaVersion":1,"revision":1,"join":{"presentation":{"text":"&ahello"}}}
            """);

        assertTrue(document.join().active());
        assertSame(ShowCondition.ALWAYS, document.join().show());
        assertEquals(ConnectionsDoc.AUDIENCE_NETWORK, document.join().audience());
        assertSame(ConnectionsDoc.Section.DISABLED, document.leave());
        assertFalse(document.leave().active());
    }

    @Test
    void theProxySwitchBlockAndSectionAudienceAreReadWithoutAffectingTheServerEdition() {
        ConnectionsDoc document = parse("""
            {"schemaVersion":1,"revision":4,
             "join":{"audience":"SERVER","presentation":{"text":"&ahello"}},
             "switch":{"enabled":true,"audience":"server","presentation":{"text":"&e$player -> $to"}},
             "leave":{"presentation":{"text":"&cbye"}}}
            """);

        assertEquals(ConnectionsDoc.AUDIENCE_SERVER, document.join().audience());
        assertTrue(document.join().active());
        assertTrue(document.leave().active());
        assertEquals(4L, document.revision());
    }

    @Test
    void variantsSortByDescendingPriorityAndCompileTheirConditions() {
        ConnectionsDoc document = parse("""
            {"schemaVersion":1,"revision":1,
             "join":{"presentation":{"text":"&7base"},
              "variants":[{"priority":1,"when":"subject.op","presentation":{"text":"&7low"}},
                          {"priority":90,"when":"viewer.op","presentation":{"text":"&6high"}}]}}
            """);
        List<ConnectionsDoc.Variant> variants = document.join().variants();

        assertEquals(2, variants.size());
        assertEquals(90, variants.getFirst().priority());
        assertEquals("&6high", variants.getFirst().presentation().text());
        assertEquals("&7low", variants.getLast().presentation().text());
    }

    @Test
    void retiredSchemasBadRevisionsUnknownAudiencesAndBrokenConditionsAreRejected() {
        assertThrows(IllegalArgumentException.class, () -> parse("""
            {"schemaVersion":2,"revision":1,"join":{"presentation":{"text":"&ahi"}}}
            """));
        assertThrows(IllegalArgumentException.class, () -> parse("""
            {"schemaVersion":1,"revision":0,"join":{"presentation":{"text":"&ahi"}}}
            """));
        assertThrows(IllegalArgumentException.class, () -> parse("""
            {"schemaVersion":1,"revision":1,"join":{"audience":"everyone","presentation":{"text":"&ahi"}}}
            """));
        assertThrows(IllegalArgumentException.class, () -> parse("""
            {"schemaVersion":1,"revision":1,"join":{"presentation":{"text":"&ahi"},
             "variants":[{"priority":1,"when":"  ","presentation":{"text":"&ahi"}}]}}
            """));
        assertThrows(IllegalArgumentException.class, () -> parse("""
            {"schemaVersion":1,"revision":1,"join":{"show":"subject.op &&","presentation":{"text":"&ahi"}}}
            """));
    }

    @Test
    void defaultsRoundTripThroughTheDocumentSerializer() {
        String raw = DocumentParsers.GSON.toJson(ConnectionsDoc.DEFAULTS);
        ConnectionsDoc document = parse(raw);

        assertEquals(ConnectionsDoc.DEFAULTS, document);
        assertTrue(document.join().active());
        assertTrue(document.leave().active());
    }

    private static ConnectionsDoc parse(String raw) {
        return ConnectionsDoc.parse("connections.json", raw);
    }
}
