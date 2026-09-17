package art.arcane.gloss.velocity;

import art.arcane.gloss.expr.ExprEvaluator;
import art.arcane.gloss.expr.ExprException;
import art.arcane.gloss.expr.ExpressionScope;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

final class ProxyDocumentsTest {
    @TempDir
    Path directory;

    @Test
    void defaultsLoadAllThreeSurfacesWithoutOverwritingEdits() throws IOException {
        ProxyDocuments.seed(directory);
        ProxyDocuments.Snapshot snapshot = ProxyDocuments.load(directory);
        assertTrue(snapshot.settings().motd());
        assertTrue(snapshot.settings().tablist());
        assertTrue(snapshot.settings().scoreboards());
        assertEquals(1, snapshot.motd().entries().size());
        assertEquals(1, snapshot.boards().size());
        assertTrue(snapshot.tablist().headerFooter().enabled());
        Files.writeString(directory.resolve("motd.json"), "{}");
        ProxyDocuments.seed(directory);
        assertEquals("{}", Files.readString(directory.resolve("motd.json")));
    }

    @Test
    void seedingWritesEveryBundledDefaultFromTheManifest() throws IOException {
        ProxyDocuments.seed(directory);
        for (String name : List.of("connections.json", "surfaces/welcome.json", "animations/marquee.json",
            "emoji/heart.json")) {
            assertTrue(Files.isRegularFile(directory.resolve(name)), name);
        }
        assertTrue(ProxyDocuments.bundledDefaults().contains("proxy.json"));
        assertFalse(ProxyDocuments.bundledDefaults().contains("manifest.txt"));
        Files.writeString(directory.resolve("connections.json"), "{}");
        ProxyDocuments.seed(directory);
        assertEquals("{}", Files.readString(directory.resolve("connections.json")));
    }

    @Test
    void newFeatureSwitchesDefaultOnAndReadTheirEnabledFlags() throws IOException {
        ProxyDocuments.seed(directory);
        ProxyDocuments.Snapshot seeded = ProxyDocuments.load(directory);
        assertTrue(seeded.settings().surfaces());
        assertTrue(seeded.settings().connections());
        assertTrue(seeded.settings().emoji());
        assertTrue(seeded.settings().animations());
        assertTrue(seeded.motd().links().isEmpty());
        Files.writeString(directory.resolve("proxy.json"), """
            {"schemaVersion":1,"surfaces":{"enabled":false},"connections":{"enabled":false},
             "emoji":{"enabled":false},"animations":{"enabled":false}}
            """);
        ProxyDocuments.Snapshot switched = ProxyDocuments.load(directory);
        assertFalse(switched.settings().surfaces());
        assertFalse(switched.settings().connections());
        assertFalse(switched.settings().emoji());
        assertFalse(switched.settings().animations());
        assertTrue(switched.settings().motd());
    }

    @Test
    void retiredSchemasAreIgnoredAndSnapshotsStayImmutable() throws IOException {
        ProxyDocuments.seed(directory);
        Files.writeString(directory.resolve("boards/default.json"), "{\"schemaVersion\":1}");
        Files.writeString(directory.resolve("motd.json"), "{\"schemaVersion\":9}");
        ProxyDocuments.Snapshot snapshot = ProxyDocuments.load(directory);
        assertTrue(snapshot.boards().isEmpty());
        assertTrue(snapshot.motd().entries().isEmpty());
        assertFalse(ExprEvaluator.bool(snapshot.motd().show(), emptyScope()));
        assertThrows(UnsupportedOperationException.class, () -> snapshot.boards().clear());
    }

    @Test
    void parsesConditionsPriorityAndLineFormats() throws IOException {
        ProxyDocuments.seed(directory);
        Files.writeString(directory.resolve("boards/high.json"), """
            {"schemaVersion":2,"select":{"priority":10,"when":"true"},
            "presentation":{"title":"Higher","lines":[{"text":"Balance","value":"20","format":"fixed"}]},
            "variants":[{"priority":20,"when":"true","presentation":{"title":"Variant"}}]}
            """);
        ProxyDocuments.Snapshot snapshot = ProxyDocuments.load(directory);
        assertEquals("high.json", snapshot.boards().getFirst().id());
        assertEquals("fixed", snapshot.boards().getFirst().presentation().lines().getFirst().format());
        ProxyText text = new ProxyText(null);
        assertEquals("Variant", text.select(snapshot.boards().getFirst(), emptyScope()).title());
    }

