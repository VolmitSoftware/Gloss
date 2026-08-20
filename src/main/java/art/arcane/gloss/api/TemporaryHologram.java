package art.arcane.gloss.api;

import org.bukkit.Location;

import java.util.List;
import java.util.function.Supplier;

public interface TemporaryHologram extends Hologram {
    void setRenderedLines(List<String> lines);

    void bindPosition(Supplier<Location> binder);

    void bindPresentation(Supplier<HologramPresentation> binder);

    long remainingMs();

    HologramViewers viewers();

    void destroy();
}
