package art.arcane.gloss.inventory;

import art.arcane.gloss.Gloss;
import art.arcane.gloss.api.internal.ApiEvents;
import art.arcane.gloss.GlossConfig;
import art.arcane.gloss.api.HoloClickTrigger;
import art.arcane.gloss.doc.DocumentRegistry;
import art.arcane.gloss.doc.DocumentDelta;
import art.arcane.gloss.doc.GlossDocument;
import art.arcane.gloss.doc.ShippedDefaults;
import art.arcane.gloss.doc.ShippedDocumentCatalog;
import art.arcane.gloss.enums.NavigationMode;
import art.arcane.gloss.expr.ExprScope;
import art.arcane.gloss.expr.ExprVariableNamespaces;
import art.arcane.gloss.expr.RepeatScope;
import art.arcane.gloss.menu.ArgsNamespace;
import art.arcane.gloss.menu.SessionNamespace;
import art.arcane.gloss.menu.SessionVariables;
import art.arcane.gloss.menu.action.MenuAction;
import art.arcane.gloss.menu.action.ActionOutcome;
import art.arcane.gloss.config.icon.MenuIconData;
import art.arcane.gloss.menu.action.NavigationRequest;
import art.arcane.gloss.menu.action.NavigationResult;
import art.arcane.gloss.service.GlossService;
import art.arcane.gloss.text.TextPipeline;
import art.arcane.volmlib.util.inventorygui.UIElement;
import art.arcane.volmlib.util.inventorygui.UIWindow;
import art.arcane.volmlib.util.inventorygui.WindowResolution;
import art.arcane.volmlib.util.scheduling.FoliaScheduler;
import art.arcane.volmlib.util.scheduling.SchedulerUtils;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.HandlerList;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.event.player.PlayerTeleportEvent;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.bukkit.plugin.java.JavaPlugin;

import java.io.File;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.function.UnaryOperator;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Owns the {@code inventories/} documents and the one chest window each viewer may have open.
 * Everything the window draws is a rendered copy; no player item ever enters it, and VolmLib's
 * window already cancels every click and drag.
 */
public final class InventoryMenuService implements GlossService, Listener {
    public static final String NAME = InventoryDoc.KIND;

    private static volatile InventoryMenuService active;

    private final Gloss plugin;
    private final ShippedDefaults defaults;
    private final DocumentRegistry<InventoryDoc> registry;
    private final InventoryNavigationHistory history = new InventoryNavigationHistory();
    private final ConcurrentMap<UUID, OpenWindow> windows = new ConcurrentHashMap<>();
    private final ConcurrentMap<UUID, OpenWindow> origins = new ConcurrentHashMap<>();
    private final ConcurrentMap<UUID, OpenWindow> refreshing = new ConcurrentHashMap<>();
    private volatile Map<String, InventoryRuntime> runtimes = Map.of();
    private volatile boolean running;
    private int refreshTaskId = -1;

    public InventoryMenuService(Gloss plugin) {
        this.plugin = plugin;
        File folder = new File(plugin.getDataFolder(), InventoryDoc.KIND);
        this.defaults = new ShippedDefaults(InventoryDoc.KIND, folder, ShippedDocumentCatalog.INVENTORIES.names());
        this.registry = DocumentRegistry.folder(InventoryDoc.KIND, folder, InventoryDoc::parse,
            InventoryDoc::revision);
        active = this;
    }

    /** Null before the service is constructed; the actions reach the service through this. */
    public static InventoryMenuService active() {
        return active;
    }

    @Override
    public String name() {
        return NAME;
    }

    @Override
    public void contribute() {
        ExprVariableNamespaces.global().register(new SessionNamespace());
        ExprVariableNamespaces.global().register(new ArgsNamespace());
    }

    @Override
    public void enable() {
        running = true;
        active = this;
        reload();
        plugin.getServer().getPluginManager().registerEvents(this, plugin);
        plugin.watchdog().register(InventoryDoc.KIND, this::poll);
    }

