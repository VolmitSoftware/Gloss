package art.arcane.gloss.sky;

import org.bukkit.entity.Player;

/**
 * Applies a per-viewer world border. Paper has a Bukkit-typed API for it; Spigot does not, so the
 * packet path is the fallback. Both sides implement this so the service never branches.
 */
public interface BorderApplier {
    void apply(Player viewer, SkyOverride.Border border);

    void restore(Player viewer);
}
