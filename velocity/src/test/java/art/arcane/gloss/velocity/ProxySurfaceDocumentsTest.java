package art.arcane.gloss.velocity;

import art.arcane.gloss.expr.ExprEvaluator;
import art.arcane.gloss.expr.ExprException;
import art.arcane.gloss.expr.ExpressionScope;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

final class ProxySurfaceDocumentsTest {
    @TempDir
    Path directory;

    @Test
    void theSeededWelcomeDocumentParsesAndStaysUnselected() throws IOException {
        ProxyDocuments.seed(directory);
        List<ProxySurfaceDocuments.Document> documents = ProxySurfaceDocuments.load(directory);
        assertEquals(1, documents.size());
        ProxySurfaceDocuments.Document welcome = documents.getFirst();
        assertEquals("welcome", welcome.id());
        assertEquals("actionbar", welcome.kind());
        assertEquals(0, welcome.priority());
        assertTrue(ExprEvaluator.bool(welcome.show(), scope(Map.of())));
        assertFalse(ExprEvaluator.bool(welcome.when(), scope(Map.of())));
        assertEquals("&7Welcome, &f{{ player.name }}", welcome.presentation().text());
        assertEquals(40, welcome.presentation().ttlTicks());
        assertTrue(welcome.variants().isEmpty());
    }

    @Test
    void missingDirectoriesAndOtherSchemasLoadNothing() throws IOException {
        assertTrue(ProxySurfaceDocuments.load(directory).isEmpty());
        write("retired.json", """
            {"schemaVersion":2,"surface":"actionbar","presentation":{"text":"Retired"}}
            """);
        assertTrue(ProxySurfaceDocuments.load(directory).isEmpty());
        Files.writeString(directory.resolve("surfaces/notes.txt"), "ignored");
        assertTrue(ProxySurfaceDocuments.load(directory).isEmpty());
    }

    @Test
    void everyKindFillsTheServerEditionDefaults() throws IOException {
        write("bar.json", """
            {"schemaVersion":1,"surface":"actionbar","presentation":{"text":"Hello"}}
            """);
        write("boss.json", """
            {"schemaVersion":1,"surface":"bossbar","presentation":{"title":"Event"}}
            """);
        write("card.json", """
            {"schemaVersion":1,"surface":"title","presentation":{"title":"Welcome"}}
            """);
        Map<String, ProxySurfaceDocuments.Document> documents = byId();

        ProxySurfaceDocuments.Presentation actionBar = documents.get("bar").presentation();
        assertEquals("Hello", actionBar.text());
        assertNull(actionBar.title());
        assertNull(actionBar.progress());
        assertNull(actionBar.ttlTicks());
        assertNull(actionBar.trigger());

        ProxySurfaceDocuments.Presentation bossBar = documents.get("boss").presentation();
        assertEquals("Event", bossBar.title());
        assertNull(bossBar.text());
        assertEquals("white", bossBar.color());
        assertEquals("solid", bossBar.style());
        assertEquals(1.0D, ExprEvaluator.number(bossBar.progress(), scope(Map.of())));
        assertNull(bossBar.stayTicks());

        ProxySurfaceDocuments.Presentation title = documents.get("card").presentation();
        assertEquals("Welcome", title.title());
        assertEquals("", title.subtitle());
        assertEquals(10, title.fadeInTicks());
        assertEquals(40, title.stayTicks());
        assertEquals(10, title.fadeOutTicks());
        assertEquals("select", title.trigger());
        assertNull(title.repeatTicks());
        assertNull(title.progress());
    }

    @Test
    void showDefaultsToTrueAndSelectionDefaultsToNeverSelected() throws IOException {
        write("bar.json", """
            {"schemaVersion":1,"surface":"actionbar","presentation":{"text":"Hello"}}
            """);
        ProxySurfaceDocuments.Document document = ProxySurfaceDocuments.load(directory).getFirst();
        assertTrue(ExprEvaluator.bool(document.show(), scope(Map.of())));
        assertFalse(ExprEvaluator.bool(document.when(), scope(Map.of())));
        assertEquals(0, document.priority());
    }

