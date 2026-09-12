package art.arcane.gloss.behavior;

import art.arcane.gloss.Gloss;
import art.arcane.gloss.GlossConfig;
import art.arcane.gloss.condition.ConditionWorldGuard;
import art.arcane.gloss.condition.GlossConditionScope;
import art.arcane.gloss.doc.DocumentDelta;
import art.arcane.gloss.doc.DocumentRegistry;
import art.arcane.gloss.doc.GlossDocument;
import art.arcane.gloss.doc.ShippedDefaults;
import art.arcane.gloss.doc.ShippedDocumentCatalog;
import art.arcane.gloss.service.GlossService;
import art.arcane.gloss.state.StateStore;
import art.arcane.volmlib.util.scheduling.FoliaScheduler;
import art.arcane.volmlib.util.scheduling.SchedulerUtils;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;

import java.io.File;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.logging.Level;

/**
 * Owns {@code behaviors/}: the document registry and its watchdog entry, the compiled
 * subscriptions, the trigger listener, the interval and region drivers and the action budget.
 * Every firing ends in {@link BehaviorDispatcher}; the service only wires threads and lifecycle.
 */
public final class BehaviorService implements GlossService, Explainable {
    public static final String NAME = "behaviors";
    private static final int REGION_POLL_TICKS = 20;

    private final Gloss plugin;
    private final ShippedDefaults defaults;
    private final DocumentRegistry<BehaviorDoc> registry;
    private final BehaviorDispatcher.ContextFactory contexts;
    private final IntervalScheduler intervals;
    private final RegionTracker regions;
    private final BehaviorTriggers triggers;
    private volatile BehaviorSubscriptions subscriptions = BehaviorSubscriptions.empty();
    private boolean active;
    private int budgetTaskId = -1;
    private int intervalTaskId = -1;
    private int regionTaskId = -1;

    public BehaviorService(Gloss plugin) {
        this.plugin = Objects.requireNonNull(plugin, "plugin");
        File folder = new File(plugin.getDataFolder(), BehaviorDoc.KIND);
        this.defaults = new ShippedDefaults(BehaviorDoc.KIND, folder, ShippedDocumentCatalog.BEHAVIORS_ENTRY.names());
        this.registry = DocumentRegistry.folder(BehaviorDoc.KIND, folder, BehaviorDoc::parse, BehaviorDoc::revision);
        this.contexts = (runtime, entry, event) -> new BehaviorActionContext(plugin, runtime, entry, event);
        this.intervals = new IntervalScheduler(Bukkit::getOnlinePlayers, this::dispatchToPlayer, this::runEntry);
        this.regions = new RegionTracker(ConditionWorldGuard::regionsAt, this::fire);
        this.triggers = new BehaviorTriggers(plugin, this);
    }

    @Override
    public String name() {
        return NAME;
    }

    @Override
    public void contribute() {
        ExplainRegistry.global().register("behavior", this);
        ExplainRegistry.global().register("board", (id, viewer) ->
            plugin.boards() == null ? null : plugin.boards().explain(id, viewer));
        ExplainRegistry.global().register("tablist", (id, viewer) ->
            plugin.tablist() == null ? null : plugin.tablist().explain(id, viewer));
    }

    /** {@code /gloss explain behavior <id>}: every entry gate and whether it would open for this viewer. */
    @Override
    public ExplainReport explain(String id, Player viewer) {
        BehaviorRuntime runtime = subscriptions.runtime(id);
        return runtime == null ? null : ExplainReports.behavior(runtime, GlossConditionScope.viewer(plugin, viewer));
    }

    @Override
    public void enable() {
        if (!enabled()) {
            Gloss.verbose("Behaviors are off (features.behaviors); documents stay unloaded.");
            return;
        }
        active = true;
        defaults.extractMissing();
        registry.reload();
        rebuild();
        ActionBudget.global().configure(config().maxActionsPerTick());
        budgetTaskId = plugin.scheduler().sr(ActionBudget.global()::refill, 1);
        triggers.register();
        StateStore store = plugin.service(StateStore.class);
        if (store != null) {
            store.deferQuitForget(true);
        }
        EmitBus.install((name, args, viewer) -> fire(BehaviorTrigger.EMIT, TriggerEvent.emit(viewer, name, args)));
        plugin.watchdog().register(BehaviorDoc.KIND, this::poll);
    }

    @Override
    public void disable() {
        if (!active) {
            return;
        }
        active = false;
        plugin.watchdog().unregister(BehaviorDoc.KIND);
        EmitBus.install(null);
        triggers.unregister();
        StateStore store = plugin.service(StateStore.class);
        if (store != null) {
            store.deferQuitForget(false);
        }
        stopDrivers();
        if (budgetTaskId != -1) {
            plugin.scheduler().csr(budgetTaskId);
            budgetTaskId = -1;
        }
        ActionBudget.global().configure(0);
        PendingTimers.global().clear();
        regions.clear();
        registry.close();
        subscriptions = BehaviorSubscriptions.empty();
    }

