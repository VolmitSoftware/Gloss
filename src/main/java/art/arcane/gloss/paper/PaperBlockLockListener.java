package art.arcane.gloss.paper;

import art.arcane.gloss.preview.ContainerPreviewAccess;
import io.papermc.paper.event.block.BlockLockCheckEvent;
import org.bukkit.Bukkit;
import org.bukkit.block.Block;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;

public final class PaperBlockLockListener implements ContainerPreviewAccess.LockCheck {

    @Override
    public ContainerPreviewAccess.LockCheckDecision check(Block block, Player viewer, ItemStack keyItem) {
        BlockLockCheckEvent event = new BlockLockCheckEvent(block, viewer, null, null);
        event.setKeyItem(keyItem);
        Bukkit.getPluginManager().callEvent(event);
        ItemStack customKeyItem = event.isUsingCustomKeyItemStack() ? event.getKeyItem() : null;
        return new ContainerPreviewAccess.LockCheckDecision(event.getResult(), customKeyItem);
    }
}
