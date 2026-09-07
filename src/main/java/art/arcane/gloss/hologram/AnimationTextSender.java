package art.arcane.gloss.hologram;

import org.bukkit.entity.Player;

import java.util.List;
import java.util.function.BooleanSupplier;

public interface AnimationTextSender {
    void send(Batch batch);

    /**
     * One fan-out. {@code live} reports whether the publication that produced this text is still
     * the current one; the sender polls it per recipient so a retired publication cannot finish
     * writing stale text over whatever replaced it on the same entity id.
     */
    record Batch(List<Player> viewers, int entityId, String legacyText, TextCodec codec,
                 BooleanSupplier live) {
    }
}
