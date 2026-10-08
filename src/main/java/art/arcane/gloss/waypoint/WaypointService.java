package art.arcane.gloss.waypoint;

import art.arcane.gloss.Gloss;
import art.arcane.gloss.GlossConfig;
import art.arcane.gloss.api.MarkerAnchor;
import art.arcane.gloss.api.WaypointSpec;
import art.arcane.gloss.api.Waypoints;
import art.arcane.gloss.condition.GlossConditionScope;
import art.arcane.gloss.doc.DocumentDelta;
import art.arcane.gloss.doc.DocumentRegistry;
import art.arcane.gloss.doc.GlossDocument;
import art.arcane.gloss.expr.ExprScope;
import art.arcane.gloss.forge.GlyphService;
import art.arcane.gloss.forge.PackNamespace;
import art.arcane.gloss.marker.MarkerSpec;
import art.arcane.gloss.service.GlossService;
import art.arcane.gloss.util.common.PacketUtils;
import art.arcane.volmlib.util.scheduling.FoliaScheduler;
import com.github.retrooper.packetevents.PacketEvents;
import com.github.retrooper.packetevents.protocol.player.ClientVersion;
import com.github.retrooper.packetevents.wrapper.PacketWrapper;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.HandlerList;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerChangedWorldEvent;
import org.bukkit.event.player.PlayerQuitEvent;

import java.io.File;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;

/**
 * Publishes locator-bar waypoints: {@code waypoints/} documents, specs other plugins registered
 * through {@link Waypoints}, and markers that declare {@code waypoint: true}. Clients older than
 * the locator bar and Bedrock viewers are skipped entirely.
 */
public final class WaypointService implements GlossService, Listener {
    public static final String NAME = "waypoints";

    private final Gloss plugin;
    private final DocumentRegistry<WaypointDoc> registry;
    private final WaypointTracker tracker;
    private final ConcurrentMap<UUID, Player> pendingClears = new ConcurrentHashMap<>();
    private final Set<UUID> queuedViewers = ConcurrentHashMap.newKeySet();
    private volatile boolean running;
    private final ConcurrentMap<UUID, List<MarkerSpec>> markerWaypoints = new ConcurrentHashMap<>();
    private volatile List<Entry> documents = List.of();
    private int driverTaskId = -1;

    private record Entry(WaypointSpec spec, art.arcane.gloss.condition.ShowCondition show,
                         art.arcane.gloss.condition.ShowCondition audience, String fallbackStyle) {
    }

    public WaypointService(Gloss plugin) {
        this.plugin = plugin;
        this.tracker = new WaypointTracker(() -> new WaypointTracker.Thresholds(
            plugin.cfg().modules().waypoints().positionThreshold(),
            plugin.cfg().modules().waypoints().azimuthThreshold()));
        File folder = new File(plugin.getDataFolder(), WaypointDoc.KIND);
        this.registry = DocumentRegistry.folder(WaypointDoc.KIND, folder, WaypointDoc::parse,
            WaypointDoc::revision);
    }

    @Override
    public String name() {
        return NAME;
    }

    @Override
    public void enable() {
        running = true;
        registry.reload();
        rebuild(registry.snapshot());
        plugin.watchdog().register(WaypointDoc.KIND, this::poll);
        plugin.getServer().getPluginManager().registerEvents(this, plugin);
        if (enabled() || !pendingClears.isEmpty()) {
            startDriver();
        }
    }

    @Override
    public void disable() {
        running = false;
        plugin.watchdog().unregister(WaypointDoc.KIND);
        HandlerList.unregisterAll(this);
        stopDriver();
        untrackEveryone();
        registry.close();
    }

    @Override
    public void reload() {
        registry.reload();
        rebuild(registry.snapshot());
        stopDriver();
        if (!enabled()) {
            untrackEveryone();
        }
        if (enabled() || !pendingClears.isEmpty()) {
            startDriver();
        }
    }

    @Override
    public boolean reloadOnConfigChange(GlossConfig previous, GlossConfig next) {
        return !previous.modules().waypoints().equals(next.modules().waypoints());
    }

