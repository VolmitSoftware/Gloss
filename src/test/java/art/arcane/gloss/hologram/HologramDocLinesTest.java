package art.arcane.gloss.hologram;

import art.arcane.gloss.config.action.MessageActionData;
import art.arcane.gloss.config.icon.ItemIconData;
import art.arcane.gloss.doc.DocumentParsers;
import art.arcane.gloss.enums.MenuActionType;
import com.google.gson.JsonArray;
import com.google.gson.JsonParser;
import org.bukkit.util.Vector;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class HologramDocLinesTest {
    private static final String RICH = """
        {
          "schemaVersion": 3, "revision": 4,
          "anchor": {"world": "world", "position": [0, 64, 0]},
          "lines": [
            "&6Shop",
            { "text": "&7Welcome", "show": "player.x > 1" },
            { "item": { "type": "item", "item": "minecraft:emerald" }, "scale": 0.6 },
            { "head": "Notch", "scale": 0.8 },
            { "block": "minecraft:beacon", "scale": 0.5 },
            { "entity": "minecraft:villager", "scale": 0.7 }
          ],
          "actions": [ { "type": "message", "message": "&aHello", "trigger": "left_click" } ],
          "hitbox": { "width": 1.2, "height": 0.35, "perLine": true },
          "motion": "breathe"
        }
        """;

    @Test
    void stringAndObjectLinesParseIntoTypedLines() {
        HologramDoc doc = HologramDoc.parse("shop.json", RICH);
        List<HologramLine> lines = doc.lines();

        assertEquals(6, lines.size());
        assertEquals(HologramLine.Kind.TEXT, lines.get(0).kind());
        assertEquals("&6Shop", lines.get(0).text());
        assertTrue(lines.get(0).show().isAlwaysVisible());
        assertEquals("&7Welcome", lines.get(1).text());
        assertEquals("player.x > 1", lines.get(1).show().expression());
        assertEquals(HologramLine.Kind.ITEM, lines.get(2).kind());
        assertInstanceOf(ItemIconData.class, lines.get(2).item());
        assertEquals(0.6D, lines.get(2).scale(), 1.0E-9D);
        assertEquals(HologramLine.Kind.HEAD, lines.get(3).kind());
        assertEquals("Notch", lines.get(3).value());
        assertEquals(HologramLine.Kind.BLOCK, lines.get(4).kind());
        assertEquals("minecraft:beacon", lines.get(4).value());
        assertEquals(HologramLine.Kind.ENTITY, lines.get(5).kind());
        assertEquals("minecraft:villager", lines.get(5).value());
        assertEquals(List.of("&6Shop", "&7Welcome"), doc.textLines());
    }

    @Test
    void actionsHitboxAndMotionParse() {
        HologramDoc doc = HologramDoc.parse("shop.json", RICH);

        assertEquals(1, doc.actions().size());
        assertEquals(MenuActionType.MESSAGE, doc.actions().getFirst().getType());
        assertInstanceOf(MessageActionData.class, doc.actions().getFirst());
        assertEquals(1.2D, doc.hitbox().width(), 1.0E-9D);
        assertEquals(0.35D, doc.hitbox().height(), 1.0E-9D);
        assertTrue(doc.hitbox().perLine());
        assertEquals("breathe", doc.motion());
    }

    @Test
    void plainStringLinesRoundTripAsStrings() {
        HologramDoc doc = HologramDoc.parse("plain.json", """
            {
              "schemaVersion": 3, "revision": 1,
              "anchor": {"world": "world", "position": [0, 64, 0]},
              "lines": ["&dOne", "&7Two"]
            }
            """);

        String encoded = DocumentParsers.GSON.toJson(doc);
        JsonArray lines = JsonParser.parseString(encoded).getAsJsonObject().getAsJsonArray("lines");

        assertEquals(2, lines.size());
        assertTrue(lines.get(0).isJsonPrimitive(), encoded);
        assertEquals("&dOne", lines.get(0).getAsString());
        assertEquals("&7Two", lines.get(1).getAsString());
        assertEquals(doc, DocumentParsers.GSON.fromJson(encoded, HologramDoc.class));
    }

    @Test
    void objectLinesRoundTripAsObjects() {
        HologramDoc doc = HologramDoc.parse("shop.json", RICH);

        String encoded = DocumentParsers.GSON.toJson(doc);
        HologramDoc restored = DocumentParsers.GSON.fromJson(encoded, HologramDoc.class);
        assertEquals(doc.lines(), restored.lines());
        assertEquals(doc.hitbox(), restored.hitbox());
        assertEquals("breathe", restored.motion());
    }

    @Test
    void unknownLineKeysAreRefused() {
        assertThrows(IllegalArgumentException.class, () -> HologramDoc.parse("bad.json", """
            {
              "schemaVersion": 3, "revision": 1,
              "anchor": {"world": "world", "position": [0, 64, 0]},
              "lines": [{ "text": "hi", "bogus": 1 }]
            }
            """));
    }

    @Test
    void aLineDeclaresExactlyOneContent() {
        assertThrows(IllegalArgumentException.class, () -> HologramDoc.parse("bad.json", """
            {
              "schemaVersion": 3, "revision": 1,
              "anchor": {"world": "world", "position": [0, 64, 0]},
              "lines": [{ "text": "hi", "block": "minecraft:stone" }]
            }
            """));
        assertThrows(IllegalArgumentException.class, () -> HologramDoc.parse("bad.json", """
            {
              "schemaVersion": 3, "revision": 1,
              "anchor": {"world": "world", "position": [0, 64, 0]},
              "lines": [{ "scale": 2 }]
            }
            """));
    }

    @Test
    void pagesReplaceLinesAndAreMutuallyExclusive() {
        HologramDoc doc = HologramDoc.parse("paged.json", """
            {
              "schemaVersion": 3, "revision": 1,
              "anchor": {"world": "world", "position": [0, 64, 0]},
              "pages": [
                { "id": "1", "lines": ["&6Page one"] },
                { "id": "2", "lines": ["&6Page two", { "block": "minecraft:beacon" }] }
              ]
            }
            """);

        assertEquals(List.of("1", "2"), doc.pages().stream().map(HologramPage::id).toList());
        assertTrue(doc.lines().isEmpty());
        assertEquals(List.of("&6Page one"), doc.linesFor("1").stream().map(HologramLine::text).toList());
        assertEquals(2, doc.linesFor("2").size());
        assertEquals(List.of("&6Page one"), doc.linesFor(null).stream().map(HologramLine::text).toList());

        assertThrows(IllegalArgumentException.class, () -> HologramDoc.parse("bad.json", """
            {
              "schemaVersion": 3, "revision": 1,
              "anchor": {"world": "world", "position": [0, 64, 0]},
              "lines": ["&6One"],
              "pages": [ { "id": "1", "lines": ["&6Page one"] } ]
            }
            """));
    }

    @Test
    void duplicatePageIdsAreRefused() {
        assertThrows(IllegalArgumentException.class, () -> HologramDoc.parse("bad.json", """
            {
              "schemaVersion": 3, "revision": 1,
              "anchor": {"world": "world", "position": [0, 64, 0]},
              "pages": [ { "id": "1", "lines": ["a"] }, { "id": "1", "lines": ["b"] } ]
            }
            """));
    }

    @Test
    void textOnlyDocumentsKeepTheirShorterConstructor() {
        HologramDoc doc = new HologramDoc(HologramDoc.CURRENT_SCHEMA_VERSION, 1L,
            new HologramDoc.Anchor("world", new Vector(1, 2, 3)), List.of("&dOne", "&7Two"),
            null, null, 0.0D, 0.0D, List.of(), null);

        assertEquals(List.of("&dOne", "&7Two"), doc.textLines());
        assertTrue(doc.pages().isEmpty());
        assertTrue(doc.actions().isEmpty());
        assertFalse(doc.hasMotion());
    }
}
