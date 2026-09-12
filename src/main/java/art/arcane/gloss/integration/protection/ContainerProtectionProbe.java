package art.arcane.gloss.integration.protection;

import org.bukkit.block.Block;
import org.bukkit.block.BlockFace;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Player;
import org.bukkit.event.Event;
import org.bukkit.event.block.Action;
import org.bukkit.event.player.PlayerInteractEntityEvent;
import org.bukkit.event.player.PlayerInteractEvent;

/**
 * The synthetic interact events the container-preview access probe fires, and the marker Gloss's
 * own listeners test before treating one as a viewer's click.
 *
 * <p>The events are real Bukkit events on the ordinary handler lists, which is the point: every
 * other plugin answers them exactly as it would a right-click. They carry a Gloss-only subtype so
 * Gloss itself can tell them apart — without it the probe runs the viewer's hologram and panel
 * action list on every preview refresh, and the resulting cancel reads back as a deny.
 */
public final class ContainerProtectionProbe {
    private ContainerProtectionProbe() {
    }

    /** True when {@code event} is Gloss asking the server a question, not a viewer clicking. */
    public static boolean isProbe(Event event) {
        return event instanceof BlockProbe || event instanceof EntityProbe;
    }

    public static PlayerInteractEvent blockProbe(Player player, Block block) {
        return new BlockProbe(player, block);
    }

    public static PlayerInteractEntityEvent entityProbe(Player player, Entity entity) {
        return new EntityProbe(player, entity);
    }

    private static final class BlockProbe extends PlayerInteractEvent {
        private BlockProbe(Player player, Block block) {
            super(player, Action.RIGHT_CLICK_BLOCK, player.getInventory().getItemInMainHand(),
                block, BlockFace.UP);
        }
    }

    private static final class EntityProbe extends PlayerInteractEntityEvent {
        private EntityProbe(Player player, Entity entity) {
            super(player, entity);
        }
    }
}
