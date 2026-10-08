package art.arcane.gloss.importer;

import art.arcane.gloss.board.BoardDoc;
import art.arcane.gloss.bubble.BubbleStyleDoc;
import art.arcane.gloss.condition.ConditionCompiler;
import art.arcane.gloss.drop.RealDropSettingsDoc;
import art.arcane.gloss.entity.EntityOverlayDoc;
import art.arcane.gloss.expr.ExprScope;
import art.arcane.gloss.hologram.HologramDoc;
import art.arcane.gloss.indicator.DamageIndicatorSettingsDoc;
import art.arcane.gloss.tab.TablistDoc;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import org.junit.jupiter.api.DynamicTest;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestFactory;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class VersionedGlossConverterTest {
    @TestFactory
    List<DynamicTest> convertsEveryHistoricalFixtureWithoutMutatingItsSource() throws IOException {
        JsonArray manifest = JsonParser.parseString(resource("sources.json")).getAsJsonArray();
        List<DynamicTest> tests = new ArrayList<>();
        for (JsonElement entry : manifest) {
            JsonObject fixture = entry.getAsJsonObject();
            String file = fixture.get("file").getAsString();
            String kind = fixture.get("kind").getAsString();
            tests.add(DynamicTest.dynamicTest(file, () -> {
                JsonObject source = fixture(file);
                JsonObject original = source.deepCopy();
                VersionedGlossConverter.Conversion converted = VersionedGlossConverter.convert(kind, "default", source);
                assertEquals(original, source);
                assertEquals(source.get("revision"), converted.document().get("revision"));
                assertTrue(converted.document().get("schemaVersion").getAsInt() > source.get("schemaVersion").getAsInt());
                VersionedGlossConverter.Conversion unchanged = VersionedGlossConverter.convert(kind, "default", converted.document());
                assertEquals(converted.document(), unchanged.document());
                assertNotSame(converted.document(), unchanged.document());
                assertTrue(unchanged.warnings().isEmpty());
            }));
        }
        assertEquals(15, tests.size());
        return tests;
    }

    @Test
    void hologramPreservesAnchorLinesAndUniformAppearance() throws IOException {
        JsonObject source = fixture("holograms-v1.json");
        source.addProperty("seeThrough", false);
        source.addProperty("billboard", "horizontal");
        HologramDoc converted = HologramDoc.parse("test", convert("holograms", source).toString());
        assertEquals("world_nether", converted.anchor().world());
        assertEquals(12.5, converted.anchor().position().getX());
        assertEquals(2, converted.lines().size());
        assertEquals(2.5F, converted.style().scaleX());
        assertEquals(2.5F, converted.style().scaleY());
        assertEquals(2.5F, converted.style().scaleZ());
        assertFalse(converted.style().seeThrough());
        assertEquals("HORIZONTAL", converted.style().billboard().name());
    }

    @Test
    void firstBubbleSchemaDisablesUnrequestedMotionAndShimmer() throws IOException {
        JsonObject source = fixture("bubbles-v1.json");
        source.addProperty("flyAway", false);
        source.remove("offset");
        VersionedGlossConverter.Conversion conversion = VersionedGlossConverter.convert("bubbles", "default", source);
        BubbleStyleDoc converted = BubbleStyleDoc.parse("test", conversion.document().toString());
        assertEquals("0", converted.motion().translation().y());
        assertFalse(converted.shimmer().spawn());
        assertFalse(converted.shimmer().flyAway());
        assertEquals(1, converted.offset().getY());
        assertTrue(conversion.warnings().stream().anyMatch(message -> message.contains("line staggering")));
        source.addProperty("flyAway", true);
        assertEquals(BubbleStyleDoc.DEFAULT_TRANSLATION_Y,
            BubbleStyleDoc.parse("test", convert("bubbles", source).toString()).motion().translation().y());
    }

    @Test
    void bubbleSelectionPreservesGlobAndPrimaryGroupConjunction() throws IOException {
        JsonObject source = fixture("bubbles-v2.json");
        source.add("select", JsonParser.parseString("""
            {"priority":12,"worlds":["dungeon_*","arena_?"],"groups":["VIP","builder"]}
            """));
        BubbleStyleDoc converted = BubbleStyleDoc.parse("test", convert("bubbles", source).toString());
        assertEquals(12, converted.select().priority());
        assertTrue(matches(converted.select().when(), Map.of("subject.world", "dungeon_one"), Set.of("vip"), Set.of()));
        assertFalse(matches(converted.select().when(), Map.of("subject.world", "lobby"), Set.of("vip"), Set.of()));
        assertFalse(matches(converted.select().when(), Map.of("subject.world", "arena_1"), Set.of("guest"), Set.of()));
    }

    @Test
    void boardPermissionUsesHistoricalNodeAndMixedFallbackIsDisclosed() throws IOException {
        JsonObject source = fixture("boards-v1.json");
        source.addProperty("permission", " Staff ");
        source.addProperty("title", "");
        source.add("groups", JsonParser.parseString("[\"admins\"]"));
        VersionedGlossConverter.Conversion conversion = VersionedGlossConverter.convert("boards", "staff-board", source);
        BoardDoc board = BoardDoc.parse("test", conversion.document().toString());
        assertEquals("staff-board", board.presentation().title());
        assertFalse(matches(board.select().when(), Map.of(), Set.of("admins"), Set.of("staff")));
        assertTrue(matches(board.select().when(), Map.of(), Set.of("guest"), Set.of("gloss.board.staff")));
        assertFalse(conversion.warnings().isEmpty());
    }

    @Test
    void boardWithoutSelectorsRemainsManualOnly() throws IOException {
        BoardDoc board = BoardDoc.parse("test", convert("boards", fixture("boards-v1.json")).toString());
        assertFalse(matches(board.select().when(), Map.of(), Set.of(), Set.of()));
    }

    @Test
    void tablistPreservesOperatorGroupTokenAndGroupFallback() throws IOException {
        JsonObject source = fixture("tablist-v1.json");
        source.add("nameFormats", JsonParser.parseString("""
            {"default":"$group $player","_op":"$group: $player","VIP":"&b$player"}
            """));
        TablistDoc converted = TablistDoc.parse("test", convert("tablist", source).toString());
        assertTrue(converted.headerFooter().enabled());
        assertEquals("&d&lGloss", converted.headerFooter().presentation().header());
        assertEquals("$group $player", converted.listNames().presentation().format());
        TablistDoc.ListNameVariant operator = converted.listNames().variants().stream()
            .filter(variant -> variant.priority() == 100).findFirst().orElseThrow();
        assertEquals("_op: $player", operator.presentation().format());
        assertTrue(matches(operator.when(), Map.of("subject.op", true), Set.of(), Set.of()));
        assertTrue(converted.listNames().variants().stream().anyMatch(variant ->
            variant.presentation().format().equals("$group: $player")
                && matches(variant.when(), Map.of("subject.op", false), Set.of("_op"), Set.of())));
    }

    @Test
    void indicatorPreservesDisabledStylesTransformsAndWorldFilter() throws IOException {
        JsonObject source = fixture("damage-indicators-v1.json");
        source.getAsJsonObject("damage").addProperty("enabled", false);
        source.getAsJsonObject("filters").add("disabledWorlds", JsonParser.parseString("[\"arena\",\"builder's-world\"]"));
        DamageIndicatorSettingsDoc converted = DamageIndicatorSettingsDoc.parse("test", convert("damage-indicators", source).toString());
        assertEquals("false", converted.damage().when());
        assertEquals(0.82, converted.damage().presentation().transform().endScale());
        assertEquals(0.45, converted.healing().presentation().motion().horizontalSpeed());
        assertFalse(matches(converted.show().expression(), Map.of("subject.world", "builder's-world"), Set.of(), Set.of()));
        assertTrue(matches(converted.show().expression(), Map.of("subject.world", "survival"), Set.of(), Set.of()));
        assertTrue(converted.audience().when().contains("gloss.indicators.show"));
    }

    @Test
    void dropLabelsRetainBlockViewRangeAndColorInBaseAndVariants() throws IOException {
        JsonObject source = fixture("real-drops-v3.json");
        JsonObject labels = source.getAsJsonObject("presentation").getAsJsonObject("labels");
        labels.addProperty("viewRange", 96);
        labels.addProperty("scale", 1.5);
        labels.addProperty("backgroundRed", 17);
        labels.addProperty("backgroundGreen", 34);
        labels.addProperty("backgroundBlue", 51);
        JsonObject variant = new JsonObject();
        variant.addProperty("id", "special");
        variant.addProperty("when", "true");
        JsonObject presentation = new JsonObject();
        presentation.add("labels", labels.deepCopy());
        presentation.getAsJsonObject("labels").addProperty("background", false);
        variant.add("presentation", presentation);
        source.getAsJsonArray("variants").add(variant);
        JsonObject converted = convert("real-drops", source);
        RealDropSettingsDoc parsed = RealDropSettingsDoc.parse("test", converted.toString());
        assertEquals(1.5F, parsed.presentation().labels().style().viewRange());
        assertEquals(1.5F, parsed.presentation().labels().style().scaleY());
        assertEquals("#50112233", converted.getAsJsonObject("presentation").getAsJsonObject("labels").getAsJsonObject("style").get("backgroundArgb").getAsString().toUpperCase());
        assertEquals("#00000000", converted.getAsJsonArray("variants").get(0).getAsJsonObject().getAsJsonObject("presentation").getAsJsonObject("labels").getAsJsonObject("style").get("backgroundArgb").getAsString());
        assertEquals(128, parsed.presentation().limits().maxVisualsPerChunk());
    }

    @Test
    void overlayKeepsStackSuffixOnNameOrHealthWithoutExtraRows() throws IOException {
        JsonObject source = fixture("entity-overlays-v1.json");
        source.remove("maxEntitiesPerViewer");
        EntityOverlayDoc converted = EntityOverlayDoc.parse("test", convert("entity-overlays", source).toString());
        assertEquals(64, converted.maxEntitiesPerViewer());
        assertTrue(converted.style().seeThrough());
        assertEquals(List.of("&f{name} &7x{count}", "{bar} &f{health}&7/{max_health}", "&7ATK &f{attack} &8| &7ARM &f{armor}"),
            visibleText(converted, true, 3));
        assertEquals(List.of("{bar} &f{health}&7/{max_health} &7x{count}", "&7ATK &f{attack} &8| &7ARM &f{armor}"),
            visibleText(converted, false, 3));
        source.addProperty("showNames", false);
        source.addProperty("showHealthNumbers", false);
        source.addProperty("showCombatStats", false);
        assertEquals(List.of("{bar} &7x{count}"), visibleText(EntityOverlayDoc.parse("test", convert("entity-overlays", source).toString()), true, 2));
    }

    @Test
    void unknownVersionsAndInvalidCurrentDocumentsFailExplicitly() throws IOException {
        JsonObject source = fixture("boards-v1.json");
        for (String value : List.of("0", "-1", "3", "1.5", "\"1\"", "null")) {
            source.add("schemaVersion", JsonParser.parseString(value));
            assertThrows(IllegalArgumentException.class, () -> convert("boards", source), value);
        }
        source.addProperty("schemaVersion", BoardDoc.CURRENT_SCHEMA_VERSION);
        source.addProperty("revision", -1);
        assertThrows(IllegalArgumentException.class, () -> convert("boards", source));
        assertThrows(IllegalArgumentException.class, () -> convert("unknown", source));
    }

    private static List<String> visibleText(EntityOverlayDoc document, boolean named, int count) {
        List<String> visible = new ArrayList<>();
        for (EntityOverlayDoc.Line line : document.lines()) {
            if (line.type().equals("text") && matches(line.show().expression(),
                Map.of("entity.named", named, "entity.stackCount", (double) count, "entity.damaged", false), Set.of(), Set.of())) {
                visible.add(line.text());
            }
        }
        return visible;
    }

    private static boolean matches(String expression, Map<String, Object> variables, Set<String> groups, Set<String> permissions) {
        return ConditionCompiler.compile(expression).matches(new TestScope(variables, groups, permissions));
    }

    private static JsonObject convert(String kind, JsonObject source) {
        return VersionedGlossConverter.convert(kind, "default", source).document();
    }

    private static JsonObject fixture(String name) throws IOException {
        return JsonParser.parseString(resource(name)).getAsJsonObject();
    }

    private static String resource(String name) throws IOException {
        try (InputStream stream = VersionedGlossConverterTest.class.getResourceAsStream("/importer/versioned-gloss/" + name)) {
            if (stream == null) {
                throw new IOException("Missing fixture " + name);
            }
            return new String(stream.readAllBytes(), StandardCharsets.UTF_8);
        }
    }

    private record TestScope(Map<String, Object> variables, Set<String> groups, Set<String> permissions) implements ExprScope {
        @Override
        public Object variable(String name) {
            return variables.get(name);
        }

        @Override
        public Object call(String name, List<Object> arguments) {
            return switch (name) {
                case "inGroup" -> groups.contains(arguments.get(1).toString());
                case "hasPermission" -> permissions.contains(arguments.get(1).toString());
                default -> null;
            };
        }
    }
}
