package art.arcane.gloss.interaction;

import art.arcane.gloss.Gloss;
import art.arcane.gloss.menu.DisplayEntityGroup;
import art.arcane.gloss.menu.DisplayEntityManager;
import art.arcane.gloss.service.VisibilityGovernor;
import art.arcane.gloss.api.HoloClickTrigger;
import art.arcane.gloss.hologram.HologramService;
import art.arcane.gloss.menu.action.MenuAction;
import art.arcane.gloss.service.GlossService;
import art.arcane.gloss.util.common.DisplayEntity;
import art.arcane.gloss.util.common.EntityIdAllocator;
import art.arcane.gloss.util.common.PacketUtils;
import art.arcane.gloss.util.common.math.CollisionPlane;
import art.arcane.volmlib.util.scheduling.FoliaScheduler;
import com.github.retrooper.packetevents.PacketEvents;
import com.github.retrooper.packetevents.event.PacketListenerCommon;
import com.github.retrooper.packetevents.protocol.entity.type.EntityTypes;
import com.github.retrooper.packetevents.util.Vector3f;
import com.github.retrooper.packetevents.wrapper.PacketWrapper;
import com.github.retrooper.packetevents.wrapper.play.client.WrapperPlayClientInteractEntity;
import org.bukkit.FluidCollisionMode;
import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.HandlerList;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.util.RayTraceResult;
import org.bukkit.util.Vector;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Predicate;

public final class InteractionHitboxService implements GlossService, Listener {
    public static final String NAME = "interaction";
    public static final double INTERACTION_RANGE = 8.0D;
    public static final int WALK_INTERVAL_TICKS = 5;
    /**
     * Hitboxes one viewer holds at once; beyond this the nearest win. An interactive hologram
     * registers one, or one per line when {@code hitbox.perLine} is set,
     * so a lobby with forty eight-line holograms around the spawn point would otherwise send every
     * viewer who stands there three hundred interaction entities with nothing bounding it.
     */
    public static final int MAX_HITBOXES_PER_VIEWER = 128;
    private static final double MOVE_EPSILON_SQUARED = 1.0E-6D;
    private static final double RAY_SLACK = 1.5D;
    private static final int NO_TASK = -1;

    public interface Sink {
        void send(Player viewer, List<PacketWrapper<?>> packets);
    }

    public interface ViewerSource {
        List<Player> nearby(Location anchor, double range);
    }

    public interface ClickRunner {
        void run(Player player, Runnable action);
    }

    public interface Obstruction {
        boolean obstructed(Player player, double distance);
    }

    public record Handle(long id, int entityId) {
    }

    private static final class Registration {
        private final Handle handle;
        private final InteractionTarget target;
        private final DisplayEntity entity;
        private final Map<UUID, Player> spawned = new ConcurrentHashMap<>();
        private final Map<UUID, DisplayEntityGroup> groups = new ConcurrentHashMap<>();
        private final UUID displayHandle = UUID.randomUUID();
        private volatile Location lastPosition;

        private Registration(Handle handle, InteractionTarget target, DisplayEntity entity) {
            this.handle = handle;
            this.target = target;
            this.entity = entity;
        }
    }

    private final Gloss plugin;
    private final Sink sink;
    private final ViewerSource viewers;
    private final ClickRunner runner;
    private final Obstruction obstruction;
    private final Predicate<UUID> bedrock;
    private final Map<Long, Registration> registrations = new ConcurrentHashMap<>();
    private final Map<Integer, Registration> byEntityId = new ConcurrentHashMap<>();
    private final AtomicLong handles = new AtomicLong();
    private PacketListenerCommon listener;
    private int walkTaskId = NO_TASK;

    public InteractionHitboxService(Gloss plugin) {
        this(plugin, InteractionHitboxService::sendPackets, viewersOf(plugin), (player, action) -> runOnRegion(plugin, player, action),
            (player, distance) -> blockObstructed(plugin, player, distance), id -> plugin.bedrock().isBedrock(id));
    }

    InteractionHitboxService(Sink sink, ViewerSource viewers, ClickRunner runner, Obstruction obstruction, Predicate<UUID> bedrock) {
        this(null, sink, viewers, runner, obstruction, bedrock);
    }

    private InteractionHitboxService(Gloss plugin, Sink sink, ViewerSource viewers, ClickRunner runner, Obstruction obstruction,
                                     Predicate<UUID> bedrock) {
        this.plugin = plugin;
        this.sink = sink;
        this.viewers = viewers;
        this.runner = runner;
        this.obstruction = obstruction;
        this.bedrock = bedrock;
    }

