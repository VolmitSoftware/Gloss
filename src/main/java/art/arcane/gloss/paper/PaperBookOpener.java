package art.arcane.gloss.paper;

import art.arcane.gloss.menu.action.BookOpener;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;

import java.lang.reflect.Method;

/**
 * Paper's own book opener. The method is probed in the constructor so a server that advertises the
 * Paper API without this overload is treated as absent rather than failing on the first click.
 */
public final class PaperBookOpener implements BookOpener {
    private final Method openBook;

    public PaperBookOpener() throws NoSuchMethodException {
        this.openBook = Player.class.getMethod("openBook", ItemStack.class);
    }

    @Override
    public boolean open(Player viewer, ItemStack book) {
        try {
            openBook.invoke(viewer, book);
            return true;
        } catch (ReflectiveOperationException | RuntimeException | LinkageError failure) {
            return false;
        }
    }
}
