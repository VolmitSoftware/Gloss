package art.arcane.gloss.api;

import org.bukkit.Location;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Player;

import java.util.List;
import java.util.Objects;
import java.util.function.Function;
import java.util.function.LongFunction;
import java.util.function.Supplier;

public interface TemporaryHologram extends Hologram {
    void setStyle(IconDisplayStyle style);

    void setBox(HologramBox box);

    void setRenderedLines(List<String> lines);

    void setRenderedParticleText(String text, List<ParticleTextSpan> spans);

    /**
     * Drives this hologram's text from an async packet loop instead of the tick scheduler. The
     * binder is asked for the already rendered lines at a wall clock instant and may be called far
     * more often than once per tick, so it must be cheap, thread safe and side effect free. Player
     * typed content is safe here: frames are serialized with section codes only.
     */
    void bindRenderedFrames(LongFunction<List<String>> frames);

    void bindRenderedViewerText(Function<Player, RenderedText> lines);

    /**
     * Samples the position binder on the owning entity's scheduler. Any Bukkit state read by the
     * binder must belong to that owner.
     */
    void bindPosition(Entity owner, Supplier<Location> binder);

    /**
     * Samples the presentation binder on the owning entity's scheduler. Any Bukkit state read by
     * the binder must belong to that owner.
     */
    void bindPresentation(Entity owner, Supplier<HologramPresentation> binder);

    long remainingMs();

    HologramViewers viewers();

    void destroy();

    record RenderedText(String text, List<ParticleTextSpan> spans, LongFunction<String> frames) {
        public RenderedText {
            Objects.requireNonNull(text, "Rendered text may not be null.");
            spans = List.copyOf(spans);
            for (ParticleTextSpan span : spans) {
                if (span.end() > text.length()) {
                    throw new IllegalArgumentException("Rendered particle span exceeds the text length.");
                }
            }
        }
    }
}