    @Override
    public void disable() {
        running = false;
        refreshing.clear();
        plugin.watchdog().unregister(InventoryDoc.KIND);
        HandlerList.unregisterAll(this);
        if (refreshTaskId != -1) {
            plugin.scheduler().csr(refreshTaskId);
            refreshTaskId = -1;
        }
        closeAll();
        registry.close();
        runtimes = Map.of();
        if (active == this) {
            active = null;
        }
    }

    @Override
    public void reload() {
        if (!enabled()) {
            closeAll();
        }
        defaults.extractMissing();
        registry.reload();
        rebuild();
        reconcileRefreshTask();
    }

    @Override
    public boolean reloadOnConfigChange(GlossConfig previous, GlossConfig next) {
        return !previous.modules().inventories().equals(next.modules().inventories());
    }

    public List<String> resetToDefault(String name) {
        List<String> restored = defaults.resetToDefault(name);
        if (!restored.isEmpty()) {
            registry.reload();
            rebuild();
        }
        return restored;
    }

    public List<String> ids() {
        return List.copyOf(runtimes.keySet());
    }

    public InventoryRuntime runtime(String id) {
        return runtimes.get(id);
    }

    /** The open window's session variables for this viewer, or null when none is open. */
    public SessionVariables sessionVariables(UUID viewer) {
        OpenWindow open = windows.get(viewer);
        return open == null ? null : open.variables();
    }

    public boolean enabled() {
        return GlossConfig.current().modules().inventories().enabled();
    }

    private void poll() {
        DocumentDelta delta = registry.poll();
        if (delta.isEmpty()) {
            return;
        }
        if (!registry.dispatch(delta, task -> SchedulerUtils.runGlobal(plugin, task),
            () -> rebuild(registry.snapshot(delta)))) {
            Gloss.warnThrottled("inventory-hotload-scheduling",
                "Inventory hot reload could not reach the server thread; the change will be retried.");
        }
    }

    private void rebuild() {
        rebuild(registry.snapshot());
    }

    private void rebuild(Map<String, GlossDocument<InventoryDoc>> documents) {
        Map<String, InventoryRuntime> built = new LinkedHashMap<>();
        for (Map.Entry<String, GlossDocument<InventoryDoc>> entry : documents.entrySet()) {
            try {
                built.put(entry.getKey(), InventoryRuntime.compile(entry.getKey(), entry.getValue().value()));
            } catch (RuntimeException failure) {
                Gloss.logExceptionStack(false, failure, "Inventory %s failed to compile.", entry.getKey());
            }
        }
        runtimes = Map.copyOf(built);
    }

    /** @return true when the viewer now has this window open */
    public boolean open(Player viewer, String id, Map<String, Object> args) {
        InventoryRuntime runtime = runtimes.get(id);
        if (!enabled() || viewer == null || runtime == null || !runtime.shown(viewer)) {
            return false;
        }
        OpenWindow previous = windows.get(viewer.getUniqueId());
        UIWindow window = previous != null && previous.window().isVisible() && previous.runtime().id().equals(id)
            && previous.runtime().doc().resolution().equals(runtime.doc().resolution())
            ? previous.window()
            : newWindow(viewer, runtime);
        OpenWindow open = new OpenWindow(runtime, window, args == null ? Map.of() : Map.copyOf(args));
        windows.put(viewer.getUniqueId(), open);
        origins.put(viewer.getUniqueId(), open);
        if (runtime.refresh().active()) {
            refreshing.put(viewer.getUniqueId(), open);
        } else {
            refreshing.remove(viewer.getUniqueId());
        }
        history.record(viewer.getUniqueId(), id);
        render(viewer, open);
        window.open();
        return true;
    }

    /**
     * Closes whatever window this viewer has open and forgets their stack. The close event is
     * published here rather than from the window callback: VolmLib unregisters the window's own
     * listener before it closes the inventory, so a programmatic close never reaches the callback.
     */
    public void close(Player viewer) {
        if (viewer == null) {
            return;
        }
        OpenWindow open = windows.remove(viewer.getUniqueId());
        refreshing.remove(viewer.getUniqueId());
        history.forget(viewer.getUniqueId());
        if (open != null) {
            open.window().close();
            publishClose(viewer, open);
        }
    }

