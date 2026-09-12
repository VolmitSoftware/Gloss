package art.arcane.gloss.board;

import art.arcane.gloss.doc.DocumentParsers;
import com.google.gson.JsonElement;
import com.google.gson.JsonParser;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

class BoardDocLineTest {
    @Test
    void aPlainStringLineParsesAsTextWithNoValueOrFormat() {
        BoardDoc doc = BoardDoc.parse("stats.json", """
            { "schemaVersion": 2, "revision": 1,
              "presentation": { "title": "&6Stats", "lines": ["&fKills", "&7---"] } }
            """);

        assertEquals(List.of(new BoardLine("&fKills", null, null), new BoardLine("&7---", null, null)),
            doc.presentation().lines());
        assertEquals(List.of("&fKills", "&7---"), doc.presentation().texts());
    }

    @Test
    void anObjectLineCarriesItsValueAndFormat() {
        BoardDoc doc = BoardDoc.parse("stats.json", """
            { "schemaVersion": 2, "revision": 1,
              "presentation": { "title": "&6Stats", "lines": [
                { "text": "&fKills", "value": "{{ fixed(1, 0) }}", "format": "fixed" },
                { "text": "&7------", "format": "blank" } ] } }
            """);

        assertEquals(new BoardLine("&fKills", "{{ fixed(1, 0) }}", BoardLineFormat.FIXED),
            doc.presentation().lines().get(0));
        assertEquals(BoardLineFormat.BLANK, doc.presentation().lines().get(1).format());
        assertNull(doc.presentation().lines().get(1).value());
    }

    @Test
    void anUnknownFormatIsRefused() {
        assertThrows(IllegalArgumentException.class, () -> BoardDoc.parse("bad.json", """
            { "schemaVersion": 2, "revision": 1,
              "presentation": { "lines": [ { "text": "&fKills", "format": "column" } ] } }
            """));
    }

    @Test
    void aStringLineIsWrittenBackAsAStringAndAnObjectLineAsAnObject() {
        BoardDoc doc = new BoardDoc(BoardDoc.CURRENT_SCHEMA_VERSION, 1L, null, null,
            new BoardDoc.Presentation("&6Stats", List.of(
                new BoardLine("&fPlain", null, null),
                new BoardLine("&fKills", "12", BoardLineFormat.FIXED)), false), List.of());

        JsonElement written = JsonParser.parseString(DocumentParsers.GSON.toJson(doc));
        JsonElement lines = written.getAsJsonObject().getAsJsonObject("presentation").get("lines");

        assertEquals("\"&fPlain\"", lines.getAsJsonArray().get(0).toString());
        assertEquals("{\"text\":\"&fKills\",\"value\":\"12\",\"format\":\"fixed\"}",
            lines.getAsJsonArray().get(1).toString());
    }

    @Test
    void aStringLineListRoundTripsThroughTheStringFactory() {
        BoardDoc.Presentation presentation = BoardDoc.Presentation.ofStrings("&6Stats",
            List.of("one", "two"), true);

        assertEquals(List.of("one", "two"), presentation.texts());
        assertEquals(List.of(new BoardLine("one", null, null), new BoardLine("two", null, null)),
            presentation.lines());
    }

    @Test
    void aBoardMetaRoundTripsLineObjectsThroughItsDocument() {
        GlossBoardMeta meta = new GlossBoardMeta("stats");
        meta.addLine(new BoardLine("&fKills", "12", BoardLineFormat.FIXED));
        meta.addLine("&7plain");

        BoardDoc doc = meta.toDoc(2L);
        GlossBoardMeta restored = GlossBoardMeta.fromDoc("stats", doc);

        assertEquals(List.of(new BoardLine("&fKills", "12", BoardLineFormat.FIXED),
            new BoardLine("&7plain", null, null)), restored.boardLines());
        assertEquals(List.of("&fKills", "&7plain"), restored.lines());
    }

    @Test
    void settingALineTextKeepsItsValueAndFormat() {
        GlossBoardMeta meta = new GlossBoardMeta("stats");
        meta.addLine(new BoardLine("&fKills", "12", BoardLineFormat.FIXED));

        meta.setLine(0, "&fDeaths");

        assertEquals(new BoardLine("&fDeaths", "12", BoardLineFormat.FIXED), meta.boardLines().get(0));
    }

    @Test
    void aRenderPlanCarriesTheValueColumnAlongsideTheText() {
        GlossBoardMeta meta = new GlossBoardMeta("stats");
        meta.addLine(new BoardLine("&fKills", "12", BoardLineFormat.FIXED));
        meta.addLine(new BoardLine("&7---", null, BoardLineFormat.BLANK));

        GlossBoardMeta.RenderPlan plan = meta.renderPlan("base", meta.presentation(), 0L, 15,
            raw -> raw);

        assertEquals("12", plan.staticValue(0));
        assertEquals(BoardLineFormat.FIXED, plan.format(0));
        assertNull(plan.staticValue(1));
        assertEquals(BoardLineFormat.BLANK, plan.format(1));
    }
}
