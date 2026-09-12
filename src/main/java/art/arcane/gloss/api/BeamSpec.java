package art.arcane.gloss.api;

import io.github.retrooper.packetevents.util.SpigotConversionUtil;
import org.bukkit.Bukkit;

/**
 * How a beam looks: the block state it is built from and its thickness in blocks. The block state
 * is resolved once by {@link #ofMaterial} so a beam that is re-shaped every other tick never
 * repeats a registry lookup.
 */
public record BeamSpec(int blockState, double width) {
    public BeamSpec {
        width = Double.isFinite(width) ? Math.clamp(width, 0.01D, 8.0D) : 0.25D;
    }

    /** @throws IllegalArgumentException when the material names no block on this server */
    public static BeamSpec ofMaterial(String material, double width) {
        return new BeamSpec(SpigotConversionUtil.fromBukkitBlockData(
            Bukkit.createBlockData(material)).getGlobalId(), width);
    }
}
