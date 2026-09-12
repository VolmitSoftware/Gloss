package art.arcane.gloss.nameplate;

import art.arcane.gloss.doc.DocumentEnvelope;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

class NameplateDocTest {
    private static final String SPEC = """
        {
          "schemaVersion": 1, "revision": 1,
          "show": "true",
          "select": { "priority": 0, "when": "true" },
          "presentation": {
            "lines": [
              { "text": "&7[{{ subject.group }}] &f{{ subject.name }}", "show": "true" },
              { "text": "{{ bar(subject.health, subject.maxHealth, 10, '&c|', '&8|') }}",
                "show": "subject.health < subject.maxHealth" }
            ],
            "offset": 0.3,
            "hideSneaking": true,
            "relations": [ { "when": "hasPermission('subject', 'server.staff')", "color": "&c" } ]
          },
          "variants": []
        }
        """;

    @Test
    void parsesTheSpecDocument() {
        NameplateDoc doc = NameplateDoc.parse("default.json", SPEC);

        Assertions.assertEquals(DocumentEnvelope.INITIAL_REVISION, doc.revision());
        Assertions.assertEquals(2, doc.presentation().lines().size());
        Assertions.assertEquals(0.3D, doc.presentation().offset());
        Assertions.assertTrue(doc.presentation().hideSneaking());
        Assertions.assertEquals(1, doc.presentation().relations().size());
    }

    @Test
    void fillsPresentationDefaults() {
        NameplateDoc doc = NameplateDoc.parse("bare.json", """
            { "schemaVersion": 1, "revision": 1 }
            """);

        Assertions.assertEquals(NameplateDoc.DEFAULT_OFFSET, doc.presentation().offset());
        Assertions.assertTrue(doc.presentation().hideSneaking());
        Assertions.assertEquals(0, doc.presentation().lines().size());
        Assertions.assertNotNull(doc.presentation().style());
    }

    @Test
    void refusesDuplicateVariantIds() {
        Assertions.assertThrows(IllegalArgumentException.class, () -> NameplateDoc.parse("bad.json", """
            { "schemaVersion": 1, "revision": 1, "variants": [
              { "id": "staff", "priority": 1, "when": "true" },
              { "id": "staff", "priority": 2, "when": "true" } ] }
            """));
    }

    @Test
    void refusesAnUnsupportedSchemaVersion() {
        IllegalArgumentException failure = Assertions.assertThrows(IllegalArgumentException.class,
            () -> NameplateDoc.parse("bad.json", """
                { "schemaVersion": 9, "revision": 1 }
                """));

        Assertions.assertTrue(DocumentEnvelope.isUnsupportedSchemaVersion(failure));
    }

    @Test
    void refusesAnUnparseableSelectionCondition() {
        Assertions.assertThrows(RuntimeException.class, () -> NameplateDoc.parse("bad.json", """
            { "schemaVersion": 1, "revision": 1, "select": { "priority": 0, "when": "((" } }
            """));
    }

    @Test
    void theShippedDefaultParses() throws Exception {
        String raw = new String(NameplateDocTest.class.getResourceAsStream(
            "/defaults/nameplates/default.json").readAllBytes(), java.nio.charset.StandardCharsets.UTF_8);

        NameplateDoc doc = NameplateDoc.parse("default.json", raw);

        Assertions.assertFalse(doc.presentation().lines().isEmpty());
    }
}