    @Test
    void repeatTitlesNeverFireFasterThanTheyStay() throws IOException {
        write("slow.json", """
            {"schemaVersion":1,"surface":"title","presentation":{"title":"Tip","stayTicks":60,
             "trigger":"repeat","repeatTicks":5}}
            """);
        write("bare.json", """
            {"schemaVersion":1,"surface":"title","presentation":{"title":"Tip","trigger":"repeat"}}
            """);
        write("wide.json", """
            {"schemaVersion":1,"surface":"title","presentation":{"title":"Tip","stayTicks":20,
             "trigger":"repeat","repeatTicks":600}}
            """);
        write("kept.json", """
            {"schemaVersion":1,"surface":"title","presentation":{"title":"Tip","repeatTicks":600}}
            """);
        Map<String, ProxySurfaceDocuments.Document> documents = byId();
        assertEquals(60, documents.get("slow").presentation().repeatTicks());
        assertEquals(40, documents.get("bare").presentation().repeatTicks());
        assertEquals(600, documents.get("wide").presentation().repeatTicks());
        assertEquals(600, documents.get("kept").presentation().repeatTicks());
        assertEquals("select", documents.get("kept").presentation().trigger());
    }

    @Test
    void ttlFadeAndRepeatValuesAreClamped() throws IOException {
        write("low.json", """
            {"schemaVersion":1,"surface":"title","presentation":{"title":"Tip","ttlTicks":0,
             "fadeInTicks":-5,"stayTicks":-1,"fadeOutTicks":-1,"trigger":"repeat","repeatTicks":0}}
            """);
        write("high.json", """
            {"schemaVersion":1,"surface":"title","presentation":{"title":"Tip","ttlTicks":99999,
             "fadeInTicks":99999,"stayTicks":99999,"fadeOutTicks":99999,"trigger":"repeat","repeatTicks":999999}}
            """);
        Map<String, ProxySurfaceDocuments.Document> documents = byId();
        ProxySurfaceDocuments.Presentation low = documents.get("low").presentation();
        assertEquals(1, low.ttlTicks());
        assertEquals(0, low.fadeInTicks());
        assertEquals(0, low.stayTicks());
        assertEquals(0, low.fadeOutTicks());
        assertEquals(1, low.repeatTicks());
        ProxySurfaceDocuments.Presentation high = documents.get("high").presentation();
        assertEquals(1200, high.ttlTicks());
        assertEquals(1200, high.fadeInTicks());
        assertEquals(1200, high.stayTicks());
        assertEquals(1200, high.fadeOutTicks());
        assertEquals(72000, high.repeatTicks());
    }

    @Test
    void progressReadsWrappedAndBareExpressionsWhileSlotsAndHudPriorityAreIgnored() throws IOException {
        write("wrapped.json", """
            {"schemaVersion":1,"surface":"bossbar","presentation":{"title":"Ping","slots":["left"],
             "priority":"ambient","progress":"{{ viewer.ping / 100 }}","color":"red","style":"segmented_20"}}
            """);
        write("bare.json", """
            {"schemaVersion":1,"surface":"bossbar","presentation":{"title":"Ping",
             "progress":"viewer.ping / 200"}}
            """);
        Map<String, ProxySurfaceDocuments.Document> documents = byId();
        ExpressionScope scope = scope(Map.of("viewer.ping", 50.0D));
        assertEquals(0.5D, ExprEvaluator.number(documents.get("wrapped").presentation().progress(), scope));
        assertEquals(0.25D, ExprEvaluator.number(documents.get("bare").presentation().progress(), scope));
        assertEquals("red", documents.get("wrapped").presentation().color());
        assertEquals("segmented_20", documents.get("wrapped").presentation().style());
    }

