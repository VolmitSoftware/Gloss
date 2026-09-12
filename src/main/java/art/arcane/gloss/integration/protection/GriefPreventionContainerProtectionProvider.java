package art.arcane.gloss.integration.protection;

import org.bukkit.Location;
import org.bukkit.entity.Player;
import org.bukkit.plugin.Plugin;

import java.lang.reflect.Field;
import java.lang.reflect.Method;

/**
 * GriefPrevention. Unclaimed ground is open; inside a claim the viewer needs the Inventory
 * permission. {@code Claim#checkPermission} answers with a message supplier when it refuses and null
 * when it allows.
 */
final class GriefPreventionContainerProtectionProvider extends ClaimContainerProtectionProvider {
    GriefPreventionContainerProtectionProvider(Plugin griefPrevention) throws ReflectiveOperationException {
        this(claimAt(griefPrevention), inventoryPermission(griefPrevention));
    }

    GriefPreventionContainerProtectionProvider(Lookup lookup, Access access) {
        super(lookup, access);
    }

    private static Lookup claimAt(Plugin griefPrevention) throws ReflectiveOperationException {
        Object dataStore = dataStore(griefPrevention);
        Class<?> claimClass = claimClass(griefPrevention);
        Method getClaimAt = dataStore.getClass().getMethod("getClaimAt", Location.class, boolean.class, claimClass);
        return location -> getClaimAt.invoke(dataStore, location, false, null);
    }

    private static Access inventoryPermission(Plugin griefPrevention) throws ReflectiveOperationException {
        ClassLoader loader = griefPrevention.getClass().getClassLoader();
        Class<?> permissionClass = Class.forName("me.ryanhamshire.GriefPrevention.ClaimPermission", true, loader);
        Object inventory = Enum.valueOf(permissionClass.asSubclass(Enum.class), "Inventory");
        Method checkPermission = claimClass(griefPrevention)
            .getMethod("checkPermission", Player.class, permissionClass, org.bukkit.event.Event.class);
        return (claim, player) -> checkPermission.invoke(claim, player, inventory, null) == null;
    }

    private static Object dataStore(Plugin griefPrevention) throws ReflectiveOperationException {
        Field instanceField = griefPrevention.getClass().getField("instance");
        Object instance = instanceField.get(null);
        return instance.getClass().getField("dataStore").get(instance);
    }

    private static Class<?> claimClass(Plugin griefPrevention) throws ReflectiveOperationException {
        return Class.forName("me.ryanhamshire.GriefPrevention.Claim", true,
            griefPrevention.getClass().getClassLoader());
    }
}
