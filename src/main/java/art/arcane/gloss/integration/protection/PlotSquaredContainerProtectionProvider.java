package art.arcane.gloss.integration.protection;

import org.bukkit.World;
import org.bukkit.plugin.Plugin;

import java.lang.reflect.Method;
import java.util.UUID;

/**
 * PlotSquared. Ground that belongs to no plot is not PlotSquared's business and stays open; inside a
 * plot only the owner and the players added to it may look into a container.
 */
final class PlotSquaredContainerProtectionProvider extends ClaimContainerProtectionProvider {
    PlotSquaredContainerProtectionProvider(Plugin plotSquared) throws ReflectiveOperationException {
        this(plotAt(plotSquared), added(plotSquared));
    }

    PlotSquaredContainerProtectionProvider(Lookup lookup, Access access) {
        super(lookup, access);
    }

    private static Lookup plotAt(Plugin plotSquared) throws ReflectiveOperationException {
        Class<?> locationClass = Class.forName("com.plotsquared.core.location.Location", true,
            plotSquared.getClass().getClassLoader());
        Method at = locationClass.getMethod("at", String.class, int.class, int.class, int.class);
        Method getPlot = locationClass.getMethod("getPlot");
        return location -> {
            World world = location.getWorld();
            if (world == null) {
                return null;
            }
            return getPlot.invoke(at.invoke(null, world.getName(), location.getBlockX(),
                location.getBlockY(), location.getBlockZ()));
        };
    }

    private static Access added(Plugin plotSquared) throws ReflectiveOperationException {
        Method isAdded = Class.forName("com.plotsquared.core.plot.Plot", true,
            plotSquared.getClass().getClassLoader()).getMethod("isAdded", UUID.class);
        return (plot, player) -> (boolean) isAdded.invoke(plot, player.getUniqueId());
    }
}
