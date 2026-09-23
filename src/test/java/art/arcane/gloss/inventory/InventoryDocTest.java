package art.arcane.gloss.inventory;

import art.arcane.gloss.config.components.ButtonComponentData;
import art.arcane.gloss.config.components.ComponentData;
import art.arcane.gloss.config.components.DecoComponentData;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.nio.file.Files;
import java.nio.file.Path;
import java.io.IOException;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * What an inventory document accepts. A mask that does not line up with the window, a character
 * with nothing behind it and a list pointed at an area that does not exist all produce a window
 * with holes in it, so they are refused at load instead.
 */
class InventoryDocTest {

    @Test
    void gameplayInventoryHasUsableConfirmationAndCloseControls() throws IOException {
        InventoryDoc doc = parse(Files.readString(Path.of("src/test/gameplay/fixtures/interaction-inventory.json")));
        assertEquals(27, doc.size());
        assertInstanceOf(ButtonComponentData.class, doc.resolveSlots().get(11));
        assertInstanceOf(ButtonComponentData.class, doc.resolveSlots().get(15));
    }

    @Test
    void aChestDocumentResolvesItsMaskAgainstItsKeys() {
        InventoryDoc doc = parse("""
            { "schemaVersion": 1, "revision": 1, "title": "&8Shop", "resolution": "9x3",
              "mask": [ "#########", "#.......#", "#########" ],
              "keys": { "#": { "type": "decoration", "icon": { "type": "item", "item": "minecraft:gray_stained_glass_pane" } } } }
            """);
        assertEquals(9, doc.width());
        assertEquals(3, doc.rows());
        Map<Integer, ComponentData> resolved = doc.resolveSlots();
        assertEquals(20, resolved.size());
        assertInstanceOf(DecoComponentData.class, resolved.get(0));
        assertNull(resolved.get(10), "a mask cell with no key stays empty");
    }

    @Test
    void aMaskRowThatDoesNotMatchTheResolutionIsRefused() {
        IllegalArgumentException failure = assertThrows(IllegalArgumentException.class, () -> parse("""
            { "schemaVersion": 1, "revision": 1, "resolution": "9x3",
              "mask": [ "#####", "#########", "#########" ] }
            """));
        assertTrue(failure.getMessage().contains("row"), failure.getMessage());
    }

    @Test
    void aMaskWithMoreRowsThanTheResolutionIsRefused() {
        assertThrows(IllegalArgumentException.class, () -> parse("""
            { "schemaVersion": 1, "revision": 1, "resolution": "9x1",
              "mask": [ "#########", "#########" ] }
            """));
    }

    @Test
    void anUnknownResolutionIsRefused() {
        IllegalArgumentException failure = assertThrows(IllegalArgumentException.class, () -> parse("""
            { "schemaVersion": 1, "revision": 1, "resolution": "7x7" }
            """));
        assertTrue(failure.getMessage().contains("7x7"), failure.getMessage());
    }

    @Test
    void everySupportedResolutionParses() {
        for (String resolution : List.of("9x1", "9x2", "9x3", "9x4", "9x5", "9x6", "5x1", "3x3")) {
            InventoryDoc doc = parse("{ \"schemaVersion\": 1, \"revision\": 1, \"resolution\": \"" + resolution + "\" }");
            assertEquals(resolution, doc.resolution());
        }
    }

    @Test
    void explicitSlotsOverrideTheMask() {
        InventoryDoc doc = parse("""
            { "schemaVersion": 1, "revision": 1, "resolution": "9x2",
              "mask": [ "#########", "#########" ],
              "keys": { "#": { "type": "decoration", "icon": { "type": "item", "item": "minecraft:gray_stained_glass_pane" } } },
              "slots": { "4": { "type": "button", "icon": { "type": "item", "item": "minecraft:diamond_sword" },
                                "actions": [ { "type": "close" } ] } } }
            """);
        Map<Integer, ComponentData> resolved = doc.resolveSlots();
        assertInstanceOf(ButtonComponentData.class, resolved.get(4));
        assertInstanceOf(DecoComponentData.class, resolved.get(3));
    }

    @Test
    void aSlotOutsideTheWindowIsRefused() {
        IllegalArgumentException failure = assertThrows(IllegalArgumentException.class, () -> parse("""
            { "schemaVersion": 1, "revision": 1, "resolution": "9x1",
              "slots": { "40": { "type": "decoration", "icon": { "type": "item", "item": "minecraft:stone" } } } }
            """));
        assertTrue(failure.getMessage().contains("40"), failure.getMessage());
    }

    @Test
    void aListAreaMustAppearInTheMask() {
        IllegalArgumentException failure = assertThrows(IllegalArgumentException.class, () -> parse("""
            { "schemaVersion": 1, "revision": 1, "resolution": "9x3",
              "mask": [ "#########", "#########", "#########" ],
              "keys": { "#": { "type": "decoration", "icon": { "type": "item", "item": "minecraft:stone" } } },
              "list": { "area": ".", "var": "entry", "source": "['stone']",
                        "template": { "type": "decoration", "icon": { "type": "item", "item": "minecraft:stone" } } } }
            """));
        assertTrue(failure.getMessage().contains("area"), failure.getMessage());
    }

    @Test
    void aListFillsItsAreaAndDefaultsItsPageSize() {
        InventoryDoc doc = parse("""
            { "schemaVersion": 1, "revision": 1, "resolution": "9x3",
              "mask": [ "#########", "#.......#", "#########" ],
              "keys": { "#": { "type": "decoration", "icon": { "type": "item", "item": "minecraft:stone" } } },
              "list": { "area": ".", "var": "entry", "source": "['stone','dirt']",
                        "template": { "type": "decoration", "icon": { "type": "item", "item": "minecraft:stone" } } } }
            """);
        assertEquals(7, doc.listSlots().size());
        assertEquals(7, doc.list().pageSize(), "an unset pageSize fills the whole area");
        assertEquals(20, doc.list().refreshTicks());
    }

    @Test
    void aListWithoutATemplateIsRefused() {
        assertThrows(IllegalArgumentException.class, () -> parse("""
            { "schemaVersion": 1, "revision": 1, "resolution": "9x3",
              "mask": [ "#########", "#.......#", "#########" ],
              "list": { "area": ".", "var": "entry", "source": "['stone']" } }
            """));
    }

    @Test
    void aVariantReplacesTheWholePresentation() {
        InventoryDoc doc = parse("""
            { "schemaVersion": 1, "revision": 1, "resolution": "9x1", "title": "Base",
              "show": "false",
              "select": { "priority": 3, "when": "viewer.level > 1" },
              "variants": [ { "id": "vip", "priority": 9, "when": "viewer.level > 30",
                              "presentation": { "title": "VIP", "mask": [ "#########" ],
                                "keys": { "#": { "type": "decoration", "icon": { "type": "item", "item": "minecraft:gold_block" } } } } } ] }
            """);
        assertEquals(1, doc.variants().size());
        assertEquals("VIP", doc.variants().getFirst().presentation().title());
        assertEquals(3, doc.select().priority());
        assertTrue(doc.closeOnTeleport());
    }

    @Test
    void anUnsupportedSchemaVersionIsRefused() {
        assertThrows(IllegalArgumentException.class,
            () -> parse("{ \"schemaVersion\": 9, \"revision\": 1, \"resolution\": \"9x1\" }"));
    }

    private static InventoryDoc parse(String raw) {
        return InventoryDoc.parse("test.json", raw);
    }
}
