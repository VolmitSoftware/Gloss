package art.arcane.gloss.connection;

import art.arcane.gloss.Gloss;
import art.arcane.gloss.GlossConfig;
import art.arcane.gloss.condition.BoundedConditionErrorCallback;
import art.arcane.gloss.condition.CompiledCondition;
import art.arcane.gloss.condition.ConditionCompiler;
import art.arcane.gloss.condition.ConditionSource;
import art.arcane.gloss.condition.GlossConditionContext;
import art.arcane.gloss.condition.GlossConditionScope;
import art.arcane.gloss.condition.ShowCondition;
import art.arcane.gloss.doc.DocumentDelta;
import art.arcane.gloss.doc.DocumentRegistry;
import art.arcane.gloss.doc.GlossDocument;
import art.arcane.gloss.doc.RegistryOwner;
import art.arcane.gloss.doc.ShippedDefaults;
import art.arcane.gloss.doc.ShippedDocumentCatalog;
import art.arcane.gloss.proxy.BackendProxyOwnership;
import art.arcane.gloss.service.GlossService;
import art.arcane.volmlib.util.format.ColorFormatter;
import art.arcane.volmlib.util.plugin.ComponentMessenger;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.HandlerList;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerQuitEvent;

import java.io.File;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.function.UnaryOperator;
import java.util.logging.Level;

/**
 * Owns {@code connections.json} and the join and leave broadcasts it describes. A Gloss proxy that
 * has claimed connection messages announces for the whole network, so a backend under such a proxy
 * only silences the vanilla line and says nothing itself.
 */
public final class ConnectionsService implements GlossService, Listener, RegistryOwner {
    private static final int DEFERRED_POLL_TICKS = 5;
    private static final int DEFERRED_LIMIT_TICKS = 60;

    private final Gloss plugin;
    private final ShippedDefaults defaults;
    private final DocumentRegistry<ConnectionsDoc> registry;
    private final BoundedConditionErrorCallback conditionErrors;
    private volatile Compiled compiled;

    public ConnectionsService(Gloss plugin) {
        this.plugin = plugin;
        this.defaults = new ShippedDefaults(ConnectionsDoc.KIND, plugin.getDataFolder(),
            ShippedDocumentCatalog.CONNECTIONS.names());
        this.registry = DocumentRegistry.singleFile(ConnectionsDoc.KIND,
            new File(plugin.getDataFolder(), ConnectionsDoc.KIND + ".json"),
            ConnectionsDoc::parse, ConnectionsDoc::revision);
        this.conditionErrors = BoundedConditionErrorCallback.bounded(100, error ->
            Gloss.logExceptionStackThrottled(false, "connections-condition-" + error.path(), error.cause(),
                "Connection condition %s failed and was treated as false.", error.path()));
        this.compiled = Compiled.of(ConnectionsDoc.DEFAULTS);
    }

    @Override
    public String name() {
        return ConnectionsDoc.KIND;
    }

    /**
     * The shipped {@code connections.json} is written only once the feature is on, so a server that
     * never enables it keeps a clean data folder. The listener still goes up while the proxy
     * ownership channel is live: suppressing the vanilla line for a proxy-owned network is this
     * service's job whether or not the local feature is on.
     */
    @Override
    public void enable() {
        boolean enabled = enabled();
        if (enabled) {
            defaults.extractMissing();
        }
        registry.reload();
        rebuild();
        plugin.watchdog().register(ConnectionsDoc.KIND, this::pollRegistry);
        if (!enabled && !proxyChannelLive()) {
            return;
        }
        Bukkit.getPluginManager().registerEvents(this, plugin);
    }

    @Override
    public void disable() {
        HandlerList.unregisterAll(this);
        plugin.watchdog().unregister(ConnectionsDoc.KIND);
        registry.close();
    }

    @Override
    public void reload() {
        disable();
        enable();
    }

    @Override
    public boolean reloadOnConfigChange(GlossConfig previous, GlossConfig next) {
        return !previous.modules().connections().equals(next.modules().connections());
    }

    public boolean enabled() {
        return plugin.cfg().modules().connections().enabled();
    }

    /**
     * A join can land between a proxy lease lapsing and the next handshake, so a proxy that is known
     * to announce connection messages gets a grace window: the vanilla line is withheld and then
     * replayed — or replaced by this document — once the claim has had time to arrive. Only a proxy
     * whose last claim carried connection messages earns that wait; a plain Velocity, or a proxy Gloss
     * with connections off, never delays a join.
     */
    @EventHandler(priority = EventPriority.HIGH)
    public void onJoin(PlayerJoinEvent event) {
        BackendProxyOwnership ownership = plugin.proxyOwnership();
        if (ownership != null && ownership.ownsConnections()) {
            event.setJoinMessage(null);
            return;
        }
        Player subject = event.getPlayer();
        if (ownership != null && ownership.enabled() && ownership.proxyLastClaimedConnections()) {
            String vanilla = event.getJoinMessage();
            event.setJoinMessage(null);
            schedulePoll(subject, vanilla, 0);
            return;
        }
        if (announce(compiled.join(), subject)) {
            event.setJoinMessage(null);
        }
    }