    @Test
    void documentsAndVariantsSortByPriorityThenId() throws IOException {
        write("zulu.json", """
            {"schemaVersion":1,"surface":"actionbar","select":{"priority":10,"when":"true"},
             "presentation":{"text":"Zulu"}}
            """);
        write("alpha.json", """
            {"schemaVersion":1,"surface":"actionbar","select":{"priority":10,"when":"true"},
             "presentation":{"text":"Alpha"},
             "variants":[{"id":"zeta","priority":20,"when":"true","presentation":{"text":"Zeta"}},
                         {"id":"beta","priority":20,"when":"true","presentation":{"text":"Beta"}},
                         {"id":"low","priority":5,"when":"true","presentation":{"text":"Low"}}]}
            """);
        write("bravo.json", """
            {"schemaVersion":1,"surface":"actionbar","select":{"priority":80,"when":"true"},
             "presentation":{"text":"Bravo"}}
            """);
        List<ProxySurfaceDocuments.Document> documents = ProxySurfaceDocuments.load(directory);
        assertEquals(List.of("bravo", "alpha", "zulu"), documents.stream()
            .map(ProxySurfaceDocuments.Document::id).toList());
        assertEquals(List.of("beta", "zeta", "low"), documents.get(1).variants().stream()
            .map(ProxySurfaceDocuments.Variant::id).toList());
        assertEquals("Beta", documents.get(1).variants().getFirst().presentation().text());
        assertThrows(UnsupportedOperationException.class, () -> documents.getFirst().variants().clear());
    }

    @Test
    void rejectsMissingAndUnknownSurfaceKinds() throws IOException {
        write("none.json", """
            {"schemaVersion":1,"presentation":{"text":"Hello"}}
            """);
        assertThrows(IllegalArgumentException.class, () -> ProxySurfaceDocuments.load(directory));
        write("none.json", """
            {"schemaVersion":1,"surface":"hologram","presentation":{"text":"Hello"}}
            """);
        assertThrows(IllegalArgumentException.class, () -> ProxySurfaceDocuments.load(directory));
    }

    @Test
    void rejectsPresentationsMissingTheirRequiredField() throws IOException {
        write("bar.json", """
            {"schemaVersion":1,"surface":"actionbar","presentation":{"ttlTicks":20}}
            """);
        assertThrows(IllegalArgumentException.class, () -> ProxySurfaceDocuments.load(directory));
        write("bar.json", """
            {"schemaVersion":1,"surface":"actionbar"}
            """);
        assertThrows(IllegalArgumentException.class, () -> ProxySurfaceDocuments.load(directory));
        write("bar.json", """
            {"schemaVersion":1,"surface":"bossbar","presentation":{"text":"Hello"}}
            """);
        assertThrows(IllegalArgumentException.class, () -> ProxySurfaceDocuments.load(directory));
        write("bar.json", """
            {"schemaVersion":1,"surface":"title","presentation":{"subtitle":"Only"}}
            """);
        assertThrows(IllegalArgumentException.class, () -> ProxySurfaceDocuments.load(directory));
    }

    @Test
    void rejectsUnknownColorsStylesTriggersAndBrokenProgress() throws IOException {
        write("boss.json", """
            {"schemaVersion":1,"surface":"bossbar","presentation":{"title":"Event","color":"orange"}}
            """);
        assertThrows(IllegalArgumentException.class, () -> ProxySurfaceDocuments.load(directory));
        write("boss.json", """
            {"schemaVersion":1,"surface":"bossbar","presentation":{"title":"Event","style":"segmented_7"}}
            """);
        assertThrows(IllegalArgumentException.class, () -> ProxySurfaceDocuments.load(directory));
        write("boss.json", """
            {"schemaVersion":1,"surface":"bossbar","presentation":{"title":"Event","progress":"viewer.mood"}}
            """);
        assertThrows(IllegalArgumentException.class, () -> ProxySurfaceDocuments.load(directory));
        write("boss.json", """
            {"schemaVersion":1,"surface":"bossbar","presentation":{"title":"Event","progress":"{{ }}"}}
            """);
        assertThrows(ExprException.class, () -> ProxySurfaceDocuments.load(directory));
        write("boss.json", """
            {"schemaVersion":1,"surface":"title","presentation":{"title":"Event","trigger":"pulse"}}
            """);
        assertThrows(IllegalArgumentException.class, () -> ProxySurfaceDocuments.load(directory));
    }

