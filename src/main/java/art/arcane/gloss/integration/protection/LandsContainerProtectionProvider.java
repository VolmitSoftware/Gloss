package art.arcane.gloss.integration.protection;

import org.bukkit.Location;
import org.bukkit.plugin.Plugin;

import java.lang.reflect.Method;
import java.util.UUID;

/**
 * Lands. Wilderness has no area and is open; inside a land the viewer's role must carry the
 * INTERACT_CONTAINER flag.
 */
final class LandsContainerProtectionProvider extends ClaimContainerProtectionProvider {
    LandsContainerProtectionProvider(Plugin lands) throws ReflectiveOperationException {
        this(areaAt(lands), containerFlag(lands));
    }

    LandsContainerProtectionProvider(Lookup lookup, Access access) {
        super(lookup, access);
    }

    private static Lookup areaAt(Plugin lands) throws ReflectiveOperationException {
        Class<?> integrationClass = Class.forName("me.angeschossen.lands.api.LandsIntegration", true,
            lands.getClass().getClassLoader());
        Object integration = integrationClass.getMethod("of", Plugin.class).invoke(null, lands);
        Method getArea = integrationClass.getMethod("getArea", Location.class);
        return location -> getArea.invoke(integration, location);
    }

    private static Access containerFlag(Plugin lands) throws ReflectiveOperationException {
        ClassLoader loader = lands.getClass().getClassLoader();
        Class<?> flagsClass = Class.forName("me.angeschossen.lands.api.flags.type.Flags", true, loader);
        Object containerFlag = flagsClass.getField("INTERACT_CONTAINER").get(null);
        Method hasRoleFlag = roleFlagCheck(Class.forName("me.angeschossen.lands.api.land.Area", true, loader));
        return (area, player) -> (boolean) hasRoleFlag.invoke(area, player.getUniqueId(), containerFlag);
    }

    /**
     * Lands has moved the role-flag parameter type between releases, so the method is matched by
     * name and by its {@code (UUID, flag)} shape rather than by a fixed signature.
     */
    private static Method roleFlagCheck(Class<?> areaClass) throws ReflectiveOperationException {
        for (Method candidate : areaClass.getMethods()) {
            if (candidate.getName().equals("hasRoleFlag") && candidate.getParameterCount() == 2
                && candidate.getParameterTypes()[0] == UUID.class) {
                return candidate;
            }
        }
        throw new NoSuchMethodException(areaClass.getName() + "#hasRoleFlag(UUID, RoleFlag)");
    }
}
