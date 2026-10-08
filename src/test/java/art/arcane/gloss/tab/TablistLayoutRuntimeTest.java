package art.arcane.gloss.tab;

import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
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
            new TablistLayoutDefinition.Slot(0, 0, "&6&lStaff", "MHF_Question", null, true),
            new TablistLayoutDefinition.Slot(3, 19, "&9discord.gg/example", null, 0, true)), null));

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
            section(1, 2, 20, "true", "hide", null)));

        assertEquals(40, runtime.sections().getFirst().cells().size());
        assertEquals(20, runtime.sections().getFirst().cells().get(0).index());
        assertEquals(21, runtime.sections().getFirst().cells().get(1).index());
        assertEquals(59, runtime.sections().getFirst().cells().get(39).index());
        assertEquals(20, runtime.sections().getFirst().cells().getFirst().index());
        assertEquals(59, runtime.sections().getFirst().cells().getLast().index());
    }

    @Test
    void aLayoutWithNoPlayerBlockHasNoPlayerCells() {
        TablistLayoutRuntime runtime = runtime(layout(1, 2, List.of(), null));

        assertEquals(List.of(), runtime.sections());
    }

    @Test
    void aSlotOutsideTheGridIsRefused() {
        assertThrows(IllegalArgumentException.class,
            () -> layout(2, 2, List.of(new TablistLayoutDefinition.Slot(5, 0, "x", null, null, true)), null));
        assertThrows(IllegalArgumentException.class,
            () -> layout(2, 2, List.of(new TablistLayoutDefinition.Slot(0, 9, "x", null, null, true)), null));
    }

    @Test
    void twoSlotsInOneCellAreRefused() {
        assertThrows(IllegalArgumentException.class, () -> layout(2, 2, List.of(
            new TablistLayoutDefinition.Slot(0, 0, "one", null, null, true),
            new TablistLayoutDefinition.Slot(0, 0, "two", null, null, true)), null));
    }

    @Test
    void aPlayerBlockThatDoesNotFitIsRefused() {
        assertThrows(IllegalArgumentException.class,
            () -> layout(2, 2, List.of(), section(1, 2, 2, "true", "hide", null)));
        assertThrows(IllegalArgumentException.class,
            () -> layout(2, 2, List.of(), section(0, 1, 9, "true", "hide", null)));
    }

    @Test
    void aGridBiggerThanTheClientShowsIsRefused() {
        assertThrows(IllegalArgumentException.class, () -> layout(5, 20, List.of(), null));
        assertThrows(IllegalArgumentException.class, () -> layout(4, 21, List.of(), null));
    }

    @Test
    void anUnknownOverflowModeIsRefused() {
        assertThrows(IllegalArgumentException.class,
            () -> section(0, 1, 1, "true", "wrap", null));
        assertTrue(section(0, 1, 1, "true", "count", null).countsOverflow());
        assertFalse(section(0, 1, 1, "true", null, null).countsOverflow());
    }

    @Test
    void aLayoutHidesItselfFromBedrockUnlessTheAuthorSaysOtherwise() {
        assertEquals("!viewer.bedrock", layout(1, 1, List.of(), null).show().expression());
        assertNull(TablistLayoutRuntime.compile(TablistDoc.Layout.DISABLED));
    }

    @Test
    void clientGeometryComesFromEntryCountAndRejectsAbsentTailCells() {
        TablistLayoutDefinition.LayoutPresentation odd = new TablistLayoutDefinition.LayoutPresentation(21, List.of(), List.of(), Map.of());
        assertEquals(2, odd.columns());
        assertEquals(11, odd.rows());
        assertThrows(IllegalArgumentException.class, () -> new TablistLayoutDefinition.LayoutPresentation(21,
            List.of(new TablistLayoutDefinition.Slot(1, 10, "absent", null, null, true)), List.of(), Map.of()));
    }

    @Test
    void fixedSlotsCannotOverlapRosterSections() {
        assertThrows(IllegalArgumentException.class, () -> new TablistLayoutDefinition.LayoutPresentation(20,
            List.of(new TablistLayoutDefinition.Slot(0, 0, "fixed", null, null, true)),
            List.of(section(0, 1, 20, "true", "hide", null)), Map.of()));
    }

    private static TablistLayoutRuntime runtime(TablistDoc.Layout layout) {
        return TablistLayoutRuntime.compile(layout);
    }

    private static TablistDoc.Layout layout(int columns, int rows, List<TablistLayoutDefinition.Slot> slots,
                                            TablistLayoutDefinition.Section players) {
        return new TablistDoc.Layout(true, columns * rows, slots, players == null ? List.of() : List.of(players), null, Map.of(), List.of());
    }
    private static TablistLayoutDefinition.Section section(int column, int columns, int rows, String filter, String overflow,
                                               String overflowFormat) {
        return new TablistLayoutDefinition.Section("players", column, 0, columns, rows, filter, null, List.of(), overflow,
            overflowFormat, false, null, true);
    }

}