    @Override
    public String name() {
        return NAME;
    }

    @Override
    public void enable() {
        if (plugin == null) {
            return;
        }
        plugin.getServer().getPluginManager().registerEvents(this, plugin);
        listener = PacketEvents.getAPI().getEventManager().registerListener(new InteractionListener(this));
        walkTaskId = plugin.scheduler().sr(this::walk, WALK_INTERVAL_TICKS);
    }

    @Override
    public void disable() {
        HandlerList.unregisterAll(this);
        if (plugin != null && walkTaskId != NO_TASK) {
            plugin.scheduler().csr(walkTaskId);
            walkTaskId = NO_TASK;
        }
        if (listener != null) {
            PacketEvents.getAPI().getEventManager().unregisterListener(listener);
            listener = null;
        }
        for (Registration registration : List.copyOf(registrations.values())) {
            unregister(registration.handle);
        }
    }

    public Handle register(InteractionTarget target) {
        int entityId = EntityIdAllocator.global().next();
        Handle handle = new Handle(handles.incrementAndGet(), entityId);
        DisplayEntity entity = new DisplayEntity(entityId, UUID.randomUUID(), EntityTypes.INTERACTION)
            .displayKind(DisplayEntity.DisplayKind.RAW)
            .noGravity(true);
        Registration registration = new Registration(handle, target, entity);
        registrations.put(handle.id(), registration);
        byEntityId.put(entityId, registration);
        return handle;
    }

    public void unregister(Handle handle) {
        Registration registration = registrations.remove(handle.id());
        if (registration == null) {
            return;
        }
        byEntityId.remove(handle.entityId());
        retire(registration);
    }

    public InteractionTarget target(int entityId) {
        Registration registration = byEntityId.get(entityId);
        return registration == null ? null : registration.target;
    }

    public int registeredCount() {
        return registrations.size();
    }

    public int spawnedCount() {
        int count = 0;
        for (Registration registration : registrations.values()) {
            count += registration.spawned.size();
        }
        return count;
    }

    @EventHandler
    public void onQuit(PlayerQuitEvent event) {
        forgetViewer(event.getPlayer().getUniqueId());
    }

    public void forgetViewer(UUID viewerId) {
        for (Registration registration : registrations.values()) {
            registration.spawned.remove(viewerId);
            DisplayEntityGroup group = registration.groups.remove(viewerId);
            if (group != null) {
                group.disconnected();
            }
        }
    }

    /**
     * One pass: sample every registration, collect what each viewer is a candidate for, admit the
     * nearest up to the per-viewer cap, then spawn and despawn to match.
     */
    void walk() {
        Map<UUID, ViewerPass> passes = new LinkedHashMap<>();
        for (Registration registration : registrations.values()) {
            Sampled sampled = sample(registration);
            if (sampled == null) {
                continue;
            }
            Map<UUID, Player> candidates = new LinkedHashMap<>();
            for (Player viewer : viewers.nearby(sampled.position(), INTERACTION_RANGE)) {
                candidates.put(viewer.getUniqueId(), viewer);
            }
            Set<UUID> nearby = Set.copyOf(candidates.keySet());
            for (DisplayEntityGroup group : registration.groups.values()) {
                candidates.putIfAbsent(group.viewer().getUniqueId(), group.viewer());
            }
            for (Map.Entry<UUID, Player> entry : candidates.entrySet()) {
                ViewerPass pass = passes.computeIfAbsent(entry.getKey(),
                    ignored -> new ViewerPass(entry.getValue(), new ArrayList<>(), new LinkedHashSet<>()));
                pass.samples().add(sampled);
                if (nearby.contains(entry.getKey())) {
                    pass.offered().add(registration.handle.id());
                }
            }
        }
        for (ViewerPass pass : passes.values()) {
            runner.run(pass.viewer(), () -> settleViewer(pass));
        }
    }

