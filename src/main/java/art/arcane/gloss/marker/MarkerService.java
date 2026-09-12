package art.arcane.gloss.marker;

import art.arcane.gloss.Gloss;
import art.arcane.gloss.GlossConfig;
import art.arcane.gloss.api.MarkerAnchor;
import art.arcane.gloss.api.MarkerProviders;
import art.arcane.gloss.beam.BeamService;
import art.arcane.gloss.condition.GlossConditionScope;
import art.arcane.gloss.doc.DocumentDelta;
import art.arcane.gloss.doc.DocumentRegistry;
import art.arcane.gloss.doc.GlossDocument;
import art.arcane.gloss.expr.ExprScope;
import art.arcane.gloss.expr.ExprVariableNamespaces;
import art.arcane.gloss.service.GlossService;
import art.arcane.gloss.service.ViewerLeases;
import art.arcane.gloss.service.VisibilityGovernor;
import art.arcane.gloss.state.PlayerSections;
import art.arcane.gloss.waypoint.WaypointService;
import art.arcane.volmlib.util.scheduling.FoliaScheduler;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.HandlerList;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerChangedWorldEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.util.Vector;

import java.io.File;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;

/**
 * Drives world markers: {@code markers/} documents plus registered providers plus each player's
 * own saved positions. Every pass runs on the viewer's own region thread, keeps the nearest
 * {@code [markers] maxPerViewer} and diffs the result against what that viewer already sees.
 */
public final class MarkerService implements GlossService, Listener {
    public static final String NAME = "markers";
    static final int DRIVE_INTERVAL_TICKS = 10;

    private final Gloss plugin;
    private final DocumentRegistry<MarkerDoc> registry;
    private final PersonalMarkers personal;
    private final MarkerNamespace namespace = new MarkerNamespace();
    private final ConcurrentMap<UUID, MarkerRenderer> renderers = new ConcurrentHashMap<>();
    private final ViewerLeases leases = new ViewerLeases(VisibilityGovernor.Surface.MARKER);
    private volatile List<MarkerRuntime> documents = List.of();
    private boolean namespaceRegistered;
    private int driverTaskId = -1;

    public MarkerService(Gloss plugin, PlayerSections sections) {
        this.plugin = plugin;
        File folder = new File(plugin.getDataFolder(), MarkerDoc.KIND);
        this.registry = DocumentRegistry.folder(MarkerDoc.KIND, folder, MarkerDoc::parse, MarkerDoc::revision);
        this.personal = new PersonalMarkers(sections);
    }

    @Override
    public String name() {
        return NAME;
    }

    @Override
    public void contribute() {
        if (!namespaceRegistered) {
            ExprVariableNamespaces.global().register(namespace);
            namespaceRegistered = true;
        }
    }

    @Override
    public void enable() {
        registry.reload();
        rebuild();
        plugin.watchdog().register(MarkerDoc.KIND, this::poll);
        plugin.getServer().getPluginManager().registerEvents(this, plugin);
        if (enabled()) {
            startDriver();
        }
    }

    @Override
    public void disable() {
        plugin.watchdog().unregister(MarkerDoc.KIND);
        HandlerList.unregisterAll(this);
        stopDriver();
        destroyAll();
        registry.close();
        if (namespaceRegistered) {
            ExprVariableNamespaces.global().unregister(MarkerNamespace.PREFIX);
            namespaceRegistered = false;
        }
    }

    @Override
    public void reload() {
        registry.reload();
        rebuild();
        if (!enabled()) {
            stopDriver();
            destroyAll();
            return;
        }
        startDriver();
    }

    @Override
    public boolean reloadOnConfigChange(GlossConfig previous, GlossConfig next) {
        return !previous.modules().markers().equals(next.modules().markers());
    }

    public PersonalMarkers personalMarkers() {
        return personal;
    }

    public DocumentRegistry<MarkerDoc> registry() {
        return registry;
    }

