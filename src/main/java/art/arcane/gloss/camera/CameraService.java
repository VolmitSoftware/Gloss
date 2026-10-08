package art.arcane.gloss.camera;

import art.arcane.gloss.Gloss;
import art.arcane.gloss.GlossConfig;
import art.arcane.gloss.api.GlossCameraRideEvent;
import art.arcane.gloss.service.GlossService;
import art.arcane.gloss.state.PlayerSections;
import art.arcane.volmlib.util.scheduling.FoliaScheduler;
import org.bukkit.Bukkit;
import org.bukkit.GameMode;
import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.entity.ArmorStand;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.HandlerList;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.PlayerDeathEvent;
import org.bukkit.event.player.PlayerChangedWorldEvent;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.event.player.PlayerRespawnEvent;
import org.bukkit.event.player.PlayerTeleportEvent;
import org.bukkit.event.player.PlayerToggleSneakEvent;

import java.util.List;
import java.util.Objects;
import java.util.UUID;
import java.lang.reflect.Method;
import java.lang.reflect.InvocationTargetException;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Spectator camera rides along a spline. The player watches a real, invisible carrier entity
 * rather than the camera packet, because that is what the client interpolates and what other
 * plugins expect; every way a ride can end puts the player back where they started.
 */
public final class CameraService implements GlossService, Listener {
    public static final String NAME = "camera";
    static final int DRIVE_INTERVAL_TICKS = 1;

    public enum EndReason {
        END,
        SKIP,
        QUIT,
        DEATH,
        WORLD_CHANGE,
        DISABLE,
        TIMEOUT
    }

    /**
     * @param skippable whether sneaking ends the ride early
     * @param letterbox whether black bars are asked for; ignored when no lane provides titles
     */
    public record Options(boolean skippable, boolean letterbox) {
    }

    private final Gloss plugin;
    private final PlayerSections sections;
    private final CameraJournal journal;
    private final Transport transport;
    private final ConcurrentMap<UUID, CameraRide> rides = new ConcurrentHashMap<>();
    private final ConcurrentMap<UUID, UUID> restoring = new ConcurrentHashMap<>();
    private final ConcurrentMap<Entity, AtomicBoolean> retiringCarriers = new ConcurrentHashMap<>();
    private int driverTaskId = -1;

    public CameraService(Gloss plugin, PlayerSections sections) {
        this(new Dependencies(plugin, sections, new EntityTransport(plugin)));
    }

    CameraService(Dependencies dependencies) {
        this.plugin = Objects.requireNonNull(dependencies.plugin(), "plugin");
        this.sections = Objects.requireNonNull(dependencies.sections(), "sections");
        this.transport = Objects.requireNonNull(dependencies.transport(), "transport");
        this.journal = new CameraJournal(this.sections);
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
        for (UUID riderId : List.copyOf(rides.keySet())) {
            end(riderId, EndReason.DISABLE);
        }
    }

    @Override
    public boolean reloadOnConfigChange(GlossConfig previous, GlossConfig next) {
        return !previous.modules().camera().equals(next.modules().camera());
    }

    public boolean enabled() {
        return plugin.cfg().modules().camera().enabled();
    }

    public boolean riding(UUID playerId) {
        return rides.containsKey(playerId);
    }

    /**
     * Starts a ride. Fires {@link GlossCameraRideEvent} first, so another plugin can refuse it.
     *
     * @return false when the ride was refused, the player is already riding, or the carrier could
     *     not be spawned
     */
    public boolean ride(Player player, List<Spline.Node> path, Options options) {
        if (!enabled() || path.isEmpty() || riding(player.getUniqueId()) || restoring.containsKey(player.getUniqueId())) {
            return false;
        }
        Spline spline = new Spline(path);
        AtomicBoolean accepted = new AtomicBoolean(true);
        return transport.execute(player, () -> accepted.set(begin(player, spline, options)),
            () -> accepted.set(false)) && accepted.get();
    }