    public boolean enabled() {
        return plugin.cfg().modules().waypoints().enabled();
    }

    public DocumentRegistry<WaypointDoc> registry() {
        return registry;
    }

    /** Markers that declare {@code waypoint: true}; the marker driver hands them over each pass. */
    public void publishMarkerWaypoints(Player viewer, List<MarkerSpec> markers) {
        if (markers.isEmpty()) {
            markerWaypoints.remove(viewer.getUniqueId());
            return;
        }
        markerWaypoints.put(viewer.getUniqueId(), List.copyOf(markers));
    }

    @EventHandler
    public void onQuit(PlayerQuitEvent event) {
        UUID viewerId = event.getPlayer().getUniqueId();
        tracker.forget(viewerId);
        pendingClears.remove(viewerId);
        queuedViewers.remove(viewerId);
        markerWaypoints.remove(viewerId);
        Waypoints.forget(viewerId);
    }

    @EventHandler
    public void onWorldChange(PlayerChangedWorldEvent event) {
        pendingClears.put(event.getPlayer().getUniqueId(), event.getPlayer());
        clearViewer(event.getPlayer());
    }

    private void poll() {
        DocumentDelta delta = registry.poll();
        if (delta.isEmpty()) {
            return;
        }
        registry.apply(delta, () -> rebuild(registry.snapshot(delta)));
    }

    private void rebuild(Map<String, GlossDocument<WaypointDoc>> snapshot) {
        List<Entry> entries = new ArrayList<>(snapshot.size());
        for (Map.Entry<String, GlossDocument<WaypointDoc>> entry : snapshot.entrySet()) {
            WaypointDoc doc = entry.getValue().value();
            entries.add(new Entry(doc.toSpec(entry.getKey()), doc.show(), doc.audience().when(), doc.fallbackStyle()));
        }
        documents = List.copyOf(entries);
    }

    private void startDriver() {
        if (driverTaskId != -1) {
            return;
        }
        driverTaskId = plugin.scheduler().sr(this::drive, plugin.cfg().modules().waypoints().refreshTicks());
    }

    private void stopDriver() {
        if (driverTaskId == -1) {
            return;
        }
        plugin.scheduler().csr(driverTaskId);
        driverTaskId = -1;
    }

    private void drive() {
        for (Player viewer : pendingClears.values()) {
            queue(viewer);
        }
        if (!enabled()) {
            if (pendingClears.isEmpty()) {
                stopDriver();
            }
            return;
        }
        for (Player viewer : Bukkit.getOnlinePlayers()) {
            queue(viewer);
        }
    }

    private void queue(Player viewer) {
        UUID id = viewer.getUniqueId();
        if (!queuedViewers.add(id)) {
            return;
        }
        Runnable retired = () -> {
            queuedViewers.remove(id);
            pendingClears.remove(id);
            tracker.forget(id);
        };
        if (!FoliaScheduler.runEntity(plugin, viewer, () -> {
            try {
                driveViewer(viewer);
            } finally {
                queuedViewers.remove(id);
            }
        }, 0, retired)) {
            queuedViewers.remove(id);
        }
    }

    private void driveViewer(Player viewer) {
        if (!viewer.isOnline()) {
            tracker.forget(viewer.getUniqueId());
            pendingClears.remove(viewer.getUniqueId());
            return;
        }
        if (pendingClears.containsKey(viewer.getUniqueId()) && !clearViewer(viewer)) {
            return;
        }
        if (!running || !enabled()) {
            return;
        }
        if (!supported(viewer)) {
            pendingClears.put(viewer.getUniqueId(), viewer);
            clearViewer(viewer);
            return;
        }
        Location eye = viewer.getEyeLocation();
        List<WaypointTarget> desired = WaypointTracker.capByDistance(targets(viewer, eye),
            eye.getX(), eye.getY(), eye.getZ(), plugin.cfg().modules().waypoints().maxPerViewer());
        tracker.reconcile(viewer.getUniqueId(), desired, changes -> send(viewer, changes));
    }

