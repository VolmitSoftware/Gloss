package art.arcane.gloss.service;

import art.arcane.gloss.Gloss;
import art.arcane.gloss.api.GlossAPI;
import art.arcane.gloss.api.HoloMenu;
import art.arcane.gloss.api.HoloMenuHandle;
import art.arcane.gloss.api.Hologram;
import art.arcane.gloss.api.TemporaryHologram;
import art.arcane.gloss.api.internal.GlossApiServiceImpl;
import org.bukkit.Location;
import org.bukkit.entity.Item;
import org.bukkit.entity.Player;
import org.bukkit.plugin.Plugin;

import java.util.List;
import java.util.Optional;
import java.util.Set;

public final class GlossAPIImpl implements GlossAPI {
    private final Gloss plugin;

    public GlossAPIImpl(Gloss plugin) {
        this.plugin = plugin;
    }

    @Override
    public HoloMenuHandle open(Plugin owner, Player player, HoloMenu menu) {
        return menus().open(owner, player, menu);
    }

    @Override
    public HoloMenuHandle open(Plugin owner, Player player, String menuId) {
        return menus().open(owner, player, menuId);
    }

    @Override
    public boolean close(Player player) {
        GlossApiServiceImpl service = plugin.getApiService();
        return service != null && service.close(player);
    }

    @Override
    public boolean isOpen(Player player) {
        GlossApiServiceImpl service = plugin.getApiService();
        return service != null && service.isOpen(player);
    }

    @Override
    public Set<String> menuIds() {
        GlossApiServiceImpl service = plugin.getApiService();
        return service == null ? Set.of() : service.menuIds();
    }

    private GlossApiServiceImpl menus() {
        GlossApiServiceImpl service = plugin.getApiService();
        if (service == null) {
            throw new IllegalStateException("Gloss menus are not enabled");
        }
        return service;
    }

    @Override
    public Hologram createHologram(String id, Location location) {
        return plugin.holograms().create(id, location);
    }

    @Override
    public Optional<Hologram> hologram(String id) {
        return Optional.ofNullable(plugin.holograms().get(id));
    }

    @Override
    public boolean hasHologram(String id) {
        return plugin.holograms().has(id);
    }

    @Override
    public void deleteHologram(String id) {
        plugin.holograms().delete(id);
    }

    @Override
    public List<Hologram> holograms() {
        return plugin.holograms().all();
    }

    @Override
    public TemporaryHologram createTemporaryHologram(String id, Location initial, long durationMs) {
        return plugin.holograms().createTemporary(id, initial, durationMs);
    }

    @Override
    public double stackSpread() {
        return plugin.holograms().stackSpread();
    }

    @Override
    public String filter(Player player, String raw) {
        return plugin.text().render(player, raw);
    }

    @Override
    public void refreshDropName(Item item) {
        plugin.drops().refresh(item);
    }

    @Override
    public void refreshDropName(Item item, String bundleHeaderFormat, String bundleEntryFormat,
                                String bundleMoreFormat, int bundleEntryLimit) {
        plugin.drops().refresh(
            item, bundleHeaderFormat, bundleEntryFormat, bundleMoreFormat, bundleEntryLimit);
    }

    @Override
    public void removeDropPresentation(Item item) {
        plugin.drops().remove(item);
    }

    @Override
    public Optional<String> boardFor(Player player) {
        return plugin.boards().boardIdFor(player);
    }

    @Override
    public void setBoard(Player player, String boardId) {
        plugin.boards().setBoard(player, boardId);
    }

    @Override
    public void clearBoard(Player player) {
        plugin.boards().clearBoard(player);
    }

    @Override
    public void setTab(Player player, String header, String footer) {
        plugin.tablist().setTab(player, header, footer);
    }

    @Override
    public void resetTab(Player player) {
        plugin.tablist().resetTab(player);
    }
}
