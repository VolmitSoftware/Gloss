package art.arcane.gloss.surface;

import art.arcane.gloss.Gloss;
import art.arcane.gloss.GlossConfig;
import art.arcane.gloss.bedrock.BedrockService;
import art.arcane.gloss.camera.TitleProvider;
import art.arcane.gloss.condition.BoundedConditionErrorCallback;
import art.arcane.gloss.condition.GlossConditionScope;
import art.arcane.gloss.doc.DocumentDelta;
import art.arcane.gloss.doc.DocumentRegistry;
import art.arcane.gloss.doc.GlossDocument;
import art.arcane.gloss.doc.ShippedDefaults;
import art.arcane.gloss.doc.ShippedDocumentCatalog;
import art.arcane.gloss.expr.ExprScope;
import art.arcane.gloss.service.GlossService;
import art.arcane.volmlib.util.scheduling.FoliaScheduler;
import art.arcane.volmlib.util.scheduling.SchedulerUtils;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.HandlerList;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerQuitEvent;

import java.io.File;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.UnaryOperator;
import java.util.logging.Level;

/**
 * Owns {@code surfaces/} documents and the striped driver that keeps every viewer's action bar,
 * boss bar and title in sync with them.
 */
public final class SurfaceService implements GlossService, Listener, TitleProvider {
    private static final int STRIPE_PERIOD_TICKS = 1;
    private static final long BOSS_BAR_SWEEP_TICKS = 40L;

    private final Gloss plugin;
    private final ShippedDefaults defaults;
    private final DocumentRegistry<SurfaceDoc> registry;
    private final CompositorDelivery delivery;
    private final SurfaceDriver driver;
    private final BoundedConditionErrorCallback conditionErrors;
    private final List<UUID> sweepOrder = new ArrayList<>();
    private volatile List<SurfaceRuntime> runtimes = List.of();
    private volatile Map<SurfaceKind, List<SurfaceRuntime>> byKind = SurfaceDriver.byKind(List.of());
    private int stripeIndex;
    private int sweepCursor;
    private long tick;
    private int taskId = -1;

    public SurfaceService(Gloss plugin) {
        this.plugin = plugin;
        File folder = new File(plugin.getDataFolder(), SurfaceDoc.KIND);
        this.defaults = new ShippedDefaults(SurfaceDoc.KIND, folder, ShippedDocumentCatalog.SURFACES.names());
        this.registry = DocumentRegistry.folder(SurfaceDoc.KIND, folder, SurfaceDoc::parse, SurfaceDoc::revision);
        this.delivery = new CompositorDelivery(plugin);
        this.driver = new SurfaceDriver(delivery, this::render);
        this.conditionErrors = BoundedConditionErrorCallback.bounded(100, error ->
            Gloss.logExceptionStackThrottled(false, "surface-condition-" + error.path(), error.cause(),
                "Surface condition %s failed and was treated as false.", error.path()));
    }

    @Override
    public String name() {
        return SurfaceDoc.KIND;
    }

    @Override
    public void enable() {
        boolean enabled = plugin.cfg().modules().surfaces().enabled();
        if (enabled) {
            defaults.extractMissing();
        }
        loadAll();
        plugin.watchdog().register(SurfaceDoc.KIND, this::pollRegistry);
        if (!enabled) {
            return;
        }
        Bukkit.getPluginManager().registerEvents(this, plugin);
        delivery.start(BOSS_BAR_SWEEP_TICKS);
        taskId = plugin.scheduler().sr(this::sweepStripe, STRIPE_PERIOD_TICKS);
    }

    @Override
    public void disable() {
        HandlerList.unregisterAll(this);
        plugin.watchdog().unregister(SurfaceDoc.KIND);
        if (taskId != -1) {
            plugin.scheduler().csr(taskId);
            taskId = -1;
        }
        registry.close();
        delivery.shutdown();
        driver.clearAll();
        sweepOrder.clear();
        runtimes = List.of();
        byKind = SurfaceDriver.byKind(List.of());
    }

    @Override
    public void reload() {
        disable();
        enable();
    }

    @Override
    public boolean reloadOnConfigChange(GlossConfig previous, GlossConfig next) {
        return !previous.modules().surfaces().equals(next.modules().surfaces());
    }

    public List<SurfaceRuntime> runtimes() {
        return runtimes;
    }

    /** Called when the proxy claims or releases this viewer's surfaces. */
    public void refreshProxyOwnership(Player player) {
        Runnable refresh = () -> {
            if (player.isOnline()) {
                apply(player);
            }
        };
        if (FoliaScheduler.isOwnedByCurrentRegion(player)) {
            refresh.run();
        } else {
            plugin.scheduler().runEntity(player, refresh);
        }
    }

    public SurfaceRuntime runtime(String id) {
        for (SurfaceRuntime runtime : runtimes) {
            if (runtime.id().equals(id)) {
                return runtime;
            }
        }
        return null;
    }