    private boolean begin(Player player, Spline spline, Options options) {
        if (!enabled() || riding(player.getUniqueId()) || restoring.containsKey(player.getUniqueId())
            || journal.read(player.getUniqueId()).isPresent()) {
            return false;
        }
        long maxTicks = (long) plugin.cfg().modules().camera().maxRideSeconds() * 20L;
        GlossCameraRideEvent event = new GlossCameraRideEvent(player,
            (int) Math.min(Integer.MAX_VALUE, Math.min(spline.totalTicks(), maxTicks)));
        plugin.getServer().getPluginManager().callEvent(event);
        if (event.isCancelled()) {
            return false;
        }
        CameraRide ride = new CameraRide(player, spline, options.skippable(), maxTicks);
        try {
            journal.write(player, ride.savedLocation(), ride.savedGameMode());
        } catch (RuntimeException failure) {
            report(player.getUniqueId(), "saving recovery state", failure);
            return false;
        }
        Entity carrier = spawnCarrier(player);
        if (carrier == null) {
            clearJournal(player.getUniqueId());
            return false;
        }
        rides.put(player.getUniqueId(), ride);
        ride.start(carrier);
        try {
            player.setGameMode(GameMode.SPECTATOR);
            move(ride, ride.position(0L), () -> attach(ride, options));
        } catch (RuntimeException failure) {
            report(player.getUniqueId(), "starting the ride", failure);
            ride.moved();
            abort(ride);
            return false;
        }
        return true;
    }

    public void stop(Player player, EndReason reason) {
        end(player.getUniqueId(), reason);
    }

    /** Puts a player back where a crash mid-ride left them, then forgets the journal. */
    public void restoreJournalled(Player player) {
        UUID id = player.getUniqueId();
        UUID restoration = UUID.randomUUID();
        if (riding(id) || restoring.putIfAbsent(id, restoration) != null) {
            return;
        }
        if (!transport.execute(player, () -> restore(player, restoration), () -> restoring.remove(id, restoration))) {
            restoring.remove(id, restoration);
        }
    }

    @EventHandler
    public void onJoin(PlayerJoinEvent event) {
        restoreJournalled(event.getPlayer());
    }

    /** The last world-lane quit handler, so the cached state file is dropped after every writer ran. */
    @EventHandler
    public void onQuit(PlayerQuitEvent event) {
        end(event.getPlayer().getUniqueId(), EndReason.QUIT);
        restoring.remove(event.getPlayer().getUniqueId());
        sections.evict(event.getPlayer().getUniqueId());
    }

    @EventHandler
    public void onDeath(PlayerDeathEvent event) {
        end(event.getEntity().getUniqueId(), EndReason.DEATH);
    }

    @EventHandler
    public void onRespawn(PlayerRespawnEvent event) {
        Player player = event.getPlayer();
        FoliaScheduler.runEntity(plugin, player, () -> restoreJournalled(player), 1L);
    }

    @EventHandler
    public void onWorldChange(PlayerChangedWorldEvent event) {
        end(event.getPlayer().getUniqueId(), EndReason.WORLD_CHANGE);
    }

    @EventHandler
    public void onSneak(PlayerToggleSneakEvent event) {
        CameraRide ride = rides.get(event.getPlayer().getUniqueId());
        if (ride != null && event.isSneaking() && ride.skippable()) {
            end(event.getPlayer().getUniqueId(), EndReason.SKIP);
        }
    }

    /** One driver pass: advance every ride and end the ones that ran out. */
    void tick() {
        retiringCarriers.forEach(this::scheduleCarrierRemoval);
        for (UUID riderId : List.copyOf(rides.keySet())) {
            CameraRide ride = rides.get(riderId);
            if (ride == null) {
                continue;
            }
            if (!ride.tick()) {
                end(riderId, ride.durationTicks() >= (long) plugin.cfg().modules().camera()
                    .maxRideSeconds() * 20L ? EndReason.TIMEOUT : EndReason.END);
                continue;
            }
            Location next = ride.beginMove();
            if (next != null) {
                move(ride, next, ride::moved);
            }
        }
    }

    /**
     * A quitting rider keeps their journal. The restore teleports asynchronously and that never
     * lands on a player who is already leaving, so the journal is what puts them back on the next
     * join; clearing it here left them wherever the ride had reached, permanently.
     */
    private void end(UUID riderId, EndReason reason) {
        CameraRide ride = rides.remove(riderId);
        if (ride == null) {
            return;
        }
        ride.end(reason != EndReason.QUIT);
        finishEnd(ride);
    }

    private Entity spawnCarrier(Player player) {
        Location at = player.getLocation();
        try {
            return at.getWorld().spawn(at, ArmorStand.class, CameraRide::prepare);
        } catch (RuntimeException failure) {
            report(player.getUniqueId(), "spawning the carrier", failure);
            return null;
        }
    }

