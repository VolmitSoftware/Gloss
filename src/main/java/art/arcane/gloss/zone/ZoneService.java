package art.arcane.gloss.zone;

import art.arcane.gloss.Gloss;
import art.arcane.gloss.GlossConfig;
import art.arcane.gloss.condition.GlossConditionScope;
import art.arcane.gloss.doc.DocumentDelta;
import art.arcane.gloss.doc.DocumentRegistry;
import art.arcane.gloss.doc.GlossDocument;
import art.arcane.gloss.expr.ExprScope;
import art.arcane.gloss.particle.ViewerParticles;
import art.arcane.gloss.service.GlossService;
import art.arcane.gloss.service.ViewerLeases;
import art.arcane.gloss.service.VisibilityGovernor;
import art.arcane.gloss.state.PlayerSections;
import art.arcane.volmlib.util.scheduling.FoliaScheduler;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.HandlerList;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerChangedWorldEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.util.Vector;

import java.io.File;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;

/**
 * Drives {@code zones/} documents. Each viewer keeps a per-zone show/hide choice in their state
 * file and one shared particle budget; a zone whose geometry comes from another plugin resolves
 * through {@link WorldGuardRegionSource} and is retried until that plugin is up.
 */
public final class ZoneService implements GlossService, Listener {
    public static final String NAME = "zones";
    public static final String SECTION = "zones";
    static final int DRIVE_INTERVAL_TICKS = 5;
    /** Zones one viewer renders at once; beyond this the nearest win. */
    static final int MAX_ZONES_PER_VIEWER = 4;

    private final Gloss plugin;
    private final DocumentRegistry<ZoneDoc> registry;
    private final PlayerSections sections;
    private final WorldGuardRegionSource regions = new WorldGuardRegionSource();
    private final ConcurrentMap<UUID, ZoneRenderer> renderers = new ConcurrentHashMap<>();
    private final ViewerLeases leases = new ViewerLeases(VisibilityGovernor.Surface.ZONE);
    private volatile List<Entry> documents = List.of();
    private int driverTaskId = -1;

    private record Entry(String id, ZoneDoc doc) {
    }

    private record Visible(String id, ZoneDoc doc, ZoneShape shape, double distance) {
    }

    public ZoneService(Gloss plugin, PlayerSections sections) {
        this.plugin = plugin;
        File folder = new File(plugin.getDataFolder(), ZoneDoc.KIND);
        this.registry = DocumentRegistry.folder(ZoneDoc.KIND, folder, ZoneDoc::parse, ZoneDoc::revision);
        this.sections = sections;
    }

    @Override
    public String name() {
        return NAME;
    }

    @Override
    public void enable() {
        registry.reload();
        rebuild();
        plugin.watchdog().register(ZoneDoc.KIND, this::poll);
        plugin.getServer().getPluginManager().registerEvents(this, plugin);
        if (enabled()) {
            startDriver();
        }
    }

    @Override
    public void disable() {
        plugin.watchdog().unregister(ZoneDoc.KIND);
        HandlerList.unregisterAll(this);
        stopDriver();
        destroyAll();
        ViewerParticles.clear();
        registry.close();
    }

    @Override
    public void reload() {
        registry.reload();
        rebuild();
        regions.invalidate();
        ViewerParticles.clear();
        if (!enabled()) {
            stopDriver();
            destroyAll();
            return;
        }
        startDriver();
    }

    @Override
    public boolean reloadOnConfigChange(GlossConfig previous, GlossConfig next) {
        return !previous.modules().zones().equals(next.modules().zones());
    }

    public boolean enabled() {
        return plugin.cfg().modules().zones().enabled();
    }

    public DocumentRegistry<ZoneDoc> registry() {
        return registry;
    }

    public List<String> ids() {
        List<String> ids = new ArrayList<>(documents.size());
        for (Entry entry : documents) {
            ids.add(entry.id());
        }
        return List.copyOf(ids);
    }

    public ZoneDoc document(String id) {
        for (Entry entry : documents) {
            if (entry.id().equals(id)) {
                return entry.doc();
            }
        }
        return null;
    }

    public boolean shown(UUID viewerId, String zoneId) {
        return shown(sections.read(viewerId, SECTION), zoneId);
    }

    /** The same choice read from a set already in hand, so one pass costs one lookup, not one per zone. */
    private static boolean shown(Map<String, Object> choices, String zoneId) {
        return !(choices.get(zoneId) instanceof Boolean hidden) || !hidden;
    }

    /** Remembers a viewer's choice; only a hidden zone is written, so the file stays small. */
    public void show(UUID viewerId, String zoneId, boolean visible) {
        Map<String, Object> stored = new LinkedHashMap<>(sections.read(viewerId, SECTION));
        if (visible) {
            stored.remove(zoneId);
        } else {
            stored.put(zoneId, Boolean.TRUE);
        }
        sections.write(viewerId, SECTION, stored);
        ZoneRenderer renderer = renderers.get(viewerId);
        if (renderer != null && !visible) {
            renderer.retireAbsent(List.of());
        }
    }

    @EventHandler
    public void onQuit(PlayerQuitEvent event) {
        forget(event.getPlayer().getUniqueId());
    }