    public void closeAll() {
        origins.clear();
        refreshing.clear();
        for (UUID viewer : List.copyOf(windows.keySet())) {
            OpenWindow open = windows.remove(viewer);
            if (open != null) {
                Player player = open.window().getViewer();
                Runnable retired = () -> open.window().close();
                boolean scheduled = FoliaScheduler.runEntity(plugin, player, () -> {
                    open.window().close();
                    publishClose(player, open);
                }, 0L, retired);
                if (!scheduled) {
                    retired.run();
                }
            }
        }
        history.clear();
    }

    /** One close event per open window, whichever path got there first. */
    private void publishClose(Player viewer, OpenWindow open) {
        if (viewer != null && open.markClosed()) {
            ApiEvents.fireMenuClose(viewer, open.runtime().id());
        }
    }

    private UIWindow newWindow(Player viewer, InventoryRuntime runtime) {
        UIWindow window = new UIWindow((JavaPlugin) plugin, viewer);
        window.setResolution(resolutionOf(runtime.doc()));
        window.setViewportHeight(runtime.doc().rows());
        window.onClosed(closed -> {
            OpenWindow open = windows.get(viewer.getUniqueId());
            if (open == null || open.window() != window) {
                return;
            }
            windows.remove(viewer.getUniqueId(), open);
            refreshing.remove(viewer.getUniqueId(), open);
            publishClose(viewer, open);
        });
        return window;
    }

    static WindowResolution resolutionOf(InventoryDoc doc) {
        return switch (doc.resolution()) {
            case "5x1" -> WindowResolution.W5_H1;
            case "3x3" -> WindowResolution.W3_H3;
            default -> WindowResolution.W9_H6;
        };
    }

    private void render(Player viewer, OpenWindow open) {
        render(viewer, open, InventoryRefreshPlan.ALL);
    }

    private void render(Player viewer, OpenWindow open, int mask) {
        InventoryRuntime runtime = open.runtime();
        ExprScope scope = new InventoryActionContext(viewer, runtime.id(), -1, HoloClickTrigger.ANY,
            open.args(), null).conditionScope();
        if ((mask & InventoryRefreshPlan.CONDITIONS) != 0 && !runtime.doc().show().matches(scope)) {
            close(viewer);
            return;
        }
        InventoryRuntime.Presentation presentation = open.presentation;
        if (presentation == null || (mask & InventoryRefreshPlan.CONDITIONS) != 0) {
            presentation = runtime.present(scope);
            if (open.presentation != presentation) {
                open.presentation = presentation;
                open.toggleStates.clear();
                open.baseCells.clear();
                mask = InventoryRefreshPlan.ALL;
            }
        }
        UIWindow window = open.window();
        if ((mask & InventoryRefreshPlan.TITLE) != 0) {
            String title = renderText(viewer, presentation.title(), scope);
            if (!Objects.equals(window.getTitle(), title)) {
                window.setTitle(title);
            }
        }
        if ((mask & InventoryRefreshPlan.SLOTS) != 0) {
            open.baseCells.keySet().retainAll(presentation.slots().keySet());
            boolean force = runtime.doc().refresh().mode().equals("always");
            for (Map.Entry<Integer, InventoryRuntime.Slot> entry : presentation.slots().entrySet()) {
                InventoryRuntime.Slot cell = entry.getValue();
                if (force || cell.toggle() != null || InventoryRefreshPlan.dynamic(cell) || !open.baseCells.containsKey(entry.getKey())) {
                    open.baseCells.put(entry.getKey(), element(viewer, open, entry.getKey(), cell, scope, cell, false));
                }
            }
        }
        if ((mask & InventoryRefreshPlan.LIST) != 0) {
            renderList(viewer, open, scope);
        }
        if ((mask & (InventoryRefreshPlan.SLOTS | InventoryRefreshPlan.LIST)) != 0) {
            window.batch(() -> {
                window.clearElements();
                for (Map.Entry<Integer, UIElement> cell : open.baseCells.entrySet()) {
                    window.setElement(window.getPosition(cell.getKey()), window.getRow(cell.getKey()), cell.getValue());
                }
                for (Map.Entry<Integer, UIElement> cell : open.listCells.entrySet()) {
                    window.setElement(window.getPosition(cell.getKey()), window.getRow(cell.getKey()), cell.getValue());
                }
            });
        }
        open.refresh.rendered(mask, ticks());
    }

