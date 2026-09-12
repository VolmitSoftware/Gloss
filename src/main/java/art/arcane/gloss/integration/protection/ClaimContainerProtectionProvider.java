package art.arcane.gloss.integration.protection;

import org.bukkit.Location;
import org.bukkit.block.Block;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Player;

import java.util.Objects;

/**
 * The shape every claim plugin shares: something covers a spot, or nothing does, and the thing that
 * covers it decides whether this viewer may open a container inside it. Ground nothing covers is
 * open, because a claim plugin has no opinion about it.
 *
 * <p>Subclasses resolve the two calls out of the plugin's own classloader once; everything after
 * that is plain invocation.</p>
 */
abstract class ClaimContainerProtectionProvider implements ContainerProtectionProvider {
    private final Lookup lookup;
    private final Access access;

    ClaimContainerProtectionProvider(Lookup lookup, Access access) {
        this.lookup = Objects.requireNonNull(lookup, "lookup");
        this.access = Objects.requireNonNull(access, "access");
    }

    @Override
    public final boolean canAccess(Player player, Block block) throws ReflectiveOperationException {
        return allowed(player, block.getLocation());
    }

    @Override
    public final boolean canAccess(Player player, Entity entity) throws ReflectiveOperationException {
        return allowed(player, entity.getLocation());
    }

    private boolean allowed(Player player, Location location) throws ReflectiveOperationException {
        Object claim = lookup.at(location);
        return claim == null || access.allows(claim, player);
    }

    /** The claim, area or plot covering a location; null when nothing does. */
    @FunctionalInterface
    interface Lookup {
        Object at(Location location) throws ReflectiveOperationException;
    }

    /** Whether the viewer may open a container inside that claim. */
    @FunctionalInterface
    interface Access {
        boolean allows(Object claim, Player player) throws ReflectiveOperationException;
    }
}
