package art.arcane.gloss.prompt;

import art.arcane.gloss.Gloss;
import art.arcane.volmlib.util.scheduling.FoliaScheduler;
import org.bukkit.Bukkit;
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

import java.lang.reflect.Method;
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
        typed.clear();
        answering.clear();
    }

    boolean open(Player viewer, PromptRequest request) {
        Inventory anvil = Bukkit.createInventory(null, InventoryType.ANVIL);
        typed.put(viewer.getUniqueId(), request.initial());
        viewer.openInventory(anvil);
        FoliaScheduler.runEntity(plugin, viewer, () -> service.timeout(viewer, request), request.timeoutTicks());
        return true;
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onPrepare(PrepareAnvilEvent event) {
        if (!(event.getView().getPlayer() instanceof Player viewer)
            || service.pending(viewer.getUniqueId()) == null) {
            return;
        }
        String text = renameText(event.getView(), event.getInventory());
        if (text != null) {
            typed.put(viewer.getUniqueId(), text);
        }
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onClick(InventoryClickEvent event) {
        if (!(event.getWhoClicked() instanceof Player viewer)) {
            return;
        }
        PromptRequest request = service.pending(viewer.getUniqueId());
        if (!owns(request) || event.getInventory().getType() != InventoryType.ANVIL) {
            return;
        }
        event.setCancelled(true);
        if (event.getRawSlot() != RESULT_SLOT) {
            return;
        }
        String answer = typed.remove(viewer.getUniqueId());
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
            typed.remove(viewer.getUniqueId());
            closed(viewer);
        }
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
        String fromView = reflectiveRenameText(view);
        if (fromView != null) {
            return fromView;
        }
        return inventory instanceof AnvilInventory anvil ? anvil.getRenameText() : null;
    }

    private static String reflectiveRenameText(Object view) {
        if (view == null) {
            return null;
        }
        try {
            Method getter = view.getClass().getMethod("getRenameText");
            Object value = getter.invoke(view);
            return value instanceof String text ? text : null;
        } catch (ReflectiveOperationException | RuntimeException | LinkageError absent) {
            return null;
        }
    }
}