    @Test
    void failedReloadDoesNotMutatePreviousSnapshot() throws IOException {
        ProxyDocuments.seed(directory);
        ProxyDocuments.Snapshot old = ProxyDocuments.load(directory);
        Files.writeString(directory.resolve("boards/default.json"), "{ malformed");
        assertThrows(IOException.class, () -> ProxyDocuments.load(directory));
        assertEquals(4, old.boards().getFirst().presentation().lines().size());
    }

    @Test
    void rejectsMalformedEmbeddedTitleAndHeaderExpressionsBeforeActivation() throws IOException {
        ProxyDocuments.seed(directory);
        Files.writeString(directory.resolve("boards/default.json"), """
            {"schemaVersion":2,"presentation":{"title":"{{ server.online + }}"}}
            """);
        assertThrows(ExprException.class, () -> ProxyDocuments.load(directory));
        Files.delete(directory.resolve("boards/default.json"));
        Files.writeString(directory.resolve("tablist.json"), """
            {"schemaVersion":2,"headerFooter":{"presentation":{"header":"{{ missing"}}}
            """);
        assertThrows(IllegalArgumentException.class, () -> ProxyDocuments.load(directory));
    }

    @Test
    void rejectsUnknownVariablesAndFunctionsInConditions() throws IOException {
        ProxyDocuments.seed(directory);
        Files.writeString(directory.resolve("boards/default.json"), """
            {"schemaVersion":2,"select":{"when":"viewer.world == 'lobby'"}}
            """);
        assertThrows(IllegalArgumentException.class, () -> ProxyDocuments.load(directory));
        Files.writeString(directory.resolve("boards/default.json"), """
            {"schemaVersion":2,"show":"unknownFunction('test')"}
            """);
        assertThrows(IllegalArgumentException.class, () -> ProxyDocuments.load(directory));
    }

    @Test
    void countsAcceptFunctionTokensLikeTheServerEditionAndStillRejectBadLiterals() throws IOException {
        ProxyDocuments.seed(directory);
        Files.writeString(directory.resolve("motd.json"), """
            {"schemaVersion":1,"entries":[{"lines":["Network"],"online":"|animation.marquee|","max":"{{ server.maxPlayers }}"}]}
            """);
        ProxyDocuments.MotdEntry entry = ProxyDocuments.load(directory).motd().entries().getFirst();
        assertEquals("|animation.marquee|", entry.online());
        assertEquals("{{ server.maxPlayers }}", entry.max());
        Files.writeString(directory.resolve("motd.json"), """
            {"schemaVersion":1,"entries":[{"lines":["Network"],"online":"lots"}]}
            """);
        assertThrows(NumberFormatException.class, () -> ProxyDocuments.load(directory));
    }

    @Test
    void parsesTheDocumentFaviconAndTreatsBlankAsAbsent() throws IOException {
        ProxyDocuments.seed(directory);
        Files.writeString(directory.resolve("motd.json"),
            "{\"schemaVersion\":1,\"favicon\":\"icons/default.png\",\"entries\":[{\"lines\":[\"Network\"]}]}");
        assertEquals("icons/default.png", ProxyDocuments.load(directory).motd().favicon());
        Files.writeString(directory.resolve("motd.json"),
            "{\"schemaVersion\":1,\"favicon\":\"   \",\"entries\":[{\"lines\":[\"Network\"]}]}");
        assertNull(ProxyDocuments.load(directory).motd().favicon());
        Files.writeString(directory.resolve("motd.json"),
            "{\"schemaVersion\":1,\"entries\":[{\"lines\":[\"Network\"]}]}");
        assertNull(ProxyDocuments.load(directory).motd().favicon());
    }

