package art.arcane.gloss.paper;

import art.arcane.gloss.sky.BorderApplier;
import art.arcane.gloss.sky.SkyOverride;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.WorldBorder;
import org.bukkit.entity.Player;

/**
 * Paper's per-player world border. {@code Player#setWorldBorder(WorldBorder)} is Bukkit-typed all
 * the way down, so nothing here crosses the relocation boundary; the constructor probes for the
 * method and refuses to load on a server that does not have it.
 */
public final class PaperWorldBorderBridge implements BorderApplier {
    public PaperWorldBorderBridge() throws NoSuchMethodException {
        Player.class.getMethod("setWorldBorder", WorldBorder.class);
    }

    @Override
    public void apply(Player viewer, SkyOverride.Border border) {
        World world = viewer.getWorld();
        WorldBorder personal = Bukkit.createWorldBorder();
        personal.setCenter(new Location(world, border.centerX(), 0.0D, border.centerZ()));
        personal.setSize(border.size());
        personal.setWarningDistance(border.warningBlocks());
        viewer.setWorldBorder(personal);
    }

    @Override
    public void restore(Player viewer) {
        viewer.setWorldBorder(null);
    }
}
