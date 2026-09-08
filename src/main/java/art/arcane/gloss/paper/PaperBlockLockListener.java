package art.arcane.gloss.paper;

import art.arcane.gloss.preview.ContainerPreviewAccess;
import io.papermc.paper.event.block.BlockLockCheckEvent;
import org.bukkit.Bukkit;
import org.bukkit.block.Block;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;

import java.lang.reflect.Constructor;
import java.lang.reflect.InvocationTargetException;

public final class PaperBlockLockListener implements ContainerPreviewAccess.LockCheck {

    private static final Constructor<?> EVENT_CONSTRUCTOR = resolveEventConstructor();

    @Override
    public ContainerPreviewAccess.LockCheckDecision check(Block block, Player viewer, ItemStack keyItem) {
        BlockLockCheckEvent event = newEvent(block, viewer);
        event.setKeyItem(keyItem);
        Bukkit.getPluginManager().callEvent(event);
        ItemStack customKeyItem = event.isUsingCustomKeyItemStack() ? event.getKeyItem() : null;
        return new ContainerPreviewAccess.LockCheckDecision(event.getResult(), customKeyItem);
    }

    private static BlockLockCheckEvent newEvent(Block block, Player viewer) {
        Object[] arguments = new Object[EVENT_CONSTRUCTOR.getParameterCount()];
        arguments[0] = block;
        arguments[1] = viewer;
        try {
            return (BlockLockCheckEvent) EVENT_CONSTRUCTOR.newInstance(arguments);
        } catch (InvocationTargetException failure) {
            throw new IllegalStateException(failure.getCause());
        } catch (ReflectiveOperationException failure) {
            throw new IllegalStateException(failure);
        }
    }

    private static Constructor<?> resolveEventConstructor() {
        Constructor<?> resolved = null;
        for (Constructor<?> candidate : BlockLockCheckEvent.class.getConstructors()) {
            Class<?>[] parameters = candidate.getParameterTypes();
            if (parameters.length < 2 || parameters[0] != Block.class || parameters[1] != Player.class) {
                continue;
            }
            if (resolved == null || parameters.length < resolved.getParameterCount()) {
                resolved = candidate;
            }
        }
        if (resolved == null) {
            throw new IllegalStateException(
                    "BlockLockCheckEvent exposes no (Block, Player) constructor on this server");
        }
        return resolved;
    }
}