    private boolean clearViewer(Player viewer) {
        boolean[] accepted = {true};
        tracker.reconcile(viewer.getUniqueId(), List.of(), changes -> {
            accepted[0] = send(viewer, changes);
            return accepted[0];
        });
        if (accepted[0]) {
            pendingClears.remove(viewer.getUniqueId());
        }
        return accepted[0];
    }

    /** Bedrock has no locator bar, and the packet does not exist before the version that shipped it. */
    private boolean supported(Player viewer) {
        if (plugin.bedrock().isBedrock(viewer)) {
            return false;
        }
        try {
            ClientVersion version = PacketEvents.getAPI().getPlayerManager().getClientVersion(viewer);
            return WaypointPackets.supports(version);
        } catch (RuntimeException | LinkageError failure) {
            Gloss.logExceptionStackThrottled(false, "waypoint-client-version", failure,
                "Could not read the client version for %s; the locator bar was skipped.", viewer.getName());
            return false;
        }
    }

    private List<WaypointTarget> targets(Player viewer, Location eye) {
        Map<String, WaypointTarget> targets = new LinkedHashMap<>();
        ExprScope scope = GlossConditionScope.viewer(plugin, viewer);
        World world = viewer.getWorld();
        for (Entry entry : documents) {
            if (!entry.show().matches(scope) || !entry.audience().matches(scope)) {
                continue;
            }
            add(targets, entry.spec(), world, eye, selectedStyle(viewer, entry.spec().style(), entry.fallbackStyle()));
        }
        for (Waypoints.Registration registration : Waypoints.registered(viewer.getUniqueId())) {
            WaypointSpec spec = registration.spec();
            add(targets, spec, world, eye, selectedStyle(viewer, spec.style(), registration.options().fallbackStyle()));
        }
        for (MarkerSpec marker : markerWaypoints.getOrDefault(viewer.getUniqueId(), List.of())) {
            add(targets, new WaypointSpec(marker.id(), marker.anchor(), marker.color(),
                WaypointStyle.DEFAULT.serializedName(), marker.maxDistance()), world, eye, "default");
        }
        return List.copyOf(targets.values());
    }

    private void add(Map<String, WaypointTarget> targets, WaypointSpec spec, World world, Location eye, String style) {
        Location position = resolve(spec.anchor(), world);
        if (position == null || position.getWorld() != world) {
            return;
        }
        double distance = eye.distance(position);
        Float azimuth = spec.range() > 0.0D && distance > spec.range()
            ? (float) Math.atan2(position.getX() - eye.getX(), position.getZ() - eye.getZ())
            : null;
        targets.put(spec.id(), new WaypointTarget(spec.id(), spec.color(),
            WaypointStyle.DEFAULT, position.getX(), position.getY(), position.getZ(), azimuth, style));
    }

    private String selectedStyle(Player viewer, String style, String fallback) {
        WaypointStyleKey key = new WaypointStyleKey(style);
        if (key.builtIn()) {
            return key.resourceKey();
        }
        GlyphService glyphs = plugin.service(GlyphService.class);
        return key.select(fallback, PackNamespace.loaded(viewer) && glyphs != null
            && glyphs.glyphs().waypointStyles().containsKey(key.value()));
    }

    private Location resolve(MarkerAnchor anchor, World world) {
        return plugin.anchorSnapshots().position(anchor, world);
    }

    private boolean send(Player viewer, List<WaypointTracker.Change> changes) {
        if (changes.isEmpty()) {
            return true;
        }
        List<PacketWrapper<?>> packets = new ArrayList<>(changes.size());
        for (WaypointTracker.Change change : changes) {
            packets.add(WaypointPackets.of(change));
        }
        try {
            return PacketUtils.sendChecked(viewer, packets);
        } catch (RuntimeException failure) {
            Gloss.logExceptionStackThrottled(false, "waypoint-send", failure,
                "Could not send locator-bar updates to %s.", viewer.getName());
            return false;
        }
    }

    private void untrackEveryone() {
        for (Player viewer : Bukkit.getOnlinePlayers()) {
            pendingClears.put(viewer.getUniqueId(), viewer);
            queue(viewer);
        }
        markerWaypoints.clear();
    }
}