    @EventHandler
    public void onWorldChange(PlayerChangedWorldEvent event) {
        ZoneRenderer renderer = renderers.get(event.getPlayer().getUniqueId());
        if (renderer != null) {
            renderer.destroyAll();
        }
    }

    private void poll() {
        DocumentDelta delta = registry.poll();
        if (delta.isEmpty()) {
            return;
        }
        registry.apply(delta, this::rebuild);
    }

    private void rebuild() {
        Map<String, GlossDocument<ZoneDoc>> snapshot = registry.snapshot();
        List<Entry> entries = new ArrayList<>(snapshot.size());
        for (Map.Entry<String, GlossDocument<ZoneDoc>> entry : snapshot.entrySet()) {
            entries.add(new Entry(entry.getKey(), entry.getValue().value()));
        }
        documents = List.copyOf(entries);
    }

    private void startDriver() {
        if (driverTaskId != -1) {
            return;
        }
        driverTaskId = plugin.scheduler().sr(this::drive, DRIVE_INTERVAL_TICKS);
    }

    private void stopDriver() {
        if (driverTaskId == -1) {
            return;
        }
        plugin.scheduler().csr(driverTaskId);
        driverTaskId = -1;
    }

    private void drive() {
        if (!enabled()) {
            return;
        }
        for (Player viewer : Bukkit.getOnlinePlayers()) {
            FoliaScheduler.runEntity(plugin, viewer, () -> driveViewer(viewer), 0, null);
        }
    }

    private void driveViewer(Player viewer) {
        if (!enabled() || !viewer.isOnline()) {
            return;
        }
        List<Visible> visible = visibleZones(viewer);
        if (visible.isEmpty()) {
            ZoneRenderer idle = renderers.remove(viewer.getUniqueId());
            if (idle != null) {
                idle.destroyAll();
            }
            leases.release(viewer.getUniqueId());
            return;
        }
        if (!admit(viewer, visible.size() * ZoneWalls.MAX_PANELS_PER_ZONE)) {
            return;
        }
        ZoneRenderer renderer = renderers.computeIfAbsent(viewer.getUniqueId(),
            ignored -> new ZoneRenderer(plugin, viewer));
        List<String> live = new ArrayList<>(visible.size());
        for (Visible zone : visible) {
            live.add(zone.id());
        }
        renderer.retireAbsent(live);
        ZoneBudget budget = new ZoneBudget(plugin.cfg().modules().zones().particlesPerViewerPerTick());
        for (Visible zone : visible) {
            try {
                renderer.render(zone.id(), zone.doc(), zone.shape(), budget, zone.distance());
            } catch (RuntimeException failure) {
                Gloss.logExceptionStackThrottled(false, "zone-render:" + zone.id(), failure,
                    "Zone %s could not be rendered for %s.", zone.id(), viewer.getName());
            }
        }
    }

    private List<Visible> visibleZones(Player viewer) {
        ExprScope scope = GlossConditionScope.viewer(plugin, viewer);
        World world = viewer.getWorld();
        Location eye = viewer.getEyeLocation();
        Vector eyeVector = eye.toVector();
        double viewRange = plugin.cfg().modules().zones().viewRange();
        Map<String, Object> choices = sections.read(viewer.getUniqueId(), SECTION);
        List<Visible> visible = new ArrayList<>();
        for (Entry entry : documents) {
            ZoneDoc doc = entry.doc();
            if (!shown(choices, entry.id()) || !viewer.hasPermission(doc.toggle())) {
                continue;
            }
            if (!doc.show().matches(scope) || !doc.audience().when().matches(scope)) {
                continue;
            }
            ZoneShape shape = resolve(doc.shape(), world);
            if (shape == null || shape.world() == null || !shape.world().equals(world.getName())) {
                continue;
            }
            double distance = distance(shape, eyeVector);
            if (distance > viewRange) {
                continue;
            }
            visible.add(new Visible(entry.id(), doc, shape, distance));
        }
        visible.sort((left, right) -> Double.compare(left.distance(), right.distance()));
        return List.copyOf(visible.subList(0, Math.min(visible.size(), MAX_ZONES_PER_VIEWER)));
    }

    private ZoneShape resolve(ZoneShape shape, World world) {
        if (!shape.isRegion()) {
            return shape;
        }
        if (!shape.plugin().equals("worldguard")) {
            return null;
        }
        World target = shape.world() == null ? world : Bukkit.getWorld(shape.world());
        return target == null ? null : regions.shape(target, shape.id());
    }

    private static double distance(ZoneShape shape, Vector eye) {
        return ZoneGeometry.contains(shape, eye) ? 0.0D : ZoneGeometry.centre(shape).distance(eye);
    }

    private boolean admit(Player viewer, int entities) {
        return leases.admit(plugin.governor(), viewer, entities,
            () -> forget(viewer.getUniqueId()));
    }

    private void forget(UUID viewerId) {
        ZoneRenderer renderer = renderers.remove(viewerId);
        if (renderer != null) {
            renderer.destroyAll();
        }
        leases.release(viewerId);
    }

    private void destroyAll() {
        for (UUID viewerId : List.copyOf(renderers.keySet())) {
            forget(viewerId);
        }
    }
}
