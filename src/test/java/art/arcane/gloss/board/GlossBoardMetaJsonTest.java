package art.arcane.gloss.board;

import art.arcane.volmlib.util.json.JSONObject;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class GlossBoardMetaJsonTest {
    @Test
    void jsonRoundTripPreservesAllFields() {
        GlossBoardMeta meta = new GlossBoardMeta("arena");
        meta.setTitle("&d&lArena");
        meta.addLine("&7Line one");
        meta.addLine("&7Line two");
        meta.setPrimary(true);
        meta.setPermission("vip");

        GlossBoardMeta restored = GlossBoardMeta.fromJson("arena", meta.toJson());

        assertEquals("arena", restored.id());
        assertEquals("&d&lArena", restored.title());
        assertEquals(List.of("&7Line one", "&7Line two"), restored.lines());
        assertTrue(restored.primary());
        assertEquals("vip", restored.permission());
    }

    @Test
    void fromJsonWithMissingKeysUsesDefaults() {
        GlossBoardMeta meta = GlossBoardMeta.fromJson("bare", new JSONObject());

        assertEquals("bare", meta.id());
        assertEquals("bare", meta.title());
        assertEquals(List.of(), meta.lines());
        assertFalse(meta.primary());
        assertEquals(GlossBoardMeta.UNRESTRICTED_PERMISSION, meta.permission());
        assertFalse(meta.permissionGated());
    }

    @Test
    void fromJsonReadsLegacyShape() {
        JSONObject json = new JSONObject("{\"title\":\"&6Board\",\"content\":[\"a\",\"b\"],\"primary\":false,\"permission\":\"staff\"}");

        GlossBoardMeta meta = GlossBoardMeta.fromJson("legacy", json);

        assertEquals("&6Board", meta.title());
        assertEquals(List.of("a", "b"), meta.lines());
        assertFalse(meta.primary());
        assertEquals("staff", meta.permission());
        assertTrue(meta.permissionGated());
    }

    @Test
    void defaultPermissionIsUnrestricted() {
        GlossBoardMeta meta = new GlossBoardMeta("plain");

        assertEquals("default", meta.permission());
        assertFalse(meta.permissionGated());
        assertEquals("gloss.board.default", meta.permissionNode());
    }

    @Test
    void permissionValueMapsToBoardNode() {
        GlossBoardMeta meta = new GlossBoardMeta("gated");
        meta.setPermission(" VIP ");

        assertEquals("vip", meta.permission());
        assertTrue(meta.permissionGated());
        assertEquals("gloss.board.vip", meta.permissionNode());
    }

    @Test
    void blankOrNullPermissionNormalizesToDefault() {
        GlossBoardMeta meta = new GlossBoardMeta("gated");
        meta.setPermission("vip");

        meta.setPermission(null);
        assertEquals(GlossBoardMeta.UNRESTRICTED_PERMISSION, meta.permission());

        meta.setPermission("   ");
        assertEquals(GlossBoardMeta.UNRESTRICTED_PERMISSION, meta.permission());
        assertFalse(meta.permissionGated());
    }

    @Test
    void lineMutatorsEditContentInPlace() {
        GlossBoardMeta meta = new GlossBoardMeta("lines");
        meta.addLine("one");
        meta.addLine("two");
        meta.addLine("three");

        meta.setLine(1, "TWO");
        meta.removeLine(0);

        assertEquals(List.of("TWO", "three"), meta.lines());
    }

    @Test
    void linesReturnsImmutableCopy() {
        GlossBoardMeta meta = new GlossBoardMeta("frozen");
        meta.addLine("one");

        List<String> snapshot = meta.lines();

        assertThrows(UnsupportedOperationException.class, () -> snapshot.add("two"));
        assertEquals(List.of("one"), meta.lines());
    }
}
