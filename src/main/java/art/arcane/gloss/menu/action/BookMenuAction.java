package art.arcane.gloss.menu.action;

import art.arcane.gloss.Gloss;
import art.arcane.gloss.config.action.BookActionData;
import art.arcane.gloss.service.PaperBridges;
import art.arcane.gloss.text.TextPipeline;
import art.arcane.gloss.util.common.PacketUtils;
import art.arcane.volmlib.util.scheduling.FoliaScheduler;
import com.github.retrooper.packetevents.protocol.player.InteractionHand;
import com.github.retrooper.packetevents.wrapper.play.server.WrapperPlayServerOpenBook;
import com.github.retrooper.packetevents.wrapper.play.server.WrapperPlayServerSetSlot;
import io.github.retrooper.packetevents.util.SpigotConversionUtil;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.BookMeta;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * Opens a written book. Paper can open one straight from a stack; on Spigot the book is pushed into
 * the main hand for exactly one packet, opened, and the real item is put back, so the player never
 * sees the swap and never holds the book.
 */
public final class BookMenuAction extends MenuAction<BookActionData> {

  private static final String PAPER_PLAYER = "org.bukkit.entity.Player";
  private static final String BRIDGE = "art.arcane.gloss.paper.PaperBookOpener";
  private static final int MAIN_HAND_WINDOW = 0;
  private static final int MAIN_HAND_STATE = 0;

  public BookMenuAction(BookActionData data) {
    super(data);
  }

  @Override
  public ActionOutcome execute(ActionContext context) {
    Player viewer = context.player();
    ItemStack book = build(viewer);
    if (book == null) {
      return ActionOutcome.CONTINUE;
    }
    Optional<BookOpener> bridge = PaperBridges.load(PAPER_PLAYER, BRIDGE, BookOpener.class);
    if (bridge.isPresent() && bridge.get().open(viewer, book)) {
      return ActionOutcome.CONTINUE;
    }
    openThroughPackets(viewer, book);
    return ActionOutcome.CONTINUE;
  }

  private ItemStack build(Player viewer) {
    try {
      ItemStack book = new ItemStack(Material.WRITTEN_BOOK);
      List<String> pages = new ArrayList<>(data.pages().size());
      for (String page : data.pages()) {
        pages.add(TextPipeline.menuText(viewer, page));
      }
      if (!(book.getItemMeta() instanceof BookMeta meta)) {
        return null;
      }
      meta.setTitle(TextPipeline.menuText(viewer, data.title()));
      meta.setAuthor(TextPipeline.menuText(viewer, data.author()));
      meta.setPages(pages);
      book.setItemMeta(meta);
      return book;
    } catch (RuntimeException | LinkageError failure) {
      Gloss.logExceptionStackThrottled(false, "book-action", failure, "A book action could not build its book.");
      return null;
    }
  }

  /**
   * The Spigot path. The slot is restored on the next tick, after the client has drawn the book,
   * so the swap is never visible and the real item is never lost.
   */
  private void openThroughPackets(Player viewer, ItemStack book) {
    int slot = viewer.getInventory().getHeldItemSlot();
    ItemStack held = viewer.getInventory().getItem(slot);
    int windowSlot = 36 + slot;
    PacketUtils.send(viewer, new WrapperPlayServerSetSlot(MAIN_HAND_WINDOW, MAIN_HAND_STATE, windowSlot,
        SpigotConversionUtil.fromBukkitItemStack(book)));
    PacketUtils.send(viewer, new WrapperPlayServerOpenBook(InteractionHand.MAIN_HAND));
    FoliaScheduler.runEntity(Gloss.instance, viewer, () -> PacketUtils.send(viewer,
        new WrapperPlayServerSetSlot(MAIN_HAND_WINDOW, MAIN_HAND_STATE, windowSlot,
            SpigotConversionUtil.fromBukkitItemStack(held))), 1L);
  }
}
