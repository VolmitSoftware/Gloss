package art.arcane.gloss.beam;

import art.arcane.gloss.Gloss;
import art.arcane.gloss.menu.DisplayEntityGroup;
import art.arcane.gloss.menu.DisplayEntityManager;
import art.arcane.gloss.service.VisibilityGovernor;
import art.arcane.volmlib.util.scheduling.FoliaScheduler;
import art.arcane.gloss.api.BeamHandle;
import art.arcane.gloss.api.BeamSpec;
import art.arcane.gloss.service.GlossService;
import art.arcane.gloss.util.common.DisplayEntity;
import art.arcane.gloss.particle.ViewerParticles;
import art.arcane.gloss.util.common.EntityIdAllocator;
import art.arcane.gloss.util.common.PacketUtils;
import com.github.retrooper.packetevents.protocol.entity.type.EntityTypes;
import com.github.retrooper.packetevents.util.Vector3f;
import com.github.retrooper.packetevents.wrapper.PacketWrapper;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.HandlerList;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.util.Vector;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.Supplier;

/**
 * Beams and trails between two world points. A beam is one stretched block display per viewer,
 * re-teleported and re-transformed only when an anchor actually moved; a trail is a particle
 * polyline re-walked only after the viewer has moved far enough for the old points to look wrong.
 */
public final class BeamService implements GlossService, Listener {
    public static final String NAME = "beams";
    static final int DRIVE_INTERVAL_TICKS = 2;

    private final class Link implements BeamHandle {
        private final Supplier<Location> from;
        private final Supplier<Location> to;
        private final BeamSpec spec;
        private final Map<UUID, BeamVisual> displays = new ConcurrentHashMap<>();
        private final Set<UUID> audience;
        private Location sampledFrom;
        private Location sampledTo;
        private long remainingTicks;
        private volatile boolean live = true;

        private Link(Supplier<Location> from, Supplier<Location> to, BeamSpec spec, long lifetimeTicks,
                     Set<UUID> viewers) {
            this.from = from;
            this.to = to;
            this.spec = spec;
            this.remainingTicks = lifetimeTicks;
            this.audience = Set.copyOf(viewers);
        }

        @Override
        public void cancel() {
            if (!live) {
                return;
            }
            live = false;
            destroy();
            links.remove(this);
        }

        @Override
        public boolean active() {
            return live;
        }

        private void destroy() {
            for (BeamVisual visual : displays.values()) {
                DisplayEntityManager.retire(plugin, visual.group());
            }
            displays.clear();
        }

        private void update() {
            Location currentFrom = from.get();
            Location currentTo = to.get();
            if (currentFrom == null || currentTo == null
                || currentFrom.getWorld() == null || currentFrom.getWorld() != currentTo.getWorld()) {
                cancel();
                return;
            }
            boolean moved = sampledFrom == null || !sampledFrom.equals(currentFrom)
                || !sampledTo.equals(currentTo);
            BeamMath.Transform transform = BeamMath.beamBetween(currentFrom.toVector(),
                currentTo.toVector(), spec.width());
            Location midpoint = new Location(currentFrom.getWorld(), transform.midpoint().getX(),
                transform.midpoint().getY(), transform.midpoint().getZ());
            for (UUID viewerId : audience) {
                Player viewer = Bukkit.getPlayer(viewerId);
                if (viewer == null || !viewer.isOnline()) {
                    BeamVisual removed = displays.remove(viewerId);
                    if (removed != null) {
                        removed.group().disconnected();
                    }
                    continue;
                }
                FoliaScheduler.runEntity(plugin, viewer, () -> updateViewer(viewer, midpoint, transform, moved));
            }
            sampledFrom = currentFrom.clone();
            sampledTo = currentTo.clone();
        }

        private void updateViewer(Player viewer, Location midpoint, BeamMath.Transform transform, boolean moved) {
            if (!live || !viewer.isOnline()) {
                BeamVisual removed = displays.remove(viewer.getUniqueId());
                if (removed != null && !viewer.isOnline()) {
                    removed.group().disconnected();
                }
                return;
            }
            UUID viewerId = viewer.getUniqueId();
            Location location = viewer.getLocation();
            boolean culled = location.getWorld() != midpoint.getWorld()
                || plugin.governor().tier(viewer, VisibilityGovernor.Surface.SURFACE,
                    location.distanceSquared(midpoint)) == VisibilityGovernor.Tier.CULLED;
            BeamVisual visual = displays.get(viewerId);
            if (visual == null && !culled) {
                DisplayEntity display = new DisplayEntity(EntityIdAllocator.global().next(),
                    UUID.randomUUID(), EntityTypes.BLOCK_DISPLAY)
                    .displayKind(DisplayEntity.DisplayKind.BLOCK).blockState(spec.blockState()).noGravity(true);
                display.location(PacketUtils.vector3d(midpoint.toVector()));
                apply(display, transform);
                DisplayEntityGroup group = DisplayEntityManager.group(viewer, VisibilityGovernor.Surface.SURFACE);
                UUID handle = DisplayEntityManager.add(group, display);
                visual = new BeamVisual(display, group, handle);
                displays.put(viewerId, visual);
                if (!live) {
                    displays.remove(viewerId, visual);
                    DisplayEntityManager.retire(plugin, group);
                    return;
                }
                DisplayEntityManager.spawn(handle, viewer);
                return;
            }
            if (visual == null) {
                return;
            }
            visual.group().culled(culled);
            if (moved) {
                reshape(viewer, visual.display(), midpoint, transform, visual.group().visible() && !culled);
            }
            visual.group().refresh();
        }

