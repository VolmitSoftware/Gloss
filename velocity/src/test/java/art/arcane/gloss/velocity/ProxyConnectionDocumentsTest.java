package art.arcane.gloss.velocity;

import art.arcane.gloss.expr.ExprEvaluator;
import art.arcane.gloss.expr.ExpressionScope;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

final class ProxyConnectionDocumentsTest {
    @TempDir
    Path directory;

    @Test
    void bundledDefaultEnablesEverySectionOnTheNetworkAudience() throws IOException {
        ProxyDocuments.seed(directory);
        ProxyConnectionDocuments.Document document = ProxyConnectionDocuments.load(directory);

        assertTrue(document.join().enabled());
        assertTrue(document.switched().enabled());
        assertTrue(document.leave().enabled());
        assertEquals("network", document.join().audience());
        assertEquals("network", document.switched().audience());
        assertEquals("network", document.leave().audience());
        assertTrue(document.join().presentation().text().contains("$player"));
        assertTrue(document.switched().presentation().text().contains("$to"));
        assertTrue(document.join().variants().isEmpty());
        assertTrue(ExprEvaluator.bool(document.show(), variables(Map.of())));
    }

    @Test
    void missingFileAndRetiredSchemaBothLoadDisabled() throws IOException {
        assertSame(ProxyConnectionDocuments.DISABLED, ProxyConnectionDocuments.load(directory));

        Files.writeString(directory.resolve("connections.json"), """
            {"schemaVersion":2,"join":{"presentation":{"text":"&aignored"}}}
            """);

        assertSame(ProxyConnectionDocuments.DISABLED, ProxyConnectionDocuments.load(directory));
    }

    @Test
    void aPresentSectionDefaultsToEnabledAndAMissingSectionIsDisabled() throws IOException {
        Files.writeString(directory.resolve("connections.json"), """
            {"schemaVersion":1,"join":{"presentation":{"text":"&a$player"}}}
            """);
        ProxyConnectionDocuments.Document document = ProxyConnectionDocuments.load(directory);

        assertTrue(document.join().enabled());
        assertEquals("network", document.join().audience());
        assertEquals("&a$player", document.join().presentation().text());
        assertSame(ProxyConnectionDocuments.Section.DISABLED, document.switched());
        assertSame(ProxyConnectionDocuments.Section.DISABLED, document.leave());
    }

    @Test
    void sectionsHonourTheirOwnEnabledShowAndAudience() throws IOException {
        Files.writeString(directory.resolve("connections.json"), """
            {"schemaVersion":1,"show":"server.online > 1",
             "join":{"enabled":false,"presentation":{"text":"&aoff"}},
             "leave":{"show":"subject.present","audience":"server","presentation":{"text":"&c$player"}}}
            """);
        ProxyConnectionDocuments.Document document = ProxyConnectionDocuments.load(directory);

        assertFalse(document.join().enabled());
        assertTrue(document.leave().enabled());
        assertEquals("server", document.leave().audience());
        assertTrue(document.leave().presentation().text().contains("$player"));
        assertTrue(ExprEvaluator.bool(document.show(), variables(Map.of("server.online", 2.0D))));
        assertFalse(ExprEvaluator.bool(document.show(), variables(Map.of("server.online", 1.0D))));
        assertTrue(ExprEvaluator.bool(document.leave().show(), variables(Map.of("subject.present", true))));
    }

    @Test
    void variantsAreSortedByDescendingPriority() throws IOException {
        Files.writeString(directory.resolve("connections.json"), """
            {"schemaVersion":1,
             "join":{"presentation":{"text":"&7base"},
              "variants":[{"priority":5,"when":"subject.present","presentation":{"text":"&7low"}},
                          {"priority":50,"when":"subject.present","presentation":{"text":"&6high"}}]}}
            """);
        ProxyConnectionDocuments.Document document = ProxyConnectionDocuments.load(directory);

        assertEquals(2, document.join().variants().size());
        assertEquals(50, document.join().variants().getFirst().priority());
        assertEquals("&6high", document.join().variants().getFirst().presentation().text());
        assertEquals("&7low", document.join().variants().getLast().presentation().text());
    }

    @Test
    void connectionTokensAndExpressionsAreAcceptedInSectionText() throws IOException {
        Files.writeString(directory.resolve("connections.json"), """
            {"schemaVersion":1,
             "switch":{"presentation":{"text":"$player: $from -> $to ({{ connection.to }})"}}}
            """);
        ProxyConnectionDocuments.Document document = ProxyConnectionDocuments.load(directory);

        assertEquals("$player: $from -> $to ({{ connection.to }})", document.switched().presentation().text());
    }

    @Test
    void unknownAudiencesAndBrokenTextAreRejected() throws IOException {
        Files.writeString(directory.resolve("connections.json"), """
            {"schemaVersion":1,"join":{"audience":"everyone","presentation":{"text":"&ahi"}}}
            """);
        assertThrows(IllegalArgumentException.class, () -> ProxyConnectionDocuments.load(directory));

        Files.writeString(directory.resolve("connections.json"), """
            {"schemaVersion":1,"join":{"presentation":{"text":"{{ nonsense.variable }}"}}}
            """);
        assertThrows(IllegalArgumentException.class, () -> ProxyConnectionDocuments.load(directory));

        Files.writeString(directory.resolve("connections.json"), """
            {"schemaVersion":1,"join":{"show":"nonsense.variable","presentation":{"text":"&ahi"}}}
            """);
        assertThrows(IllegalArgumentException.class, () -> ProxyConnectionDocuments.load(directory));
    }

    private static ExpressionScope variables(Map<String, Object> values) {
        return new ExpressionScope() {
            @Override
            public Object variable(String name) {
                return values.get(name);
            }

            @Override
            public Object call(String name, List<Object> arguments) {
                return null;
            }
        };
    }
}