    private void settleViewer(ViewerPass pass) {
        Player viewer = pass.viewer();
        List<Sampled> candidates = new ArrayList<>(pass.samples().size());
        if (viewer.isOnline() && !bedrock.test(viewer.getUniqueId())) {
            for (Sampled sampled : pass.samples()) {
                if (pass.offered().contains(sampled.registration().handle.id())
                    && registrations.get(sampled.registration().handle.id()) == sampled.registration()
                    && sampled.registration().target.visibility().shown().test(viewer)
                    && distanceSquared(viewer, sampled.position()) <= INTERACTION_RANGE * INTERACTION_RANGE) {
                    candidates.add(sampled);
                }
            }
        }
        if (candidates.size() > MAX_HITBOXES_PER_VIEWER) {
            candidates.sort(Comparator.comparingDouble(sampled -> distanceSquared(viewer, sampled.position())));
        }
        Set<Long> selected = new LinkedHashSet<>();
        for (int index = 0; index < Math.min(candidates.size(), MAX_HITBOXES_PER_VIEWER); index++) {
            selected.add(candidates.get(index).registration().handle.id());
        }
        for (Sampled sampled : pass.samples()) {
            Registration registration = sampled.registration();
            if (!selected.contains(registration.handle.id())) {
                despawn(registration, viewer);
                continue;
            }
            spawn(registration, viewer);
            if (sampled.moved() && registration.spawned.containsKey(viewer.getUniqueId())) {
                sink.send(viewer, List.of(registration.entity.goTo(sampled.position())));
            }
        }
    }

    private record ViewerPass(Player viewer, List<Sampled> samples, Set<Long> offered) {
    }

    /** @return where this registration is this pass, or null when it has gone away */
    private Sampled sample(Registration registration) {
        InteractionTarget target = registration.target;
        Location position = target.live().getAsBoolean() ? target.position().get() : null;
        if (position == null || position.getWorld() == null) {
            retire(registration);
            return null;
        }
        Location previous = registration.lastPosition;
        boolean moved = previous == null || previous.getWorld() != position.getWorld()
            || previous.distanceSquared(position) > MOVE_EPSILON_SQUARED;
        if (moved) {
            registration.entity.goTo(position);
            registration.lastPosition = position.clone();
        }
        return new Sampled(registration, position, moved);
    }

    /** Far enough to sort last when the two are not comparable, never near enough to win a slot. */
    private static double distanceSquared(Player viewer, Location position) {
        Location at = viewer.getLocation();
        if (at == null || at.getWorld() == null || at.getWorld() != position.getWorld()) {
            return Double.MAX_VALUE;
        }
        return at.distanceSquared(position);
    }

    private record Sampled(Registration registration, Location position, boolean moved) {
    }

    private void retire(Registration registration) {
        for (DisplayEntityGroup group : registration.groups.values()) {
            if (plugin == null) {
                group.close();
            } else {
                DisplayEntityManager.retire(plugin, group);
            }
        }
        registration.groups.clear();
    }

    private void spawn(Registration registration, Player viewer) {
        DisplayEntityGroup group = registration.groups.computeIfAbsent(viewer.getUniqueId(), ignored ->
            new DisplayEntityGroup(new DisplayEntityGroup.Options(viewer, registration.target.visibility().surface(),
                () -> plugin == null ? VisibilityGovernor.passthrough() : plugin.governor(),
                new HitboxTransport(registration, viewer))));
        VisibilityGovernor governor = plugin == null ? VisibilityGovernor.passthrough() : plugin.governor();
        group.culled(governor.tier(viewer, registration.target.visibility().surface(),
            distanceSquared(viewer, registration.lastPosition)) == VisibilityGovernor.Tier.CULLED);
        group.show(registration.displayHandle);
    }

    private void despawn(Registration registration, Player viewer) {
        DisplayEntityGroup group = registration.groups.remove(viewer.getUniqueId());
        if (group == null) {
            return;
        }
        try {
            group.close();
        } catch (RuntimeException failure) {
            DisplayEntityManager.retire(plugin, group);
            throw failure;
        }
    }

    private final class HitboxTransport implements DisplayEntityGroup.Transport {
        private final Registration registration;
        private final Player viewer;

        private HitboxTransport(Registration registration, Player viewer) {
            this.registration = registration;
            this.viewer = viewer;
        }

        @Override
        public boolean spawn(UUID handle) {
            if (!viewer.isOnline()) {
                return false;
            }
            List<PacketWrapper<?>> packets = new ArrayList<>(registration.entity.spawn());
            packets.add(DisplayEntity.interactionSize(registration.handle.entityId(), registration.target.width(),
                registration.target.height()));
            sink.send(viewer, packets);
            registration.spawned.put(viewer.getUniqueId(), viewer);
            return true;
        }

        @Override
        public void remove(List<UUID> handles, boolean delete) {
            if (viewer.isOnline()) {
                sink.send(viewer, List.of(registration.entity.remove()));
            }
            registration.spawned.remove(viewer.getUniqueId());
        }
    }

