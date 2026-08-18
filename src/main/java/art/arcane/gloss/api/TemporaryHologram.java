package art.arcane.gloss.api;

import org.bukkit.Location;

import java.util.function.Supplier;

public interface TemporaryHologram extends Hologram {
    void bindPosition(Supplier<Location> binder);

    long remainingMs();

    HologramViewers viewers();

    void destroy();
}