    private void renderList(Player viewer, OpenWindow open, ExprScope scope) {
        open.listCells.clear();
        InventoryRuntime.ListSection list = open.runtime().list();
        if (list == null) {
            return;
        }
        List<Object> entries = open.runtime().entries(scope);
        open.entryCount(entries.size());
        List<Object> page = InventoryPager.page(entries,
            InventoryPager.clamp(open.page(), entries.size(), list.pageSize()), list.pageSize());
        List<Integer> slots = list.slots();
        for (int index = 0; index < slots.size() && index < page.size(); index++) {
            ExprScope entryScope = new RepeatScope(scope, list.var(), page.get(index));
            open.listCells.put(slots.get(index), element(viewer, open, slots.get(index), list.template(), entryScope, page.get(index), true));
        }
    }

    private UIElement element(Player viewer, OpenWindow open, int slot, InventoryRuntime.Slot cell, ExprScope scope, Object binding, boolean listCell) {
        UIElement element = new UIElement("slot-" + slot);
        InventoryRuntime.Toggle toggle = cell.toggle();
        boolean state = true;
        if (toggle != null) {
            state = open.toggleStates.resolve(listCell, slot, binding,
                TextPipeline.viewerDependent(toggle.condition()) || TextPipeline.viewerDependent(toggle.expectedValue()),
                () -> renderText(viewer, toggle.condition(), scope).equalsIgnoreCase(renderText(viewer, toggle.expectedValue(), scope)));
        }
        MenuIconData icon = toggle == null || state ? cell.icon() : toggle.falseIcon();
        ItemStack stack = SlotItemRenderer.render(viewer, icon, scope);
        element.setBaseItemStack(stack);
        if (cell.clickable()) {
            InventoryClicks.bind(element, trigger -> click(viewer, open, slot, cell, trigger, scope, listCell));
        }
        return element;
    }

    private static String renderText(Player viewer, String text, ExprScope scope) {
        Gloss plugin = Gloss.instance;
        return plugin == null || plugin.text() == null ? text
            : plugin.text().renderScoped(viewer, text, scope, UnaryOperator.identity());
    }

    private void click(Player viewer, OpenWindow open, int slot, InventoryRuntime.Slot cell,
                       HoloClickTrigger trigger, ExprScope scope, boolean listCell) {
        if (windows.get(viewer.getUniqueId()) != open || !open.window().isVisible()) {
            return;
        }
        InventoryActionContext context = new InventoryActionContext(viewer,
            new InventoryActionContext.Options(open.runtime().id(), slot, trigger, open.args(),
                request -> navigate(viewer, open, request), scope, open.variables(),
                () -> running && origins.get(viewer.getUniqueId()) == open && viewer.isOnline()));
        boolean state = open.toggleStates.current(listCell, slot);
        List<MenuAction<?>> actions = cell.toggle() != null && state ? cell.toggle().falseActions() : cell.actions();
        ActionOutcome outcome = MenuAction.execute(actions, context);
        if (cell.toggle() != null && outcome == ActionOutcome.CONTINUE && windows.get(viewer.getUniqueId()) == open) {
            open.toggleStates.flip(listCell, slot);
            render(viewer, open, InventoryRefreshPlan.SLOTS | InventoryRefreshPlan.LIST);
        }
        ApiEvents.fireInventoryClick(viewer, open.runtime().id(), slot);
    }

    private NavigationResult navigate(Player viewer, OpenWindow open, NavigationRequest request) {
        NavigationMode mode = request.mode();
        if (mode == NavigationMode.CLOSE) {
            close(viewer);
            return NavigationResult.APPLIED;
        }
        if (mode == NavigationMode.PAGE) {
            InventoryRuntime.ListSection list = open.runtime().list();
            if (list == null) {
                return NavigationResult.NOT_FOUND;
            }
            open.page(InventoryPager.target(request.target(), open.page(), open.entryCount(), list.pageSize()));
            render(viewer, open);
            open.window().updateInventory();
            return NavigationResult.APPLIED;
        }
        String target = history.resolve(viewer.getUniqueId(), mode, request.target());
        if (target == null) {
            return NavigationResult.NO_HISTORY;
        }
        String previous = history.current(viewer.getUniqueId());
        if (!open(viewer, target, open.args())) {
            return NavigationResult.NOT_FOUND;
        }
        history.commit(viewer.getUniqueId(), mode, previous);
        return NavigationResult.APPLIED;
    }