    boolean handleInteract(Player player, int entityId, WrapperPlayClientInteractEntity.InteractAction action,
                           boolean sneaking, Optional<Vector3f> hit) {
        Registration registration = byEntityId.get(entityId);
        if (registration == null || !registration.spawned.containsKey(player.getUniqueId())) {
            return false;
        }
        HoloClickTrigger trigger = trigger(action, sneaking);
        runner.run(player, () -> dispatch(registration, player, trigger));
        return true;
    }

    static HoloClickTrigger trigger(WrapperPlayClientInteractEntity.InteractAction action, boolean sneaking) {
        if (action == WrapperPlayClientInteractEntity.InteractAction.ATTACK) {
            return sneaking ? HoloClickTrigger.SHIFT_LEFT_CLICK : HoloClickTrigger.LEFT_CLICK;
        }
        return sneaking ? HoloClickTrigger.SHIFT_RIGHT_CLICK : HoloClickTrigger.RIGHT_CLICK;
    }

    private void dispatch(Registration registration, Player player, HoloClickTrigger trigger) {
        InteractionTarget target = registration.target;
        if (!target.live().getAsBoolean() || !player.isOnline() || !target.visibility().shown().test(player)) {
            return;
        }
        Location position = target.position().get();
        Location eye = player.getEyeLocation();
        if (position == null || position.getWorld() == null || eye.getWorld() != position.getWorld()) {
            return;
        }
        Vector center = position.toVector().add(new Vector(0.0D, target.height() / 2.0D, 0.0D));
        double distance = rayDistance(center, target, eye);
        if (distance < 0.0D || distance > INTERACTION_RANGE + RAY_SLACK) {
            return;
        }
        if (obstruction.obstructed(player, distance)) {
            return;
        }
        try {
            MenuAction.execute(target.actions(), target.context().apply(player, trigger));
        } catch (RuntimeException failure) {
            Gloss.logExceptionStackThrottled(false, "interaction-" + target.owner(), failure,
                "Interaction %s/%s threw while handling a click from %s.", target.owner(), target.id(), player.getName());
        }
    }

    private static double rayDistance(Vector center, InteractionTarget target, Location eye) {
        CollisionPlane plane = new CollisionPlane(center.clone(), target.width(), target.height());
        Vector origin = eye.toVector();
        Vector toViewer = origin.clone().subtract(center);
        if (toViewer.lengthSquared() < 1.0E-12D) {
            return 0.0D;
        }
        Vector normal = toViewer.normalize();
        Vector referenceUp = Math.abs(normal.dot(new Vector(0.0D, 1.0D, 0.0D))) > 0.999D
            ? new Vector(0.0D, 0.0D, 1.0D)
            : new Vector(0.0D, 1.0D, 0.0D);
        Vector right = normal.clone().crossProduct(referenceUp);
        if (right.lengthSquared() < 1.0E-12D) {
            return -1.0D;
        }
        right.normalize();
        plane.orient(right.clone().crossProduct(normal), right);
        return plane.intersectionDistance(origin, eye.getDirection()).orElse(-1.0D);
    }

    private static ViewerSource viewersOf(Gloss plugin) {
        return (anchor, range) -> {
            Set<Player> found = new LinkedHashSet<>();
            HologramService holograms = plugin.holograms();
            if (holograms != null) {
                holograms.forEachNearbyViewer(anchor, range * range, found::add);
            }
            return List.copyOf(found);
        };
    }

    private static void sendPackets(Player viewer, List<PacketWrapper<?>> packets) {
        if (!PacketUtils.sendChecked(viewer, packets) && viewer.isOnline()) {
            throw new IllegalStateException("Interaction channel is unavailable for " + viewer.getUniqueId());
        }
    }

    private static void runOnRegion(Gloss plugin, Player player, Runnable action) {
        if (!plugin.scheduler().runEntity(player, action)) {
            Gloss.warnThrottled("interaction-dispatch", "Interaction click for %s could not reach the player's region thread.",
                player.getName());
        }
    }

    private static boolean blockObstructed(Gloss plugin, Player player, double distance) {
        Location eye = player.getEyeLocation();
        World world = eye.getWorld();
        if (world == null) {
            return true;
        }
        if (FoliaScheduler.isFolia(plugin)) {
            return false;
        }
        RayTraceResult hit = world.rayTraceBlocks(eye, eye.getDirection(), distance, FluidCollisionMode.NEVER, true);
        if (hit == null) {
            return false;
        }
        double hitDistanceSquared = hit.getHitPosition().distanceSquared(eye.toVector());
        return hitDistanceSquared + 1.0E-6D < distance * distance;
    }
}
