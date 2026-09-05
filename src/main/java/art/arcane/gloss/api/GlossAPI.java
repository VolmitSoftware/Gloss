package art.arcane.gloss.api;

import org.bukkit.Location;
import org.bukkit.entity.Item;
import org.bukkit.entity.LivingEntity;
import org.bukkit.entity.Player;
import org.bukkit.plugin.Plugin;

import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

public interface GlossAPI {
    static GlossAPI get() {
        return GlossAPIProvider.get();
    }

    HoloMenuHandle open(Plugin owner, Player player, HoloMenu menu);

    HoloMenuHandle open(Plugin owner, Player player, String menuId);

    boolean close(Player player);

    boolean isOpen(Player player);

    Set<String> menuIds();

    AnchoredHologram createHologram(String id, Location location);

    Optional<AnchoredHologram> hologram(String id);

    boolean hasHologram(String id);

    void deleteHologram(String id);

    List<AnchoredHologram> holograms();

    TemporaryHologram createTemporaryHologram(String id, Location initial, long durationMs);

    double stackSpread();

    String filter(Player player, String raw);

    void refreshDropName(Item item);

    void refreshDropName(Item item, String bundleHeaderFormat, String bundleEntryFormat,
                         String bundleMoreFormat, int bundleEntryLimit);

    void removeDropPresentation(Item item);

    boolean refreshEntityOverlay(LivingEntity entity, int stackCount);

    void removeEntityOverlayStack(LivingEntity entity);

    boolean updateEntityInsight(Plugin owner, Player viewer, LivingEntity target,
                               List<String> details, long durationMs);

    void clearEntityInsight(Plugin owner, UUID viewerId);

    void restrictEntityOverlays(Plugin owner, boolean restricted);

    Optional<String> boardFor(Player player);

    void setBoard(Player player, String boardId);

    void clearBoard(Player player);

    void setTab(Player player, String header, String footer);

    void resetTab(Player player);
}
