package art.arcane.gloss.api;

import org.bukkit.Location;
import org.bukkit.entity.Item;
import org.bukkit.entity.Player;
import org.bukkit.plugin.Plugin;

import java.util.List;
import java.util.Optional;
import java.util.Set;

public interface GlossAPI {
    static GlossAPI get() {
        return GlossAPIProvider.get();
    }

    HoloMenuHandle open(Plugin owner, Player player, HoloMenu menu);

    HoloMenuHandle open(Plugin owner, Player player, String menuId);

    boolean close(Player player);

    boolean isOpen(Player player);

    Set<String> menuIds();

    Hologram createHologram(String id, Location location);

    Optional<Hologram> hologram(String id);

    boolean hasHologram(String id);

    void deleteHologram(String id);

    List<Hologram> holograms();

    TemporaryHologram createTemporaryHologram(String id, Location initial, long durationMs);

    double stackSpread();

    String filter(Player player, String raw);

    void refreshDropName(Item item);

    void refreshDropName(Item item, String bundleFormat, int bundleEntryLimit);

    Optional<String> boardFor(Player player);

    void setBoard(Player player, String boardId);

    void clearBoard(Player player);

    void setTab(Player player, String header, String footer);

    void resetTab(Player player);
}
