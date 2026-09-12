package art.arcane.gloss.paper;

import art.arcane.gloss.menu.action.BookOpener;
import art.arcane.gloss.service.PaperBridges;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.junit.jupiter.api.Test;

import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * A drift alarm on {@code Player#openBook(ItemStack)}. The bridge probes the method in its
 * constructor, so if Paper ever changes that signature the bridge goes absent and the Spigot packet
 * path takes over instead of throwing on the first click.
 */
class PaperBookOpenerTest {

    @Test
    void theBridgeLoadsAgainstTheCurrentPaperApi() {
        Optional<BookOpener> bridge = PaperBridges.load("org.bukkit.entity.Player",
            "art.arcane.gloss.paper.PaperBookOpener", BookOpener.class);
        assertTrue(bridge.isPresent(), "Player#openBook(ItemStack) is gone; the book action needs a new bridge");
    }

    @Test
    void probingTheMethodIsWhatDecidesPresence() {
        assertDoesNotThrow(() -> Player.class.getMethod("openBook", ItemStack.class));
    }
}