    public List<MarkerRuntime> documents() {
        return documents;
    }

    public boolean enabled() {
        return plugin.cfg().modules().markers().enabled();
    }

    @EventHandler
    public void onQuit(PlayerQuitEvent event) {
        forget(event.getPlayer().getUniqueId());
    }

    @EventHandler
    public void onWorldChange(PlayerChangedWorldEvent event) {
        MarkerRenderer renderer = renderers.get(event.getPlayer().getUniqueId());
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
        Map<String, GlossDocument<MarkerDoc>> snapshot = registry.snapshot();
        List<MarkerRuntime> runtimes = new ArrayList<>(snapshot.size());
        for (Map.Entry<String, GlossDocument<MarkerDoc>> entry : snapshot.entrySet()) {
            MarkerDoc doc = entry.getValue().value();
            runtimes.add(MarkerRuntime.of(doc.toSpec(entry.getKey()), doc.show()));
        }
        documents = List.copyOf(runtimes);
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
        for (Player viewer : plugin.getServer().getOnlinePlayers()) {
            FoliaScheduler.runEntity(plugin, viewer, () -> driveViewer(viewer), 0, null);
        }
    }

    private void driveViewer(Player viewer) {
        if (!enabled() || !viewer.isOnline()) {
            return;
        }
        List<MarkerCandidate> selected = MarkerSelection.select(candidates(viewer),
            plugin.cfg().modules().markers().maxPerViewer());
        publishWaypoints(viewer, selected);
        if (plugin.bedrock().isBedrock(viewer)) {
            MarkerRenderer bedrock = renderers.remove(viewer.getUniqueId());
            if (bedrock != null) {
                bedrock.destroyAll();
            }
            leases.release(viewer.getUniqueId());
            return;
        }
        if (selected.isEmpty()) {
            MarkerRenderer idle = renderers.remove(viewer.getUniqueId());
            if (idle != null) {
                idle.destroyAll();
            }
            leases.release(viewer.getUniqueId());
            return;
        }
        if (!admit(viewer, selected.size())) {
            return;
        }
        MarkerRenderer renderer = renderers.computeIfAbsent(viewer.getUniqueId(),
            ignored -> new MarkerRenderer(plugin, viewer));
        renderer.retireAbsent(selected);
        Location eye = viewer.getEyeLocation();
        for (MarkerCandidate candidate : selected) {
            renderOne(viewer, renderer, candidate, eye);
        }
    }

    private void renderOne(Player viewer, MarkerRenderer renderer, MarkerCandidate candidate, Location eye) {
        Location anchor = new Location(eye.getWorld(), candidate.x(), candidate.y(), candidate.z());
        MarkerNamespace.push(new MarkerContext(candidate.id(), candidate.x(), candidate.y(), candidate.z(),
            candidate.distance()));
        try {
            String label = candidate.spec().label().isEmpty() ? ""
                : plugin.text().render(viewer, candidate.spec().label());
            double scale = candidate.runtime().scale(GlossConditionScope.viewer(plugin, viewer));
            EdgeIndicatorMath.Indicator indicator = candidate.spec().edge().enabled()
                ? EdgeIndicatorMath.resolve(eye.getYaw(), eye.getPitch(),
                    new Vector(candidate.x() - eye.getX(), candidate.y() - eye.getY(),
                        candidate.z() - eye.getZ()), candidate.spec().edge().margin())
                : null;
            renderer.apply(candidate, anchor, label, scale, indicator);
            renderTrail(viewer, candidate, eye, anchor);
        } catch (RuntimeException failure) {
            Gloss.logExceptionStackThrottled(false, "marker-render:" + candidate.id(), failure,
                "Marker %s could not be rendered for %s.", candidate.id(), viewer.getName());
        } finally {
            MarkerNamespace.pop();
        }
    }