    private void move(CameraRide ride, Location destination, Runnable success) {
        Entity carrier = ride.carrier();
        Runnable refused = () -> {
            ride.moved();
            abort(ride);
        };
        if (!transport.execute(carrier, () -> {
            if (ride.ended()) {
                ride.moved();
                finishEnd(ride);
                return;
            }
            teleport(carrier, destination).whenComplete((moved, failure) -> {
                if (failure != null) {
                    report(ride.player().getUniqueId(), "moving the carrier", failure);
                }
                if (ride.ended()) {
                    ride.moved();
                    finishEnd(ride);
                } else if (failure != null || !Boolean.TRUE.equals(moved)) {
                    refused.run();
                } else {
                    try {
                        success.run();
                        if (ride.ended()) {
                            finishEnd(ride);
                        }
                    } catch (RuntimeException startFailure) {
                        report(ride.player().getUniqueId(), "continuing the ride", startFailure);
                        refused.run();
                    }
                }
            });
        }, refused)) {
            refused.run();
        }
    }

    private void attach(CameraRide ride, Options options) {
        Player player = ride.player();
        Runnable refused = () -> {
            ride.moved();
            abort(ride);
        };
        if (!transport.execute(player, () -> {
            if (ride.ended()) {
                ride.moved();
                finishEnd(ride);
                return;
            }
            teleport(player, ride.position(0L)).whenComplete((moved, failure) -> {
                if (failure != null) {
                    report(player.getUniqueId(), "positioning the rider", failure);
                }
                if (ride.ended() || failure != null || !Boolean.TRUE.equals(moved)) {
                    refused.run();
                    return;
                }
                if (!transport.execute(player, () -> {
                    if (ride.ended()) {
                        ride.moved();
                        finishEnd(ride);
                        return;
                    }
                    try {
                        player.setSpectatorTarget(ride.carrier());
                        letterbox(player, options.letterbox(), true);
                        ride.attached();
                        if (ride.ended()) {
                            finishEnd(ride);
                        }
                    } catch (RuntimeException attachFailure) {
                        report(player.getUniqueId(), "attaching the rider", attachFailure);
                        refused.run();
                    }
                }, refused)) {
                    refused.run();
                }
            });
        }, refused)) {
            refused.run();
        }
    }

    private CompletableFuture<Boolean> teleport(Entity entity, Location destination) {
        try {
            return transport.teleport(entity, destination);
        } catch (RuntimeException failure) {
            return CompletableFuture.failedFuture(failure);
        }
    }

    private void abort(CameraRide ride) {
        if (rides.remove(ride.player().getUniqueId(), ride)) {
            ride.end(true);
        }
        finishEnd(ride);
    }

    private void finishEnd(CameraRide ride) {
        if (!ride.finalizeEnd()) {
            return;
        }
        removeCarrier(ride);
        if (ride.restoreOnEnd()) {
            restoreJournalled(ride.player());
        }
    }

    private void removeCarrier(CameraRide ride) {
        Entity carrier = ride.carrier();
        if (carrier != null) {
            scheduleCarrierRemoval(carrier, retiringCarriers.computeIfAbsent(carrier, ignored -> new AtomicBoolean()));
        }
    }

    private void scheduleCarrierRemoval(Entity carrier, AtomicBoolean scheduled) {
        if (!scheduled.compareAndSet(false, true)) {
            return;
        }
        Runnable retired = () -> retiringCarriers.remove(carrier, scheduled);
        if (!transport.execute(carrier, () -> {
            try {
                carrier.remove();
                retired.run();
            } catch (RuntimeException failure) {
                Gloss.logExceptionStackThrottled(false, "camera-carrier-removal", failure,
                    "Camera carrier %s could not be removed.", carrier.getUniqueId());
            } finally {
                scheduled.set(false);
            }
        }, retired)) {
            scheduled.set(false);
        }
    }

