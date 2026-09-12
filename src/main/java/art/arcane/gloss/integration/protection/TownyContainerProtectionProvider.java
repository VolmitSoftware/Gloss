package art.arcane.gloss.integration.protection;

import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.block.Block;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Player;
import org.bukkit.plugin.Plugin;

import java.lang.reflect.Method;
import java.util.Objects;

/**
 * Towny. There is no claim object to fetch: opening a container is a SWITCH action, so the town's
 * switch permission for the container material at that spot is the whole answer. Container entities
 * are asked about as chests.
 */
final class TownyContainerProtectionProvider implements ContainerProtectionProvider {
    private static final Material ENTITY_CONTAINER = Material.CHEST;

    private final SwitchPermission switchPermission;

    TownyContainerProtectionProvider(Plugin towny) throws ReflectiveOperationException {
        this(resolve(towny));
    }

    TownyContainerProtectionProvider(SwitchPermission switchPermission) {
        this.switchPermission = Objects.requireNonNull(switchPermission, "switchPermission");
    }

    @Override
    public boolean canAccess(Player player, Block block) throws ReflectiveOperationException {
        return switchPermission.allows(player, block.getLocation(), block.getType());
    }

    @Override
    public boolean canAccess(Player player, Entity entity) throws ReflectiveOperationException {
        return switchPermission.allows(player, entity.getLocation(), ENTITY_CONTAINER);
    }

    private static SwitchPermission resolve(Plugin towny) throws ReflectiveOperationException {
        ClassLoader loader = towny.getClass().getClassLoader();
        Class<?> cacheUtil = Class.forName("com.palmergames.bukkit.towny.utils.PlayerCacheUtil", true, loader);
        Class<?> actionType = Class.forName("com.palmergames.bukkit.towny.object.TownyPermission$ActionType",
            true, loader);
        Object switchAction = Enum.valueOf(actionType.asSubclass(Enum.class), "SWITCH");
        Method getCachePermission = cacheUtil.getMethod("getCachePermission", Player.class, Location.class,
            Material.class, actionType);
        return (player, location, material) ->
            (boolean) getCachePermission.invoke(null, player, location, material, switchAction);
    }

    @FunctionalInterface
    interface SwitchPermission {
        boolean allows(Player player, Location location, Material material) throws ReflectiveOperationException;
    }
}
