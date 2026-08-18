package art.arcane.gloss.drop;

import art.arcane.gloss.Gloss;
import art.arcane.volmlib.util.format.Form;
import org.bukkit.Bukkit;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Item;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.HandlerList;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.ItemMergeEvent;
import org.bukkit.event.entity.ItemSpawnEvent;

import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

public final class DropNameService implements Listener {
    private final Gloss plugin;
    private final Set<UUID> named;
    private boolean listening;

    public DropNameService(Gloss plugin) {
        this.plugin = plugin;
        this.named = ConcurrentHashMap.newKeySet();
    }

    public void enable() {
        if (!plugin.cfg().drops().enabled()) {
            return;
        }

        Bukkit.getPluginManager().registerEvents(this, plugin);
        listening = true;
    }

    public void disable() {
        if (!listening) {
            return;
        }

        HandlerList.unregisterAll(this);
        listening = false;
        named.clear();
    }

    public void reload() {
        disable();
        enable();
    }

    public int activeCount() {
        pruneNow();
        return named.size();
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onItemSpawn(ItemSpawnEvent event) {
        Item item = event.getEntity();
        applyName(item, item.getItemStack().getAmount());
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onItemMerge(ItemMergeEvent event) {
        named.remove(event.getEntity().getUniqueId());
        Item target = event.getTarget();
        applyName(target, target.getItemStack().getAmount() + event.getEntity().getItemStack().getAmount());
    }

    private void applyName(Item item, int count) {
        String pretty = Form.prettyEnumName(item.getItemStack().getType().name());
        String raw = DropNameFormatter.format(plugin.cfg().drops().nameFormat(), count, pretty);
        item.setCustomName(plugin.text().renderStatic(raw));
        item.setCustomNameVisible(true);
        named.add(item.getUniqueId());
    }

    private void pruneNow() {
        named.removeIf(entityId -> {
            Entity entity = Bukkit.getEntity(entityId);
            return entity == null || !entity.isValid();
        });
    }
}
