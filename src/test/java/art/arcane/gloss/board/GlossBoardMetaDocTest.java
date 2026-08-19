package art.arcane.gloss.board;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class GlossBoardMetaDocTest {
    @Test
    void docRoundTripPreservesAllFields() {
        GlossBoardMeta meta = new GlossBoardMeta("arena");
        meta.setTitle("&d&lArena");
        meta.addLine("&7Line one");
        meta.addLine("&7Line two");
        meta.setPrimary(true);
        meta.setPermission("vip");
        meta.setGroups(List.of("admins", "mods"));

        BoardDoc doc = meta.toDoc(7L);
        GlossBoardMeta restored = GlossBoardMeta.fromDoc("arena", doc);

        assertEquals("arena", restored.id());
        assertEquals("&d&lArena", restored.title());
        assertEquals(List.of("&7Line one", "&7Line two"), restored.lines());
        assertTrue(restored.primary());
        assertEquals("vip", restored.permission());
        assertEquals(List.of("admins", "mods"), restored.groups());
        assertEquals(7L, restored.revision());
    }

    @Test
    void fromDocWithBlankTitleFallsBackToTheId() {
        BoardDoc doc = new BoardDoc(1, 1L, "", List.of(), false, null, null);

        GlossBoardMeta meta = GlossBoardMeta.fromDoc("bare", doc);

        assertEquals("bare", meta.id());
        assertEquals("bare", meta.title());
        assertEquals(List.of(), meta.lines());
        assertFalse(meta.primary());
        assertEquals(GlossBoardMeta.UNRESTRICTED_PERMISSION, meta.permission());
        assertEquals(List.of(), meta.groups());
        assertFalse(meta.permissionGated());
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

    @Test
    void newMetaStartsBeforeTheFirstDocRevision() {
        GlossBoardMeta meta = new GlossBoardMeta("fresh");

        assertEquals(0L, meta.revision());
        assertEquals(1L, meta.nextRevision());
        assertEquals(2L, meta.nextRevision());
        assertEquals(2L, meta.revision());
    }
}