    @Override
    public void reload() {
        boolean wanted = enabled();
        if (wanted && !active) {
            enable();
            return;
        }
        if (!wanted && active) {
            disable();
            return;
        }
        if (!active) {
            return;
        }
        defaults.extractMissing();
        registry.reload();
        rebuild();
        ActionBudget.global().configure(config().maxActionsPerTick());
    }

    @Override
    public boolean reloadOnConfigChange(GlossConfig previous, GlossConfig next) {
        return !previous.modules().behaviors().equals(next.modules().behaviors());
    }

    public boolean enabled() {
        return config().enabled();
    }

    public BehaviorSubscriptions subscriptions() {
        return subscriptions;
    }

    public void fire(BehaviorTrigger trigger, TriggerEvent event) {
        BehaviorDispatcher.fire(subscriptions, trigger, event, contexts);
    }

    /** {@code /gloss do}: fires the command entries named {@code name}; returns how many accepted it. */
    public int fireCommand(Player player, String name, Map<String, Object> args) {
        return BehaviorDispatcher.fire(subscriptions, BehaviorTrigger.COMMAND, TriggerEvent.command(player, name, args), contexts);
    }

    /** Runs one entry directly for a player (or without one), bypassing the trigger filters; for {@code /gloss behavior fire}. */
    public boolean fireEntry(String id, int index, Player player) {
        BehaviorRuntime runtime = subscriptions.runtime(id);
        if (runtime == null || index < 0 || index >= runtime.entries().size()) {
            return false;
        }
        BehaviorRuntime.CompiledEntry entry = runtime.entries().get(index);
        TriggerEvent event = player == null ? TriggerEvent.none() : TriggerEvent.viewer(player);
        BehaviorDispatcher.run(new BehaviorSubscriptions.Subscription(runtime, entry), event, contexts);
        return true;
    }

    public List<String> resetToDefault(String name) {
        List<String> written = defaults.resetToDefault(name);
        if (!written.isEmpty() && active) {
            registry.reload();
            rebuild();
        }
        return written;
    }

    /** Runs {@code task} once the player's state file has landed; immediately when there is no store. */
    void whenStateReady(Player player, Runnable task) {
        StateStore store = plugin.service(StateStore.class);
        if (store == null) {
            task.run();
            return;
        }
        store.whenLoaded(player.getUniqueId(), task);
    }

    /**
     * Runs after the QUIT entries, which is why the store's own drop is deferred to here: a
     * {@code quit} behavior writing state must reach a loaded entry.
     */
    void forget(UUID player) {
        PendingTimers.global().forget(player);
        regions.forget(player);
        StateStore store = plugin.service(StateStore.class);
        if (store != null) {
            store.forget(player);
        }
    }

    void forgetRegions(UUID player) {
        regions.forget(player);
    }

    private void runEntry(BehaviorSubscriptions.Subscription subscription, TriggerEvent event) {
        BehaviorDispatcher.run(subscription, event, contexts);
    }

    private void rebuild() {
        Map<String, BehaviorDoc> documents = new LinkedHashMap<>();
        for (Map.Entry<String, GlossDocument<BehaviorDoc>> document : registry.snapshot().entrySet()) {
            documents.put(document.getKey(), document.getValue().value());
        }
        BehaviorSubscriptions compiled = BehaviorSubscriptions.compile(documents, plugin.service(StateStore.class));
        for (Map.Entry<String, String> refusal : compiled.refused().entrySet()) {
            Gloss.log(Level.WARNING, "behaviors/%s.json refused: %s", refusal.getKey(), refusal.getValue());
        }
        subscriptions = compiled;
        intervals.rebuild(compiled);
        startDrivers(compiled);
    }

    private void startDrivers(BehaviorSubscriptions compiled) {
        stopDrivers();
        if (intervals.hasEntries()) {
            intervalTaskId = plugin.scheduler().sr(intervals::tick, 1);
        }
        if (compiled.hasAny(BehaviorTrigger.REGION_ENTER) || compiled.hasAny(BehaviorTrigger.REGION_LEAVE)) {
            regionTaskId = plugin.scheduler().sr(this::pollRegions, REGION_POLL_TICKS);
        }
    }

    private void stopDrivers() {
        if (intervalTaskId != -1) {
            plugin.scheduler().csr(intervalTaskId);
            intervalTaskId = -1;
        }
        if (regionTaskId != -1) {
            plugin.scheduler().csr(regionTaskId);
            regionTaskId = -1;
        }
    }

    private void pollRegions() {
        for (Player player : Bukkit.getOnlinePlayers()) {
            dispatchToPlayer(player, () -> regions.tick(player));
        }
    }

    private void dispatchToPlayer(Player player, Runnable task) {
        FoliaScheduler.runEntity(plugin, player, task);
    }

    private void poll() {
        DocumentDelta delta = registry.poll();
        if (delta.isEmpty()) {
            return;
        }
        if (!registry.dispatch(delta, task -> SchedulerUtils.runGlobal(plugin, task), this::rebuild)) {
            Gloss.warnThrottled("behavior-hotload-scheduling",
                "Behavior hot reload could not reach the server thread; the change will be retried.");
        }
    }

    private GlossConfig.Behaviors config() {
        return plugin.cfg().modules().behaviors();
    }
}