    public int unsupportedSchemaCount() {
        return registry.unsupportedSchemaDocuments().size();
    }

    public SurfaceDelivery delivery() {
        return delivery;
    }

    /**
     * The camera lane's letterbox seam: the bars ride the title slot through the same compositor
     * every other surface uses, so a scene's own title cue takes the slot from them.
     */
    @Override
    public void letterbox(Player viewer, boolean visible) {
        if (viewer == null) {
            return;
        }
        BedrockService bedrock = plugin.bedrock();
        if (bedrock != null && bedrock.isBedrock(viewer)) {
            return;
        }
        SurfaceLetterbox.apply(delivery, viewer, visible, plugin.cfg().modules().camera().maxRideSeconds());
    }

    public List<String> resetToDefault(String nameOrStar) {
        return defaults.resetToDefault(nameOrStar);
    }

    /** Runs one viewer through the whole surface set now, for {@code /gloss surface test}. */
    public void applyNow(Player viewer) {
        plugin.scheduler().runEntity(viewer, () -> apply(viewer));
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void on(PlayerQuitEvent event) {
        driver.forget(event.getPlayer().getUniqueId());
        delivery.retire(event.getPlayer());
    }

    private void loadAll() {
        registry.reload();
        rebuildRuntimes();
    }

    private void rebuildRuntimes() {
        List<SurfaceRuntime> compiled = new ArrayList<>();
        for (GlossDocument<SurfaceDoc> document : registry.snapshot().values()) {
            try {
                compiled.add(SurfaceRuntime.compile(document.id(), document.value()));
            } catch (RuntimeException failure) {
                Gloss.logExceptionStack(false, failure, "Surface document \"%s\" failed to compile.",
                    document.id());
            }
        }
        compiled.sort(Comparator.comparing(SurfaceRuntime::id));
        runtimes = List.copyOf(compiled);
        byKind = SurfaceDriver.byKind(runtimes);
    }

    private void pollRegistry() {
        DocumentDelta delta = registry.poll();
        if (delta.isEmpty()) {
            return;
        }
        if (!registry.dispatch(delta, task -> SchedulerUtils.runGlobal(plugin, task), () -> applyDelta(delta))) {
            Gloss.warnThrottled("surface-hotload-scheduling",
                "Surface hot reload could not reach the server thread; the change will be retried.");
        }
    }

    private void applyDelta(DocumentDelta delta) {
        for (String id : delta.loaded()) {
            Gloss.log(Level.INFO, "Surface document \"%s\" changed and was reloaded.", id);
        }
        rebuildRuntimes();
    }

    private void sweepStripe() {
        tick++;
        int intervalTicks = Math.max(1, plugin.cfg().modules().surfaces().refreshIntervalTicks());
        if (SurfaceDriver.sweepsWholeFleet(intervalTicks)) {
            stripeIndex = 0;
            sweepOrder.clear();
            sweepCursor = 0;
            for (Player viewer : Bukkit.getOnlinePlayers()) {
                dispatch(viewer);
            }
            return;
        }
        if (stripeIndex >= intervalTicks) {
            stripeIndex = 0;
        }
        if (stripeIndex == 0) {
            beginCycle();
        }
        int remaining = sweepOrder.size() - sweepCursor;
        if (remaining > 0) {
            int slice = SurfaceDriver.stripeSize(remaining, intervalTicks - stripeIndex);
            for (int index = 0; index < slice; index++) {
                Player viewer = Bukkit.getPlayer(sweepOrder.get(sweepCursor++));
                if (viewer != null) {
                    dispatch(viewer);
                }
            }
        }
        stripeIndex++;
    }

    private void beginCycle() {
        sweepOrder.clear();
        for (Player viewer : Bukkit.getOnlinePlayers()) {
            sweepOrder.add(viewer.getUniqueId());
        }
        sweepOrder.sort(Comparator.naturalOrder());
        sweepCursor = 0;
    }

    private void dispatch(Player viewer) {
        plugin.scheduler().runEntity(viewer, () -> apply(viewer));
    }

    private void apply(Player viewer) {
        if (suppressForProxy(viewer)) {
            return;
        }
        driver.apply(viewer, GlossConditionScope.viewer(plugin, viewer), byKind,
            plugin.cfg().modules().surfaces().refreshIntervalTicks(), tick, conditionErrors);
    }

    /** Velocity Gloss owns this viewer's screen slots: stand our surfaces down and stay off them. */
    private boolean suppressForProxy(Player viewer) {
        if (plugin.proxyOwnership() == null || !plugin.proxyOwnership().ownsSurfaces(viewer.getUniqueId())) {
            return false;
        }
        driver.clear(viewer);
        return true;
    }

    private String render(Player viewer, String raw, ExprScope scope) {
        if (raw == null || raw.isEmpty()) {
            return "";
        }
        String rendered = plugin.text().renderScoped(viewer, raw, scope, UnaryOperator.identity());
        return rendered == null ? "" : rendered;
    }
}