    @Test
    void rejectsBlankDuplicateAndUnsupportedVariantIds() throws IOException {
        write("bar.json", """
            {"schemaVersion":1,"surface":"actionbar","presentation":{"text":"Hello"},
             "variants":[{"id":" ","when":"true","presentation":{"text":"Blank"}}]}
            """);
        assertThrows(IllegalArgumentException.class, () -> ProxySurfaceDocuments.load(directory));
        write("bar.json", """
            {"schemaVersion":1,"surface":"actionbar","presentation":{"text":"Hello"},
             "variants":[{"id":"one two","when":"true","presentation":{"text":"Spaced"}}]}
            """);
        assertThrows(IllegalArgumentException.class, () -> ProxySurfaceDocuments.load(directory));
        write("bar.json", """
            {"schemaVersion":1,"surface":"actionbar","presentation":{"text":"Hello"},
             "variants":[{"id":"same","when":"true","presentation":{"text":"First"}},
                         {"id":"same","when":"true","presentation":{"text":"Second"}}]}
            """);
        assertThrows(IllegalArgumentException.class, () -> ProxySurfaceDocuments.load(directory));
        write("bar.json", """
            {"schemaVersion":1,"surface":"actionbar","presentation":{"text":"Hello"},
             "variants":[{"id":"empty","when":"true"}]}
            """);
        assertThrows(IllegalArgumentException.class, () -> ProxySurfaceDocuments.load(directory));
        write("bar.json", """
            {"schemaVersion":1,"surface":"bossbar","presentation":{"title":"Hello"},
             "variants":[{"id":"wrong","when":"true","presentation":{"text":"Action bar only"}}]}
            """);
        assertThrows(IllegalArgumentException.class, () -> ProxySurfaceDocuments.load(directory));
    }

    @Test
    void rejectsUnknownVariablesAndUnclosedTemplates() throws IOException {
        write("bar.json", """
            {"schemaVersion":1,"surface":"actionbar","select":{"when":"viewer.world == 'lobby'"},
             "presentation":{"text":"Hello"}}
            """);
        assertThrows(IllegalArgumentException.class, () -> ProxySurfaceDocuments.load(directory));
        write("bar.json", """
            {"schemaVersion":1,"surface":"actionbar","show":"unknownFunction('x')",
             "presentation":{"text":"Hello"}}
            """);
        assertThrows(IllegalArgumentException.class, () -> ProxySurfaceDocuments.load(directory));
        write("bar.json", """
            {"schemaVersion":1,"surface":"actionbar","presentation":{"text":"{{ missing"}}
            """);
        assertThrows(IllegalArgumentException.class, () -> ProxySurfaceDocuments.load(directory));
        write("bar.json", """
            {"schemaVersion":1,"surface":"actionbar","presentation":{"text":"{{ server.online + }}"}}
            """);
        assertThrows(ExprException.class, () -> ProxySurfaceDocuments.load(directory));
    }

    @Test
    void malformedJsonFailsTheWholeLoad() throws IOException {
        write("bar.json", "{ malformed");
        assertThrows(IOException.class, () -> ProxySurfaceDocuments.load(directory));
    }

    private Map<String, ProxySurfaceDocuments.Document> byId() throws IOException {
        Map<String, ProxySurfaceDocuments.Document> documents = new HashMap<>();
        for (ProxySurfaceDocuments.Document document : ProxySurfaceDocuments.load(directory)) {
            documents.put(document.id(), document);
        }
        return documents;
    }

    private void write(String name, String json) throws IOException {
        Files.createDirectories(directory.resolve("surfaces"));
        Files.writeString(directory.resolve("surfaces").resolve(name), json);
    }

    private static ExpressionScope scope(Map<String, Object> values) {
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
