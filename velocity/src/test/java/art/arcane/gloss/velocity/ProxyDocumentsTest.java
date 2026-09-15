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
    void rejectsInvalidStaticMotdCounts() throws IOException {
        ProxyDocuments.seed(directory);
        for (String value : List.of("invalid", "NaN", "Infinity")) {
            Files.writeString(directory.resolve("motd.json"),
                "{\"schemaVersion\":1,\"entries\":[{\"lines\":[\"Network\"],\"online\":\"" + value + "\"}]}");
            assertThrows(IllegalArgumentException.class, () -> ProxyDocuments.load(directory));
        }
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
