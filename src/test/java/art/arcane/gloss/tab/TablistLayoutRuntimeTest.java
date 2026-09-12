package art.arcane.gloss.tab;

import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class TablistLayoutRuntimeTest {
    @Test
    void theGridIsIndexedColumnMajorSoItReadsDownEachColumn() {
        TablistLayoutRuntime runtime = runtime(layout(4, 20, List.of(
            new TablistDoc.Slot(0, 0, "&6&lStaff", "MHF_Question", null),
            new TablistDoc.Slot(3, 19, "&9discord.gg/example", null, 0)), null));

        assertEquals(80, runtime.size());
        assertEquals(0, TablistLayoutRuntime.indexOf(0, 0, 20));
        assertEquals(19, TablistLayoutRuntime.indexOf(0, 19, 20));
        assertEquals(20, TablistLayoutRuntime.indexOf(1, 0, 20));
        assertEquals(79, TablistLayoutRuntime.indexOf(3, 19, 20));
        assertEquals("&6&lStaff", runtime.cell(0).text());
        assertEquals("&9discord.gg/example", runtime.cell(79).text());
        assertEquals(Integer.valueOf(0), runtime.cell(79).ping());
        assertEquals("MHF_Question", runtime.cell(0).skin());
        assertEquals("", runtime.cell(5).text());
    }

    @Test
    void everyCellCarriesADeterministicIdentityAndDescendingOrder() {
        TablistLayoutRuntime runtime = runtime(layout(1, 3, List.of(), null));

        assertEquals(UUID.nameUUIDFromBytes("gloss:tab:0".getBytes(StandardCharsets.UTF_8)), runtime.cell(0).id());
        assertEquals(UUID.nameUUIDFromBytes("gloss:tab:2".getBytes(StandardCharsets.UTF_8)), runtime.cell(2).id());
        assertEquals(" gloss_slot_0", runtime.cell(0).name());
        assertEquals(" gloss_slot_2", runtime.cell(2).name());
        assertEquals(100_000, runtime.cell(0).listOrder());
        assertEquals(99_998, runtime.cell(2).listOrder());
    }

    @Test
    void thePlayerBlockReservesItsColumnsInReadingOrder() {
        TablistLayoutRuntime runtime = runtime(layout(4, 20, List.of(),
            new TablistDoc.Players(1, 2, 20, "true", "hide")));

        assertEquals(40, runtime.playerCells().size());
        assertEquals(20, runtime.playerCells().get(0).index());
        assertEquals(21, runtime.playerCells().get(1).index());
        assertEquals(59, runtime.playerCells().get(39).index());
        assertTrue(runtime.isPlayerCell(20));
        assertFalse(runtime.isPlayerCell(19));
    }

    @Test
    void aLayoutWithNoPlayerBlockHasNoPlayerCells() {
        TablistLayoutRuntime runtime = runtime(layout(1, 2, List.of(), null));

        assertEquals(List.of(), runtime.playerCells());
    }

    @Test
    void aSlotOutsideTheGridIsRefused() {
        assertThrows(IllegalArgumentException.class,
            () -> layout(2, 2, List.of(new TablistDoc.Slot(5, 0, "x", null, null)), null));
        assertThrows(IllegalArgumentException.class,
            () -> layout(2, 2, List.of(new TablistDoc.Slot(0, 9, "x", null, null)), null));
    }

    @Test
    void twoSlotsInOneCellAreRefused() {
        assertThrows(IllegalArgumentException.class, () -> layout(2, 2, List.of(
            new TablistDoc.Slot(0, 0, "one", null, null),
            new TablistDoc.Slot(0, 0, "two", null, null)), null));
    }

    @Test
    void aPlayerBlockThatDoesNotFitIsRefused() {
        assertThrows(IllegalArgumentException.class,
            () -> layout(2, 2, List.of(), new TablistDoc.Players(1, 2, 2, "true", "hide")));
        assertThrows(IllegalArgumentException.class,
            () -> layout(2, 2, List.of(), new TablistDoc.Players(0, 1, 9, "true", "hide")));
    }

    @Test
    void aGridBiggerThanTheClientShowsIsRefused() {
        assertThrows(IllegalArgumentException.class, () -> layout(5, 20, List.of(), null));
        assertThrows(IllegalArgumentException.class, () -> layout(4, 21, List.of(), null));
    }

    @Test
    void anUnknownOverflowModeIsRefused() {
        assertThrows(IllegalArgumentException.class,
            () -> new TablistDoc.Players(0, 1, 1, "true", "wrap"));
        assertTrue(new TablistDoc.Players(0, 1, 1, "true", "count").countsOverflow());
        assertFalse(new TablistDoc.Players(0, 1, 1, "true", null).countsOverflow());
    }

    @Test
    void aLayoutHidesItselfFromBedrockUnlessTheAuthorSaysOtherwise() {
        assertEquals("!viewer.bedrock", layout(1, 1, List.of(), null).show().expression());
        assertNull(TablistLayoutRuntime.compile(TablistDoc.Layout.DISABLED));
    }

    private static TablistLayoutRuntime runtime(TablistDoc.Layout layout) {
        return TablistLayoutRuntime.compile(layout);
    }

    private static TablistDoc.Layout layout(int columns, int rows, List<TablistDoc.Slot> slots,
                                            TablistDoc.Players players) {
        return new TablistDoc.Layout(true, columns, rows, slots, players, null);
    }
}