    private void reconcileRefreshTask() {
        if (running && enabled()) {
            if (refreshTaskId == -1) {
                refreshTaskId = plugin.scheduler().sr(this::refreshOpenWindows, 1);
            }
        } else if (refreshTaskId != -1) {
            plugin.scheduler().csr(refreshTaskId);
            refreshTaskId = -1;
        }
    }

    private void refreshOpenWindows() {
        long now = ticks();
        for (Map.Entry<UUID, OpenWindow> entry : refreshing.entrySet()) {
            OpenWindow open = entry.getValue();
            if (open.refresh.due(now) == 0 || !open.refreshPending.compareAndSet(false, true)) {
                continue;
            }
            Player viewer = open.window().getViewer();
            boolean scheduled = FoliaScheduler.runEntity(plugin, viewer, () -> {
                try {
                    if (!running || windows.get(entry.getKey()) != open || !viewer.isOnline() || !open.window().isVisible()) {
                        return;
                    }
                    int mask = open.refresh.due(ticks());
                    if (mask != 0) {
                        render(viewer, open, mask);
                    }
                } finally {
                    open.refreshPending.set(false);
                }
            }, 0L, () -> {
                refreshing.remove(entry.getKey(), open);
                open.refreshPending.set(false);
            });
            if (!scheduled) {
                open.refreshPending.set(false);
            }
        }
    }

    private static long ticks() {
        return System.nanoTime() / 50_000_000L;
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onTeleport(PlayerTeleportEvent event) {
        if (!GlossConfig.current().modules().inventories().closeOnTeleport()) {
            return;
        }
        OpenWindow open = windows.get(event.getPlayer().getUniqueId());
        if (open != null && open.runtime().doc().closeOnTeleport()) {
            close(event.getPlayer());
        }
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onQuit(PlayerQuitEvent event) {
        origins.remove(event.getPlayer().getUniqueId());
        windows.remove(event.getPlayer().getUniqueId());
        refreshing.remove(event.getPlayer().getUniqueId());
        history.forget(event.getPlayer().getUniqueId());
    }

    /** One viewer's open window: which document, which page and what it was opened with. */
    private static final class OpenWindow {
        private final InventoryRuntime runtime;
        private final UIWindow window;
        private final Map<String, Object> args;
        private final SessionVariables variables;
        private final AtomicBoolean closed = new AtomicBoolean();
        private volatile int page;
        private volatile int entryCount;
        private final InventoryRefreshClock refresh;
        private final AtomicBoolean refreshPending = new AtomicBoolean();
        private final Map<Integer, UIElement> baseCells = new LinkedHashMap<>();
        private final Map<Integer, UIElement> listCells = new LinkedHashMap<>();
        private final InventoryToggleState toggleStates = new InventoryToggleState();
        private InventoryRuntime.Presentation presentation;

        private OpenWindow(InventoryRuntime runtime, UIWindow window, Map<String, Object> args) {
            this.runtime = runtime;
            this.window = window;
            this.args = args;
            this.variables = SessionVariables.of(Map.of(), args);
            this.refresh = new InventoryRefreshClock(runtime.refresh(), ticks());
        }

        /** @return true for the one caller that closed this window; every later one gets false */
        private boolean markClosed() {
            return closed.compareAndSet(false, true);
        }

        private InventoryRuntime runtime() {
            return runtime;
        }

        private UIWindow window() {
            return window;
        }

        private Map<String, Object> args() {
            return args;
        }

        private SessionVariables variables() {
            return variables;
        }

        private int page() {
            return page;
        }

        private void page(int page) {
            this.page = page;
        }

        private int entryCount() {
            return entryCount;
        }

        private void entryCount(int entryCount) {
            this.entryCount = entryCount;
        }


    }
}