    private void restore(Player player, UUID restoration) {
        UUID id = player.getUniqueId();
        if (!restoration.equals(restoring.get(id))) {
            return;
        }
        CameraJournal.Entry entry = journal.read(id).orElse(null);
        World world = entry == null ? null : Bukkit.getWorld(entry.world());
        if (world == null || !player.isOnline()) {
            restoring.remove(id, restoration);
            return;
        }
        try {
            letterbox(player, true, false);
            player.setSpectatorTarget(null);
            player.setGameMode(gameMode(entry.gameMode()));
            Location destination = new Location(world, entry.x(), entry.y(), entry.z(), entry.yaw(), entry.pitch());
            teleport(player, destination).whenComplete((moved, failure) -> {
                if (failure != null) {
                    report(id, "restoring the rider", failure);
                }
                if (failure != null || !Boolean.TRUE.equals(moved)) {
                    restoring.remove(id, restoration);
                    return;
                }
                Runnable retired = () -> restoring.remove(id, restoration);
                if (!transport.execute(player, () -> finishRestore(player, entry, restoration), retired)) {
                    retired.run();
                }
            });
        } catch (RuntimeException failure) {
            restoring.remove(id, restoration);
            report(id, "restoring the rider", failure);
        }
    }

    private void finishRestore(Player player, CameraJournal.Entry entry, UUID restoration) {
        UUID id = player.getUniqueId();
        try {
            if (!player.isOnline() || !restoration.equals(restoring.get(id))) {
                return;
            }
            if (entry.allowFlight() != null) {
                player.setAllowFlight(entry.allowFlight());
            }
            if (entry.flying() != null) {
                player.setFlying(entry.flying());
            }
            player.setVelocity(entry.velocity());
            journal.clear(id);
        } catch (RuntimeException failure) {
            report(id, "finishing rider restoration", failure);
        } finally {
            restoring.remove(id, restoration);
        }
    }

    private void clearJournal(UUID id) {
        try {
            journal.clear(id);
        } catch (RuntimeException failure) {
            report(id, "clearing recovery state", failure);
        }
    }

    private static void report(UUID id, String operation, Throwable failure) {
        Gloss.logExceptionStack(false, failure, "Camera ride for %s failed while %s.", id, operation);
    }

    /** The bars are only asked for when some lane published a title provider. */
    private void letterbox(Player player, boolean wanted, boolean visible) {
        if (!wanted) {
            return;
        }
        for (GlossService service : plugin.laneServices()) {
            if (service instanceof TitleProvider provider) {
                provider.letterbox(player, visible);
                return;
            }
        }
    }

    private static GameMode gameMode(String name) {
        try {
            return GameMode.valueOf(name);
        } catch (IllegalArgumentException unknown) {
            return GameMode.SURVIVAL;
        }
    }

    record Dependencies(Gloss plugin, PlayerSections sections, Transport transport) {
    }

    interface Transport {
        boolean execute(Entity entity, Runnable action, Runnable retired);

        CompletableFuture<Boolean> teleport(Entity entity, Location destination);
    }

    private static final class EntityTransport implements Transport {
        private static final Method TELEPORT_ASYNC = teleportMethod();
        private final Gloss plugin;

        private EntityTransport(Gloss plugin) {
            this.plugin = plugin;
        }

        @Override
        public boolean execute(Entity entity, Runnable action, Runnable retired) {
            if (FoliaScheduler.isOwnedByCurrentRegion(entity)) {
                if (entity.isValid()) {
                    action.run();
                } else {
                    retired.run();
                }
                return true;
            }
            return FoliaScheduler.runEntity(plugin, entity, action, 0L, retired);
        }

        @Override
        @SuppressWarnings("unchecked")
        public CompletableFuture<Boolean> teleport(Entity entity, Location destination) {
            if (TELEPORT_ASYNC == null) {
                return CompletableFuture.completedFuture(entity.teleport(destination, PlayerTeleportEvent.TeleportCause.PLUGIN));
            }
            try {
                return (CompletableFuture<Boolean>) TELEPORT_ASYNC.invoke(entity, destination,
                    PlayerTeleportEvent.TeleportCause.PLUGIN);
            } catch (InvocationTargetException failure) {
                return CompletableFuture.failedFuture(failure.getCause());
            } catch (ReflectiveOperationException | RuntimeException failure) {
                return CompletableFuture.failedFuture(failure);
            }
        }

        private static Method teleportMethod() {
            try {
                return Entity.class.getMethod("teleportAsync", Location.class, PlayerTeleportEvent.TeleportCause.class);
            } catch (NoSuchMethodException absent) {
                return null;
            }
        }
    }
}
