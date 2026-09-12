package art.arcane.gloss.inventory;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;

/** How a list source is cut into pages, including the edges an author will hit first. */
class InventoryPagingTest {

    private static final List<Object> SEVEN = List.of("a", "b", "c", "d", "e", "f", "g");

    @Test
    void aPageIsTheWindowOfEntriesAtThatOffset() {
        assertEquals(List.of("a", "b", "c"), InventoryPager.page(SEVEN, 0, 3));
        assertEquals(List.of("d", "e", "f"), InventoryPager.page(SEVEN, 1, 3));
        assertEquals(List.of("g"), InventoryPager.page(SEVEN, 2, 3));
    }

    @Test
    void aPagePastTheEndIsEmptyRatherThanAnError() {
        assertEquals(List.of(), InventoryPager.page(SEVEN, 9, 3));
        assertEquals(List.of(), InventoryPager.page(List.of(), 0, 3));
    }

    @Test
    void aNegativePageReadsAsTheFirst() {
        assertEquals(List.of("a", "b", "c"), InventoryPager.page(SEVEN, -4, 3));
    }

    @Test
    void theCountOfPagesIsNeverZero() {
        assertEquals(3, InventoryPager.pages(7, 3));
        assertEquals(1, InventoryPager.pages(3, 3));
        assertEquals(1, InventoryPager.pages(0, 3));
        assertEquals(1, InventoryPager.pages(7, 0));
    }

    @Test
    void aPageIsClampedIntoRange() {
        assertEquals(2, InventoryPager.clamp(9, 7, 3));
        assertEquals(0, InventoryPager.clamp(-2, 7, 3));
        assertEquals(1, InventoryPager.clamp(1, 7, 3));
    }

    @Test
    void nextAndPreviousStopAtTheEnds() {
        assertEquals(1, InventoryPager.target("next", 0, 7, 3));
        assertEquals(2, InventoryPager.target("next", 2, 7, 3));
        assertEquals(0, InventoryPager.target("prev", 0, 7, 3));
        assertEquals(1, InventoryPager.target("prev", 2, 7, 3));
        assertEquals(2, InventoryPager.target("2", 0, 7, 3), "an absolute target is zero based");
        assertEquals(0, InventoryPager.target("nonsense", 0, 7, 3));
    }
}
