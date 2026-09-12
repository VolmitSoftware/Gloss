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
import org.bukkit.entity.Entity;
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
    static final int DRIVE_INTERVAL_TICKS = 20;

    private final Gloss plugin;
    private final DocumentRegistry<WaypointDoc> registry;
    private final WaypointTracker tracker = new WaypointTracker();
    private final ConcurrentMap<UUID, List<MarkerSpec>> markerWaypoints = new ConcurrentHashMap<>();
    private volatile List<Entry> documents = List.of();
    private int driverTaskId = -1;

    private record Entry(WaypointSpec spec, art.arcane.gloss.condition.ShowCondition show,
                         art.arcane.gloss.condition.ShowCondition audience) {
    }

    public WaypointService(Gloss plugin) {
        this.plugin = plugin;
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
        registry.reload();
        rebuild();
        plugin.watchdog().register(WaypointDoc.KIND, this::poll);
        plugin.getServer().getPluginManager().registerEvents(this, plugin);
        if (enabled()) {
            startDriver();
        }
    }

    @Override
    public void disable() {
        plugin.watchdog().unregister(WaypointDoc.KIND);
        HandlerList.unregisterAll(this);
        stopDriver();
        untrackEveryone();
        registry.close();
    }

    @Override
    public void reload() {
        registry.reload();
        rebuild();
        if (!enabled()) {
            stopDriver();
            untrackEveryone();
            return;
        }
        startDriver();
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
        markerWaypoints.remove(viewerId);
        Waypoints.forget(viewerId);
    }

    @EventHandler
    public void onWorldChange(PlayerChangedWorldEvent event) {
        send(event.getPlayer(), tracker.forget(event.getPlayer().getUniqueId()));
    }

    private void poll() {
        DocumentDelta delta = registry.poll();
        if (delta.isEmpty()) {
            return;
        }
        registry.apply(delta, this::rebuild);
    }

    private void rebuild() {
        Map<String, GlossDocument<WaypointDoc>> snapshot = registry.snapshot();
        List<Entry> entries = new ArrayList<>(snapshot.size());
        for (Map.Entry<String, GlossDocument<WaypointDoc>> entry : snapshot.entrySet()) {
            WaypointDoc doc = entry.getValue().value();
            entries.add(new Entry(doc.toSpec(entry.getKey()), doc.show(), doc.audience().when()));
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
        if (!supported(viewer)) {
            send(viewer, tracker.forget(viewer.getUniqueId()));
            return;
        }
        Location eye = viewer.getEyeLocation();
        List<WaypointTarget> desired = WaypointTracker.capByDistance(targets(viewer, eye),
            eye.getX(), eye.getY(), eye.getZ(), plugin.cfg().modules().waypoints().maxPerViewer());
        send(viewer, tracker.reconcile(viewer.getUniqueId(), desired));
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
            add(targets, entry.spec(), world, eye);
        }
        for (WaypointSpec spec : Waypoints.tracked(viewer.getUniqueId())) {
            add(targets, spec, world, eye);
        }
        for (MarkerSpec marker : markerWaypoints.getOrDefault(viewer.getUniqueId(), List.of())) {
            add(targets, new WaypointSpec(marker.id(), marker.anchor(), marker.color(),
                WaypointStyle.DEFAULT.serializedName(), marker.maxDistance()), world, eye);
        }
        return List.copyOf(targets.values());
    }

    private void add(Map<String, WaypointTarget> targets, WaypointSpec spec, World world, Location eye) {
        Location position = resolve(spec.anchor());
        if (position == null || position.getWorld() != world) {
            return;
        }
        double distance = eye.distance(position);
        Float azimuth = spec.range() > 0.0D && distance > spec.range()
            ? (float) Math.atan2(position.getX() - eye.getX(), position.getZ() - eye.getZ())
            : null;
        targets.put(spec.id(), new WaypointTarget(spec.id(), spec.color(),
            WaypointStyle.parse(spec.style()), position.getX(), position.getY(), position.getZ(), azimuth));
    }

    private Location resolve(MarkerAnchor anchor) {
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

    private void send(Player viewer, List<WaypointTracker.Change> changes) {
        if (changes.isEmpty()) {
            return;
        }
        List<PacketWrapper<?>> packets = new ArrayList<>(changes.size());
        for (WaypointTracker.Change change : changes) {
            packets.add(WaypointPackets.of(change));
        }
        PacketUtils.send(viewer, packets);
    }

    private void untrackEveryone() {
        for (Player viewer : Bukkit.getOnlinePlayers()) {
            send(viewer, tracker.forget(viewer.getUniqueId()));
        }
        tracker.clear();
        markerWaypoints.clear();
    }
}