        private void reshape(Player viewer, DisplayEntity display, Location midpoint,
                             BeamMath.Transform transform, boolean send) {
            List<PacketWrapper<?>> packets = new ArrayList<>(2);
            packets.add(display.goTo(midpoint));
            apply(display, transform);
            packets.add(DisplayEntity.transformUpdate(display.id(), display.translation(),
                display.scale(), display.leftRotation(), display.rightRotation(),
                0, DRIVE_INTERVAL_TICKS));
            if (send) {
                PacketUtils.send(viewer, packets);
            }
        }

        private void apply(DisplayEntity display, BeamMath.Transform transform) {
            display.scale(transform.scale());
            display.leftRotation(transform.rotation());
            display.translation(new Vector3f((float) (-spec.width() / 2.0D),
                (float) (-spec.width() / 2.0D), (float) (-transform.scale().z / 2.0D)));
        }

        /** A lifetime of zero never expires; anything else burns one driver interval per pass. */
        private boolean expired() {
            if (remainingTicks <= 0) {
                return false;
            }
            remainingTicks -= DRIVE_INTERVAL_TICKS;
            return remainingTicks <= 0;
        }
    }

    private record BeamVisual(DisplayEntity display, DisplayEntityGroup group, UUID handle) {
    }

    private final Gloss plugin;
    private final CopyOnWriteArrayList<Link> links = new CopyOnWriteArrayList<>();
    /**
     * One sampled origin per viewer <em>and</em> trail: a viewer with two markers in range walks
     * both in the same pass from the same eye position, so a single origin per viewer skipped
     * every trail after the first. Concurrent because Folia drives each viewer on their own
     * region thread.
     */
    private final ConcurrentMap<TrailKey, Vector> trailOrigins = new ConcurrentHashMap<>();
    private int driverTaskId = -1;

    public BeamService(Gloss plugin) {
        this.plugin = Objects.requireNonNull(plugin, "plugin");
    }

    @Override
    public String name() {
        return NAME;
    }

    @Override
    public void enable() {
        plugin.getServer().getPluginManager().registerEvents(this, plugin);
        if (driverTaskId == -1) {
            driverTaskId = plugin.scheduler().sr(this::tick, DRIVE_INTERVAL_TICKS);
        }
    }

    @Override
    public void disable() {
        HandlerList.unregisterAll(this);
        if (driverTaskId != -1) {
            plugin.scheduler().csr(driverTaskId);
            driverTaskId = -1;
        }
        for (Link link : List.copyOf(links)) {
            link.cancel();
        }
        links.clear();
        trailOrigins.clear();
    }

    public BeamHandle link(Supplier<Location> from, Supplier<Location> to, BeamSpec spec,
                       long lifetimeTicks, Set<UUID> viewers) {
        Link link = new Link(Objects.requireNonNull(from, "from"), Objects.requireNonNull(to, "to"),
            Objects.requireNonNull(spec, "spec"), Math.max(0L, lifetimeTicks),
            Set.copyOf(viewers));
        links.add(link);
        link.update();
        return link;
    }

    /**
     * Walks a particle line from {@code from} to {@code to} for one viewer. The walk is skipped
     * while the viewer has not moved {@link BeamMath#TRAIL_RESAMPLE_DISTANCE} blocks since the last
     * one, so a standing player costs nothing.
     */
    public void trail(Player viewer, Location from, Location to, String particle, double spacing,
                      int maxPoints) {
        trail(viewer, destinationId(to), from, to, particle, spacing, maxPoints, 0xFFFFFF);
    }

    /**
     * @param trailId what this trail is for, so two trails a viewer holds at once each keep their
     *     own resample origin instead of the second one being skipped as unmoved
     */
    public void trail(Player viewer, String trailId, Location from, Location to, String particle,
                      double spacing, int maxPoints, int color) {
        if (from.getWorld() == null || from.getWorld() != to.getWorld()) {
            return;
        }
        ViewerParticles.Resolved resolved = ViewerParticles.resolve(particle, color);
        if (resolved == null) {
            Gloss.warnThrottled("beam-trail-particle:" + particle,
                "Trail particle %s is not a particle this server knows; the trail was skipped.", particle);
            return;
        }
        Vector origin = from.toVector();
        if (!shouldWalk(viewer.getUniqueId(), trailId, origin)) {
            return;
        }
        World world = from.getWorld();
        for (Vector point : BeamMath.trailPoints(origin, to.toVector(), spacing, maxPoints)) {
            ViewerParticles.spawn(viewer, resolved, world, point);
        }
    }

    boolean shouldWalk(UUID viewerId, String trailId, Vector origin) {
        TrailKey key = new TrailKey(viewerId, trailId);
        Vector sampledAt = trailOrigins.get(key);
        if (!BeamMath.shouldResample(sampledAt, origin)) {
            return false;
        }
        trailOrigins.put(key, origin);
        return true;
    }

    public void forget(UUID viewerId) {
        trailOrigins.keySet().removeIf(key -> key.viewerId().equals(viewerId));
    }

    @EventHandler
    public void onQuit(PlayerQuitEvent event) {
        UUID viewerId = event.getPlayer().getUniqueId();
        for (Link link : links) {
            BeamVisual visual = link.displays.remove(viewerId);
            if (visual != null) {
                visual.group().disconnected();
            }
        }
        forget(viewerId);
    }

    /** A caller without an id of its own gets one per destination block, which is the next best key. */
    private static String destinationId(Location to) {
        return to.getBlockX() + "," + to.getBlockY() + "," + to.getBlockZ();
    }

    private record TrailKey(UUID viewerId, String trailId) {
    }

    /** One driver pass: expire what ran out, then update what moved. */
    void tick() {
        for (Link link : List.copyOf(links)) {
            if (link.expired()) {
                link.cancel();
                continue;
            }
            link.update();
        }
    }
}
