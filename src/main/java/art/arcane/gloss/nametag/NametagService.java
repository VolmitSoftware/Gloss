package art.arcane.gloss.nametag;

import art.arcane.gloss.Gloss;
import art.arcane.gloss.GlossConfig;
import art.arcane.gloss.condition.BoundedConditionErrorCallback;
import art.arcane.gloss.condition.GlossConditionContext;
import art.arcane.gloss.condition.GlossConditionScope;
import art.arcane.gloss.doc.DocumentDelta;
import art.arcane.gloss.doc.DocumentRegistry;
import art.arcane.gloss.doc.GlossDocument;
import art.arcane.gloss.doc.ShippedDefaults;
import art.arcane.gloss.doc.ShippedDocumentCatalog;
import art.arcane.gloss.expr.ExprScope;
import art.arcane.gloss.service.GlossService;
import art.arcane.volmlib.util.scheduling.SchedulerUtils;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.HandlerList;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerChangedWorldEvent;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerQuitEvent;

import java.io.File;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.function.UnaryOperator;
import java.util.logging.Level;

/**
 * Owns {@code nametags/} documents and the pass that keeps every viewer's team prefixes in sync.
 * The quit hook also retires the shared team allocator, which every per-viewer team in Gloss comes
 * from, so it is registered even while the nametag feature itself is off.
 */
public final class NametagService implements GlossService, Listener {
    private final Gloss plugin;
    private final ShippedDefaults defaults;
    private final DocumentRegistry<NametagDoc> registry;
    private final NametagDriver driver;
    private final BoundedConditionErrorCallback conditionErrors;
    private volatile List<NametagRuntime> runtimes = List.of();
    private int taskId = -1;

    public NametagService(Gloss plugin) {
        this.plugin = plugin;
        File folder = new File(plugin.getDataFolder(), NametagDoc.KIND);
        this.defaults = new ShippedDefaults(NametagDoc.KIND, folder, ShippedDocumentCatalog.NAMETAGS.names());
        this.registry = DocumentRegistry.folder(NametagDoc.KIND, folder, NametagDoc::parse, NametagDoc::revision);
        this.driver = new NametagDriver(plugin.teams(), this::render);
        this.conditionErrors = BoundedConditionErrorCallback.bounded(100, error ->
            Gloss.logExceptionStackThrottled(false, "nametag-condition-" + error.path(), error.cause(),
                "Nametag condition %s failed and was treated as false.", error.path()));
    }

    @Override
    public String name() {
        return NametagDoc.KIND;
    }

    @Override
    public void enable() {
        boolean enabled = plugin.cfg().modules().nametags().enabled();
        if (enabled) {
            defaults.extractMissing();
        }
        loadAll();
        plugin.watchdog().register(NametagDoc.KIND, this::pollRegistry);
        Bukkit.getPluginManager().registerEvents(this, plugin);
        if (!enabled) {
            return;
        }
        taskId = plugin.scheduler().sr(this::pass,
            Math.max(1, plugin.cfg().modules().nametags().refreshIntervalTicks()));
    }

    @Override
    public void disable() {
        HandlerList.unregisterAll(this);
        plugin.watchdog().unregister(NametagDoc.KIND);
        if (taskId != -1) {
            plugin.scheduler().csr(taskId);
            taskId = -1;
        }
        for (Player viewer : Bukkit.getOnlinePlayers()) {
            driver.releaseAll(viewer);
        }
        driver.clear();
        registry.close();
        runtimes = List.of();
    }

    @Override
    public void reload() {
        disable();
        enable();
    }

    @Override
    public boolean reloadOnConfigChange(GlossConfig previous, GlossConfig next) {
        return !previous.modules().nametags().equals(next.modules().nametags());
    }

    public List<NametagRuntime> runtimes() {
        return runtimes;
    }

    public int unsupportedSchemaCount() {
        return registry.unsupportedSchemaDocuments().size();
    }

    public List<String> resetToDefault(String nameOrStar) {
        return defaults.resetToDefault(nameOrStar);
    }

    /** Re-applies every tag now, for {@code /gloss nametag refresh}. */
    public void refresh() {
        plugin.scheduler().s(this::pass);
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void on(PlayerJoinEvent event) {
        plugin.scheduler().s(this::pass, 1);
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void on(PlayerChangedWorldEvent event) {
        driver.releaseAll(event.getPlayer());
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void on(PlayerQuitEvent event) {
        driver.forget(event.getPlayer().getUniqueId());
    }

    private void loadAll() {
        registry.reload();
        rebuildRuntimes();
    }

    private void rebuildRuntimes() {
        List<NametagRuntime> compiled = new ArrayList<>();
        for (GlossDocument<NametagDoc> document : registry.snapshot().values()) {
            try {
                compiled.add(NametagRuntime.compile(document.id(), document.value()));
            } catch (RuntimeException failure) {
                Gloss.logExceptionStack(false, failure, "Nametag document \"%s\" failed to compile.",
                    document.id());
            }
        }
        compiled.sort(Comparator.comparing(NametagRuntime::id));
        runtimes = List.copyOf(compiled);
    }

    private void pollRegistry() {
        DocumentDelta delta = registry.poll();
        if (delta.isEmpty()) {
            return;
        }
        if (!registry.dispatch(delta, task -> SchedulerUtils.runGlobal(plugin, task), () -> applyDelta(delta))) {
            Gloss.warnThrottled("nametag-hotload-scheduling",
                "Nametag hot reload could not reach the server thread; the change will be retried.");
        }
    }

    private void applyDelta(DocumentDelta delta) {
        for (String id : delta.loaded()) {
            Gloss.log(Level.INFO, "Nametag document \"%s\" changed and was reloaded.", id);
        }
        for (Player viewer : Bukkit.getOnlinePlayers()) {
            driver.releaseAll(viewer);
        }
        rebuildRuntimes();
    }

    private void pass() {
        List<NametagRuntime> active = runtimes;
        if (active.isEmpty()) {
            return;
        }
        List<Player> players = new ArrayList<>(Bukkit.getOnlinePlayers());
        driver.apply(players, players, active, this::scope, conditionErrors);
    }

    private String render(Player viewer, String raw, ExprScope scope) {
        String rendered = plugin.text().renderScoped(viewer, raw, scope, UnaryOperator.identity());
        return rendered == null ? "" : rendered;
    }

    private ExprScope scope(Player viewer, Player subject) {
        return new GlossConditionScope(plugin, GlossConditionContext.subject(viewer, subject, null, Map.of()));
    }
}
