package art.arcane.gloss.api;

import org.bukkit.Location;

import java.util.List;
import java.util.function.LongFunction;
import java.util.function.Supplier;

public interface TemporaryHologram extends Hologram {
    void setRenderedLines(List<String> lines);

    /**
     * Drives this hologram's text from an async packet loop instead of the tick scheduler. The
     * binder is asked for the already rendered lines at a wall clock instant and may be called far
     * more often than once per tick, so it must be cheap, thread safe and side effect free. Player
     * typed content is safe here: frames are serialized with section codes only.
     */
    void bindRenderedFrames(LongFunction<List<String>> frames);

    void bindPosition(Supplier<Location> binder);

    void bindPresentation(Supplier<HologramPresentation> binder);

    long remainingMs();

    HologramViewers viewers();

    void destroy();
}
