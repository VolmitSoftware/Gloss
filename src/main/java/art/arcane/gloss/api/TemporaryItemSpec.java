package art.arcane.gloss.api;

import org.bukkit.Location;
import org.bukkit.inventory.ItemStack;

import java.util.Objects;

public record TemporaryItemSpec(Location location, ItemStack item, long durationMs, double range, float scale) {
    public TemporaryItemSpec {
        location = Objects.requireNonNull(location, "location").clone();
        item = Objects.requireNonNull(item, "item").clone();
        if (location.getWorld() == null || !Double.isFinite(location.getX()) || !Double.isFinite(location.getY())
                || !Double.isFinite(location.getZ()) || item.getType().isAir() || durationMs < 100 || durationMs > 30_000
                || !Double.isFinite(range) || range < 1 || range > 64
                || !Float.isFinite(scale) || scale < 0.1F || scale > 2F) {
            throw new IllegalArgumentException("Invalid temporary item presentation");
        }
        item.setAmount(1);
    }

    @Override
    public Location location() {
        return location.clone();
    }

    @Override
    public ItemStack item() {
        return item.clone();
    }
}

