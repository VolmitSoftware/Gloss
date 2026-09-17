package art.arcane.gloss.integration.protection;

import art.arcane.volmlib.util.event.ProtectionProbe;
import org.bukkit.block.Block;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Player;
import org.bukkit.event.Event;
import org.bukkit.event.player.PlayerInteractEntityEvent;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.inventory.EquipmentSlot;

import java.util.Objects;
import java.util.function.Consumer;

/**
 * The answer for a server with no claim plugin Gloss has an adapter for: ask the server what would
 * happen if the viewer right-clicked the container, and treat a cancel or a denied block use as a
 * no. The result is read, never acted on, so nothing is opened, placed or consumed, and the event
 * carries the {@link ProtectionProbe} marker so Gloss's own click handler ignores it.
 */
final class InteractEventContainerProtectionProvider implements ContainerProtectionProvider {
    private final Consumer<Event> eventDispatcher;

    InteractEventContainerProtectionProvider(Consumer<Event> eventDispatcher) {
        this.eventDispatcher = Objects.requireNonNull(eventDispatcher, "eventDispatcher");
    }

    @Override
    public boolean canAccess(Player player, Block block) {
        PlayerInteractEvent event = ProtectionProbe.blockInteract(player, block, EquipmentSlot.HAND);
        eventDispatcher.accept(event);
        return !event.isCancelled() && event.useInteractedBlock() != Event.Result.DENY;
    }

    @Override
    public boolean canAccess(Player player, Entity entity) {
        PlayerInteractEntityEvent event = ProtectionProbe.entityInteract(player, entity);
        eventDispatcher.accept(event);
        return !event.isCancelled();
    }
}
