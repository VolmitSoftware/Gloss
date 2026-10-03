package art.arcane.gloss.prompt;

import art.arcane.gloss.Gloss;
import art.arcane.gloss.text.TextPipeline;
import art.arcane.volmlib.util.scheduling.FoliaScheduler;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.HandlerList;
import org.bukkit.event.Listener;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryCloseEvent;
import org.bukkit.event.inventory.InventoryType;
import org.bukkit.event.inventory.PrepareAnvilEvent;
import org.bukkit.inventory.AnvilInventory;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.InventoryView;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.MenuType;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.inventory.view.AnvilView;

import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;

/**
 * An anvil rename box used as a text field. Paper moved the rename text onto {@code AnvilView} and
 * Spigot keeps it on {@code AnvilInventory}; both are Bukkit-typed, so the reader is chosen by what
 * the event actually hands over rather than by a server-flavour guess.
 */
public final class AnvilPrompt implements Listener {
    private static final int RESULT_SLOT = 2;

    private final Gloss plugin;
    private final PromptService service;
    private final ConcurrentMap<UUID, String> typed = new ConcurrentHashMap<>();
    private final ConcurrentMap<UUID, Inventory> inventories = new ConcurrentHashMap<>();
    private final Set<UUID> answering = ConcurrentHashMap.newKeySet();

    AnvilPrompt(Gloss plugin, PromptService service) {
        this.plugin = plugin;
        this.service = service;
    }

    void enable() {
        if (plugin != null && plugin.getServer() != null) {
            plugin.getServer().getPluginManager().registerEvents(this, plugin);
        }
    }

    void disable() {
        HandlerList.unregisterAll(this);
        for (Map.Entry<UUID, Inventory> entry : inventories.entrySet()) {
            Player viewer = Bukkit.getPlayer(entry.getKey());
            if (viewer == null) {
                continue;
            }
            Inventory inventory = entry.getValue();
            if (FoliaScheduler.isOwnedByCurrentRegion(viewer)) {
                inventory.clear();
            } else {
                FoliaScheduler.runEntity(plugin, viewer, inventory::clear);
            }
        }
        inventories.clear();
        typed.clear();
        answering.clear();
    }

    boolean open(Player viewer, PromptRequest request) {
        AnvilView view = MenuType.ANVIL.create(viewer, request.label().isBlank()
            ? null : TextPipeline.menuText(viewer, request.label()));
        AnvilInventory anvil = view.getTopInventory();
        ItemStack input = new ItemStack(Material.PAPER);
        ItemMeta meta = input.getItemMeta();
        meta.setDisplayName(request.initial());
        input.setItemMeta(meta);
        anvil.setItem(0, input);
        typed.put(viewer.getUniqueId(), request.initial());
        inventories.put(viewer.getUniqueId(), anvil);
        viewer.openInventory(view);
        FoliaScheduler.runEntity(plugin, viewer, () -> service.timeout(viewer, request), request.timeoutTicks());
        return true;
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onPrepare(PrepareAnvilEvent event) {
        if (!(event.getView().getPlayer() instanceof Player viewer)
            || inventories.get(viewer.getUniqueId()) != event.getInventory()
            || !owns(service.pending(viewer.getUniqueId()))) {
            return;
        }
        String text = renameText(event.getView(), event.getInventory());
        if (text != null) {
            typed.put(viewer.getUniqueId(), text);
        }
        if (event.getView() instanceof AnvilView view) {
            view.setRepairCost(0);
        }
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onClick(InventoryClickEvent event) {
        if (!(event.getWhoClicked() instanceof Player viewer)) {
            return;
        }
        PromptRequest request = service.pending(viewer.getUniqueId());
        if (inventories.get(viewer.getUniqueId()) != event.getInventory()) {
            return;
        }
        event.setCancelled(true);
        if (!owns(request)) {
            return;
        }
        if (event.getRawSlot() != RESULT_SLOT) {
            return;
        }
        String answer = renameText(event.getView(), event.getInventory());
        String cached = typed.remove(viewer.getUniqueId());
        if (answer == null) {
            answer = cached;
        }
        answering.add(viewer.getUniqueId());
        try {
            viewer.closeInventory();
        } finally {
            answering.remove(viewer.getUniqueId());
        }
        service.complete(viewer, request, answer == null ? request.initial() : answer);
    }

    /**
     * Whether this request is one of ours. The kind is compared by value: it comes out of a
     * document, so it is never the same instance as the constant.
     */
    static boolean owns(PromptRequest request) {
        return request != null && PromptRequest.ANVIL.equals(request.kind());
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onClose(InventoryCloseEvent event) {
        if (event.getPlayer() instanceof Player viewer
            && event.getInventory().getType() == InventoryType.ANVIL) {
            if (inventories.remove(viewer.getUniqueId(), event.getInventory())) {
                event.getInventory().clear();
                typed.remove(viewer.getUniqueId());
                closed(viewer);
            }
        }
    }

    void release(UUID viewer) {
        Inventory inventory = inventories.get(viewer);
        if (inventory != null) {
            inventory.clear();
        }
        typed.remove(viewer);
    }

    /**
     * The prompt's anvil going away without an answer ends the prompt. Without this the player
     * keeps a pending entry no editor is attached to and every later prompt is refused as busy.
     */
    void closed(Player viewer) {
        if (answering.contains(viewer.getUniqueId())) {
            return;
        }
        PromptRequest request = service.pending(viewer.getUniqueId());
        if (owns(request)) {
            service.timeout(viewer, request);
        }
    }

    /**
     * The text in the rename box, from whichever of the two Bukkit types exposes it here. Returns
     * null when neither does, which is what an unmodified anvil with no typed text looks like.
     */
    public static String renameText(InventoryView view, Inventory inventory) {
        if (view instanceof AnvilView anvilView) {
            String text = anvilView.getRenameText();
            if (text != null) {
                return text;
            }
        }
        return inventory instanceof AnvilInventory anvil ? anvil.getRenameText() : null;
    }
}