    @EventHandler(priority = EventPriority.HIGH)
    public void onQuit(PlayerQuitEvent event) {
        BackendProxyOwnership ownership = plugin.proxyOwnership();
        if (ownership != null && ownership.ownsConnections()) {
            event.setQuitMessage(null);
            return;
        }
        if (announce(compiled.leave(), event.getPlayer())) {
            event.setQuitMessage(null);
        }
    }

    private boolean proxyChannelLive() {
        BackendProxyOwnership ownership = plugin.proxyOwnership();
        return ownership != null && ownership.enabled();
    }

    private void schedulePoll(Player subject, String vanilla, int elapsedTicks) {
        plugin.scheduler().runEntity(subject,
            () -> poll(subject, vanilla, elapsedTicks + DEFERRED_POLL_TICKS), DEFERRED_POLL_TICKS);
    }

    private void poll(Player subject, String vanilla, int elapsedTicks) {
        if (!subject.isOnline()) {
            return;
        }
        BackendProxyOwnership ownership = plugin.proxyOwnership();
        if (ownership != null && ownership.ownsConnections()) {
            return;
        }
        if (elapsedTicks < DEFERRED_LIMIT_TICKS) {
            schedulePoll(subject, vanilla, elapsedTicks);
            return;
        }
        if (announce(compiled.join(), subject)) {
            return;
        }
        if (vanilla == null || vanilla.isEmpty()) {
            return;
        }
        for (Player recipient : Bukkit.getOnlinePlayers()) {
            ComponentMessenger.sendSection(recipient, vanilla);
        }
    }

    private boolean announce(CompiledSection section, Player subject) {
        if (!enabled() || !section.section().active()) {
            return false;
        }
        GlossConditionScope gate = GlossConditionScope.viewer(plugin, subject);
        if (!compiled.show().matches(gate, conditionErrors)
            || !section.section().show().matches(gate, conditionErrors)) {
            return false;
        }
        String console = render(section, subject, subject);
        for (Player recipient : Bukkit.getOnlinePlayers()) {
            String rendered = recipient.getUniqueId().equals(subject.getUniqueId())
                ? console : render(section, recipient, subject);
            if (!rendered.isEmpty()) {
                ComponentMessenger.sendSection(recipient, rendered);
            }
        }
        if (!console.isEmpty()) {
            Gloss.log(Level.INFO, ColorFormatter.stripColor(console));
        }
        return true;
    }

    private String render(CompiledSection section, Player viewer, Player subject) {
        GlossConditionScope scope = new GlossConditionScope(plugin,
            GlossConditionContext.subject(viewer, subject, null, Map.of()));
        ConnectionsDoc.Presentation presentation = section.section().presentation();
        for (CompiledVariant variant : section.variants()) {
            if (variant.when().matches(scope, conditionErrors)) {
                presentation = variant.presentation();
                break;
            }
        }
        if (presentation.text().isEmpty()) {
            return "";
        }
        String rendered = plugin.text().renderScoped(viewer, presentation.text(), scope,
            UnaryOperator.identity());
        return rendered == null ? "" : rendered;
    }

    private void pollRegistry() {
        DocumentDelta delta = registry.poll();
        if (delta.isEmpty() || !registry.acknowledge(delta)) {
            return;
        }
        rebuild();
        Gloss.log(Level.INFO, "Connection messages reloaded from connections.json.");
    }

    private void rebuild() {
        GlossDocument<ConnectionsDoc> document = registry.get(ConnectionsDoc.KIND);
        compiled = Compiled.of(document == null ? ConnectionsDoc.DEFAULTS : document.value());
    }

    private record Compiled(ShowCondition show, CompiledSection join, CompiledSection leave) {
        private static Compiled of(ConnectionsDoc document) {
            return new Compiled(document.show(), CompiledSection.of(document.join(), "join"),
                CompiledSection.of(document.leave(), "leave"));
        }
    }

    private record CompiledSection(ConnectionsDoc.Section section, List<CompiledVariant> variants) {
        private static CompiledSection of(ConnectionsDoc.Section section, String key) {
            List<ConnectionsDoc.Variant> declared = section.variants();
            List<CompiledVariant> compiled = new ArrayList<>(declared.size());
            for (int index = 0; index < declared.size(); index++) {
                ConnectionsDoc.Variant variant = declared.get(index);
                compiled.add(new CompiledVariant(ConditionCompiler.compile(new ConditionSource(
                    "connections." + key + ".variants[" + index + "].when", variant.when())),
                    variant.presentation()));
            }
            return new CompiledSection(section, List.copyOf(compiled));
        }
    }

    private record CompiledVariant(CompiledCondition when, ConnectionsDoc.Presentation presentation) {
    }

    @Override
    public Map<String, DocumentRegistry<?>> registries() {
        return Map.of(ConnectionsDoc.KIND, registry);
    }
}