    @Test
    void rejectsInvalidStaticMotdCounts() throws IOException {
        ProxyDocuments.seed(directory);
        for (String value : List.of("invalid", "NaN", "Infinity")) {
            Files.writeString(directory.resolve("motd.json"),
                "{\"schemaVersion\":1,\"entries\":[{\"lines\":[\"Network\"],\"online\":\"" + value + "\"}]}");
            assertThrows(IllegalArgumentException.class, () -> ProxyDocuments.load(directory));
        }
    }

    @Test
    void parsesServerLinksWithTypesLabelsAndBlankTrimming() throws IOException {
        ProxyDocuments.seed(directory);
        Files.writeString(directory.resolve("motd.json"), """
            {"schemaVersion":1,"entries":[{"lines":["Network"]}],
             "links":[{"type":"WEBSITE","url":"https://example.org"},
                      {"type":"  ","label":"&dDiscord","url":" https://discord.gg/example "},
                      {"type":"report_bug","label":"   ","url":"https://example.org/bugs"}]}
            """);
        List<ProxyDocuments.MotdLink> links = ProxyDocuments.load(directory).motd().links();
        assertEquals(3, links.size());
        assertEquals("website", links.get(0).type());
        assertNull(links.get(0).label());
        assertEquals("https://example.org", links.get(0).url());
        assertFalse(links.get(0).isLabelled());
        assertNull(links.get(1).type());
        assertEquals("&dDiscord", links.get(1).label());
        assertEquals("https://discord.gg/example", links.get(1).url());
        assertTrue(links.get(1).isLabelled());
        assertEquals("report_bug", links.get(2).type());
        assertNull(links.get(2).label());
        assertThrows(UnsupportedOperationException.class, links::clear);
    }

    @Test
    void rejectsUnknownLinkTypesBadUrlsAndLinksWithoutTypeOrLabel() throws IOException {
        ProxyDocuments.seed(directory);
        for (String link : List.of("{\"type\":\"teleport\",\"url\":\"https://example.org\"}",
            "{\"type\":\"website\",\"url\":\"javascript:alert(1)\"}",
            "{\"type\":\"website\",\"url\":\"not a url\"}",
            "{\"type\":\"website\",\"url\":\"https:///bugs\"}",
            "{\"type\":\"website\",\"url\":\"   \"}",
            "{\"type\":\"website\"}",
            "{\"label\":\"   \",\"url\":\"https://example.org\"}")) {
            Files.writeString(directory.resolve("motd.json"),
                "{\"schemaVersion\":1,\"entries\":[{\"lines\":[\"Network\"]}],\"links\":[" + link + "]}");
            assertThrows(IllegalArgumentException.class, () -> ProxyDocuments.load(directory), link);
        }
    }

    @Test
    void refusesMoreThanSixteenLinksAndMalformedLabelTemplates() throws IOException {
        ProxyDocuments.seed(directory);
        StringBuilder many = new StringBuilder();
        for (int index = 0; index < 17; index++) {
            many.append(index == 0 ? "" : ",").append("{\"label\":\"Link ").append(index)
                .append("\",\"url\":\"https://example.org/").append(index).append("\"}");
        }
        Files.writeString(directory.resolve("motd.json"),
            "{\"schemaVersion\":1,\"entries\":[{\"lines\":[\"Network\"]}],\"links\":[" + many + "]}");
        assertThrows(IllegalArgumentException.class, () -> ProxyDocuments.load(directory));
        Files.writeString(directory.resolve("motd.json"), """
            {"schemaVersion":1,"entries":[{"lines":["Network"]}],
             "links":[{"label":"{{ missing","url":"https://example.org"}]}
            """);
        assertThrows(IllegalArgumentException.class, () -> ProxyDocuments.load(directory));
    }

    private static ExpressionScope emptyScope() {
        return new ExpressionScope() {
            @Override
            public Object variable(String name) {
                return null;
            }

            @Override
            public Object call(String name, List<Object> arguments) {
                return null;
            }
        };
    }
}
