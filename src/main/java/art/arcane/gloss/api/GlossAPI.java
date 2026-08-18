package art.arcane.gloss.api;

import org.bukkit.Location;
import org.bukkit.entity.Player;

import java.util.List;
import java.util.Optional;

public interface GlossAPI {
    static GlossAPI get() {
        return GlossAPIProvider.get();
    }

    Hologram createHologram(String id, Location location);

    Optional<Hologram> hologram(String id);

    boolean hasHologram(String id);

    void deleteHologram(String id);

    List<Hologram> holograms();

    TemporaryHologram createTemporaryHologram(String id, Location initial, long durationMs);

    double stackSpread();

    String filter(Player player, String raw);

    Optional<String> boardFor(Player player);

    void setBoard(Player player, String boardId);

    void clearBoard(Player player);

    void setTab(Player player, String header, String footer);

    void resetTab(Player player);
}
