package art.arcane.gloss.chat;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ChannelDocTest {
    private static final String GLOBAL = """
        {
          "schemaVersion": 1, "revision": 1,
          "show": "true",
          "channel": { "name": "global", "aliases": ["g"], "default": true, "scope": "global",
                       "radius": 0, "permission": "", "priority": 0, "cooldownTicks": 0 },
          "format": "&f{{ sender.name }}&8: &f{{ message }}",
          "card": ["&6{{ sender.name }}"],
          "mentions": { "enabled": true, "pattern": "@{name}", "render": "&e@{{ mention.name }}&r",
                        "sound": "minecraft:entity.experience_orb.pickup", "permission": "gloss.chat.mention" },
          "items": { "enabled": true, "token": "[item]", "permission": "gloss.chat.item" },
          "links": { "enabled": true, "render": "&9&n{{ link.host }}" },
          "filters": [ { "match": "(?i)\\\\bbadword\\\\b", "replace": "***" } ],
          "throttle": { "repeatWindowTicks": 100, "maxRepeats": 1, "minIntervalTicks": 10 },
          "variants": [ { "id": "staff", "priority": 10, "when": "hasPermission('sender', 'server.staff')",
                          "format": "&c[Staff] &f{{ sender.name }}&8: &f{{ message }}" } ]
        }
        """;

    @Test
    void theSpecExampleParsesWithEveryBlockIntact() {
        ChannelDoc doc = ChannelDoc.parse("global.json", GLOBAL);

        assertEquals("global", doc.channel().name());
        assertEquals(java.util.List.of("g"), doc.channel().aliases());
        assertTrue(doc.channel().defaultChannel());
        assertEquals(ChannelDoc.Scope.GLOBAL, doc.channel().scope());
        assertEquals(1, doc.filters().size());
        assertEquals("***", doc.filters().getFirst().replace());
        assertEquals(100, doc.throttle().repeatWindowTicks());
        assertEquals(1, doc.variants().size());
        assertEquals("staff", doc.variants().getFirst().id());
        assertEquals("[item]", doc.items().token());
    }

    @Test
    void absentBlocksTakeTheDocumentedDefaults() {
        ChannelDoc doc = ChannelDoc.parse("bare.json",
            "{\"schemaVersion\":1,\"revision\":1,\"channel\":{\"name\":\"bare\"},\"format\":\"{{ message }}\"}");

        assertEquals(ChannelDoc.Scope.GLOBAL, doc.channel().scope());
        assertFalse(doc.channel().defaultChannel());
        assertEquals("@{name}", doc.mentions().pattern());
        assertEquals("gloss.chat.mention", doc.mentions().permission());
        assertEquals("gloss.chat.item", doc.items().permission());
        assertTrue(doc.card().isEmpty());
        assertTrue(doc.filters().isEmpty());
    }

    @Test
    void anInvalidFilterPatternIsRefusedByItsIndex() {
        IllegalArgumentException failure = assertThrows(IllegalArgumentException.class,
            () -> ChannelDoc.parse("bad.json", """
                {"schemaVersion":1,"revision":1,"channel":{"name":"bad"},"format":"{{ message }}",
                 "filters":[{"match":"ok","replace":""},{"match":"(unclosed","replace":""}]}
                """));

        assertTrue(failure.getMessage().contains("filter 1"), failure.getMessage());
    }

    @Test
    void aRadiusScopeWithoutARadiusIsRefused() {
        IllegalArgumentException failure = assertThrows(IllegalArgumentException.class,
            () -> ChannelDoc.parse("local.json",
                "{\"schemaVersion\":1,\"revision\":1,\"channel\":{\"name\":\"local\",\"scope\":\"radius\"},"
                    + "\"format\":\"{{ message }}\"}"));

        assertTrue(failure.getMessage().contains("radius"), failure.getMessage());
    }

    @Test
    void aPermissionScopeWithoutAPermissionIsRefused() {
        assertThrows(IllegalArgumentException.class,
            () -> ChannelDoc.parse("staff.json",
                "{\"schemaVersion\":1,\"revision\":1,\"channel\":{\"name\":\"staff\",\"scope\":\"permission\"},"
                    + "\"format\":\"{{ message }}\"}"));
    }

    @Test
    void aDuplicateVariantIdIsRefused() {
        assertThrows(IllegalArgumentException.class,
            () -> ChannelDoc.parse("dup.json", """
                {"schemaVersion":1,"revision":1,"channel":{"name":"dup"},"format":"{{ message }}",
                 "variants":[{"id":"a","when":"true","format":"x"},{"id":"a","when":"true","format":"y"}]}
                """));
    }

    @Test
    void aChannelNameOutsideTheAllowedShapeIsRefused() {
        assertThrows(IllegalArgumentException.class,
            () -> ChannelDoc.parse("bad.json",
                "{\"schemaVersion\":1,\"revision\":1,\"channel\":{\"name\":\"Global Chat\"},\"format\":\"x\"}"));
    }

    @Test
    void aBlankFormatIsRefused() {
        assertThrows(IllegalArgumentException.class,
            () -> ChannelDoc.parse("bad.json",
                "{\"schemaVersion\":1,\"revision\":1,\"channel\":{\"name\":\"bad\"},\"format\":\"\"}"));
    }

    @Test
    void anotherSchemaVersionIsRefused() {
        assertThrows(IllegalArgumentException.class,
            () -> ChannelDoc.parse("bad.json",
                "{\"schemaVersion\":2,\"revision\":1,\"channel\":{\"name\":\"bad\"},\"format\":\"x\"}"));
    }

    @Test
    void partyResolvesToGlobalUntilAPartySpiExists() {
        ChannelDoc doc = ChannelDoc.parse("party.json",
            "{\"schemaVersion\":1,\"revision\":1,\"channel\":{\"name\":\"party\",\"scope\":\"party\"},"
                + "\"format\":\"{{ message }}\"}");

        assertEquals(ChannelDoc.Scope.GLOBAL, doc.channel().scope());
    }
}
