package art.arcane.gloss.menu.icon;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class ItemMenuIconContextTest {
    @Test
    void flatItemsDrawTheirInventoryModel() {
        assertEquals(ItemMenuIcon.CONTEXT_GUI, ItemMenuIcon.displayContext(false));
    }

    @Test
    void blocksKeepTheirFullModel() {
        assertEquals(ItemMenuIcon.CONTEXT_NONE, ItemMenuIcon.displayContext(true));
    }
}