    /** A Bedrock viewer sees no marker entities, so its waypoint markers are its only navigation. */
    /** A trail is the viewer's own particle line to the marker, re-walked only when they move. */
    private void renderTrail(Player viewer, MarkerCandidate candidate, Location eye, Location anchor) {
        MarkerSpec.Trail trail = candidate.spec().trail();
        if (!trail.enabled()) {
            return;
        }
        BeamService beams = plugin.service(BeamService.class);
        if (beams == null) {
            return;
        }
        beams.trail(viewer, candidate.id(), eye, anchor, trail.particle(), trail.spacing(),
            trail.maxPoints());
    }

    private void publishWaypoints(Player viewer, List<MarkerCandidate> selected) {
        WaypointService waypoints = plugin.service(WaypointService.class);
        if (waypoints == null) {
            return;
        }
        List<MarkerSpec> specs = new ArrayList<>(selected.size());
        for (MarkerCandidate candidate : selected) {
            if (candidate.spec().waypoint()) {
                specs.add(candidate.spec());
            }
        }
        waypoints.publishMarkerWaypoints(viewer, specs);
    }

    private List<MarkerCandidate> candidates(Player viewer) {
        World world = viewer.getWorld();
        Location eye = viewer.getEyeLocation();
        double viewRange = plugin.cfg().modules().markers().viewRange();
        ExprScope scope = GlossConditionScope.viewer(plugin, viewer);
        List<MarkerCandidate> candidates = new ArrayList<>();
        for (MarkerRuntime runtime : documents) {
            add(candidates, runtime, viewer, world, eye, viewRange, scope);
        }
        for (MarkerSpec spec : MarkerProviders.collect(viewer)) {
            add(candidates, MarkerRuntime.of(spec), viewer, world, eye, viewRange, scope);
        }
        for (MarkerSpec spec : personal.list(viewer.getUniqueId())) {
            add(candidates, MarkerRuntime.of(spec), viewer, world, eye, viewRange, scope);
        }
        return candidates;
    }

    private void add(List<MarkerCandidate> candidates, MarkerRuntime runtime, Player viewer, World world,
                     Location eye, double viewRange, ExprScope scope) {
        Location position = resolve(runtime.spec().anchor(), world);
        if (position == null || position.getWorld() != world) {
            return;
        }
        double distance = eye.distance(position);
        if (distance > viewRange) {
            return;
        }
        MarkerNamespace.push(new MarkerContext(runtime.id(), position.getX(), position.getY(),
            position.getZ(), distance));
        try {
            if (!runtime.visible(scope)) {
                return;
            }
        } finally {
            MarkerNamespace.pop();
        }
        candidates.add(new MarkerCandidate(runtime, world.getName(), position.getX(), position.getY(),
            position.getZ(), distance));
    }

    private Location resolve(MarkerAnchor anchor, World world) {
        if (anchor.isPosition()) {
            World anchored = Bukkit.getWorld(anchor.world());
            return anchored == null ? null : new Location(anchored, anchor.x(), anchor.y(), anchor.z());
        }
        if (anchor.followsEntity()) {
            Entity entity = Bukkit.getEntity(anchor.entity());
            return entity == null || !entity.isValid() ? null : entity.getLocation();
        }
        Player target = Bukkit.getPlayerExact(anchor.player());
        return target == null || !target.isOnline() ? null : target.getLocation();
    }

    private boolean admit(Player viewer, int entities) {
        return leases.admit(plugin.governor(), viewer, entities, () -> forget(viewer.getUniqueId()));
    }

    private void forget(UUID viewerId) {
        MarkerRenderer renderer = renderers.remove(viewerId);
        if (renderer != null) {
            renderer.destroyAll();
        }
        leases.release(viewerId);
        BeamService beams = plugin.service(BeamService.class);
        if (beams != null) {
            beams.forget(viewerId);
        }
    }

    private void destroyAll() {
        for (UUID viewerId : List.copyOf(renderers.keySet())) {
            forget(viewerId);
        }
    }
}
