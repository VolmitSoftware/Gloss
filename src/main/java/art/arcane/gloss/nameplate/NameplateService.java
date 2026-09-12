package art.arcane.gloss.nameplate;

import art.arcane.gloss.Gloss;
import art.arcane.gloss.GlossConfig;
import art.arcane.gloss.doc.DocumentDelta;
import art.arcane.gloss.doc.DocumentRegistry;
import art.arcane.gloss.doc.GlossDocument;
import art.arcane.gloss.doc.ShippedDefaults;
import art.arcane.gloss.doc.ShippedDocumentCatalog;
import art.arcane.gloss.entity.EntityOverlayService;
import art.arcane.gloss.service.GlossService;
import org.bukkit.event.EventHandler;
import org.bukkit.event.HandlerList;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerQuitEvent;

import java.io.File;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Player panes that replace the vanilla tag. Nothing here draws: the pane rides the entity-overlay
 * engine through a registered source, and this service owns the documents, the source's lifetime
 * and the per-viewer team that hides the real tag underneath.
 */
public final class NameplateService implements GlossService, Listener {
    public static final String NAME = "nameplates";

    private final Gloss plugin;
    private final ShippedDefaults defaults;
    private final DocumentRegistry<NameplateDoc> registry;
    private final NameplateSuppression suppression;
    private final NameplateSource source;
    private volatile List<NameplateRuntime> documents = List.of();
    private boolean registered;

    public NameplateService(Gloss plugin) {
        this.plugin = plugin;
        File folder = new File(plugin.getDataFolder(), NameplateDoc.KIND);
        this.defaults = new ShippedDefaults(NameplateDoc.KIND, folder,
            ShippedDocumentCatalog.NAMEPLATES.names());
        this.registry = DocumentRegistry.folder(NameplateDoc.KIND, folder, NameplateDoc::parse,
            NameplateDoc::revision);
        this.suppression = new NameplateSuppression(plugin.teams());
        this.source = new NameplateSource(this::enabled, this::documents, suppression);
    }

    @Override
    public String name() {
        return NAME;
    }

    @Override
    public void enable() {
        if (enabled()) {
            defaults.extractMissing();
        }
        registry.reload();
        rebuild();
        plugin.watchdog().register(NameplateDoc.KIND, this::poll);
        plugin.getServer().getPluginManager().registerEvents(this, plugin);
        syncSource();
    }

    @Override
    public void disable() {
        plugin.watchdog().unregister(NameplateDoc.KIND);
        HandlerList.unregisterAll(this);
        unregisterSource();
        suppression.clear();
        registry.close();
    }

    @Override
    public void reload() {
        if (enabled()) {
            defaults.extractMissing();
        }
        registry.reload();
        rebuild();
        syncSource();
    }

    @Override
    public boolean reloadOnConfigChange(GlossConfig previous, GlossConfig next) {
        return !previous.modules().nameplates().equals(next.modules().nameplates());
    }

    public boolean enabled() {
        return plugin.cfg().modules().nameplates().enabled();
    }

    public List<NameplateRuntime> documents() {
        return documents;
    }

    public DocumentRegistry<NameplateDoc> registry() {
        return registry;
    }

    public List<String> resetToDefault(String name) {
        List<String> restored = defaults.resetToDefault(name);
        if (!restored.isEmpty()) {
            registry.reload();
            rebuild();
        }
        return restored;
    }

    /** Drops every viewer's panes so the next overlay pass rebuilds them from the current documents. */
    public void refresh() {
        suppression.clear();
        rebuild();
    }

    /**
     * A quitting player is both a viewer whose claims go away and a subject other viewers were
     * hiding the tag of; releasing only the first left the second hidden for the rest of the
     * viewer's session.
     */
    @EventHandler
    public void onQuit(PlayerQuitEvent event) {
        suppression.forget(event.getPlayer());
        suppression.retireSubject(event.getPlayer().getUniqueId());
    }

    private void poll() {
        DocumentDelta delta = registry.poll();
        if (delta.isEmpty()) {
            return;
        }
        registry.apply(delta, this::rebuild);
    }

    private void rebuild() {
        Map<String, GlossDocument<NameplateDoc>> snapshot = registry.snapshot();
        List<NameplateRuntime> runtimes = new ArrayList<>(snapshot.size());
        for (Map.Entry<String, GlossDocument<NameplateDoc>> entry : snapshot.entrySet()) {
            runtimes.add(new NameplateRuntime(entry.getKey(), entry.getValue().value()));
        }
        documents = List.copyOf(runtimes);
    }

    private void syncSource() {
        if (enabled()) {
            registerSource();
        } else {
            unregisterSource();
            suppression.clear();
        }
    }

    private void registerSource() {
        EntityOverlayService overlays = plugin.entityOverlays();
        if (registered || overlays == null) {
            return;
        }
        overlays.registerSource(source);
        registered = true;
    }

    private void unregisterSource() {
        EntityOverlayService overlays = plugin.entityOverlays();
        if (!registered || overlays == null) {
            registered = false;
            return;
        }
        overlays.unregisterSource(source);
        registered = false;
    }
}
