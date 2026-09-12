package art.arcane.gloss.inventory;

import art.arcane.gloss.enums.NavigationMode;
import org.junit.jupiter.api.Test;

import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

/** The per-player window stack: what back and home reach, and what a close leaves behind. */
class InventoryNavigationHistoryTest {

    private final InventoryNavigationHistory history = new InventoryNavigationHistory();
    private final UUID player = UUID.randomUUID();

    @Test
    void pushRemembersWhereTheViewerCameFrom() {
        history.record(player, "shop");
        history.commit(player, NavigationMode.PUSH, "shop");
        history.record(player, "shop/weapons");

        assertEquals("shop", history.resolve(player, NavigationMode.BACK, null));
    }

    @Test
    void replaceLeavesTheStackAlone() {
        history.record(player, "shop");
        history.commit(player, NavigationMode.PUSH, "shop");
        history.record(player, "shop/weapons");
        history.commit(player, NavigationMode.REPLACE, "shop/weapons");
        history.record(player, "shop/armor");

        assertEquals("shop", history.resolve(player, NavigationMode.BACK, null));
    }

    @Test
    void homeReturnsToTheFirstWindowOfTheSession() {
        history.record(player, "shop");
        history.commit(player, NavigationMode.PUSH, "shop");
        history.record(player, "shop/weapons");
        history.commit(player, NavigationMode.PUSH, "shop/weapons");
        history.record(player, "shop/weapons/swords");

        assertEquals("shop", history.resolve(player, NavigationMode.HOME, null));
    }

    @Test
    void backWithNothingBehindItResolvesToNothing() {
        history.record(player, "shop");
        assertNull(history.resolve(player, NavigationMode.BACK, null));
    }

    @Test
    void pushAndPageResolveTheirOwnTargets() {
        history.record(player, "shop");
        assertEquals("shop/weapons", history.resolve(player, NavigationMode.PUSH, "shop/weapons"));
        assertNull(history.resolve(player, NavigationMode.CLOSE, null));
        assertNull(history.resolve(player, NavigationMode.PAGE, "next"),
            "paging stays inside the window it was clicked in");
    }

    @Test
    void closingForgetsTheWholeStack() {
        history.record(player, "shop");
        history.commit(player, NavigationMode.PUSH, "shop");
        history.record(player, "shop/weapons");
        history.forget(player);

        assertNull(history.resolve(player, NavigationMode.BACK, null));
        assertNull(history.current(player));
    }

    @Test
    void oneViewersStackIsNotAnothers() {
        UUID other = UUID.randomUUID();
        history.record(player, "shop");
        history.commit(player, NavigationMode.PUSH, "shop");
        history.record(player, "shop/weapons");
        history.record(other, "bank");

        assertEquals("shop", history.resolve(player, NavigationMode.BACK, null));
        assertNull(history.resolve(other, NavigationMode.BACK, null));
    }
}
