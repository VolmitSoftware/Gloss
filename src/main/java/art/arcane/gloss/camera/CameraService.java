package art.arcane.gloss.camera;

import art.arcane.gloss.Gloss;
import art.arcane.gloss.GlossConfig;
import art.arcane.gloss.api.GlossCameraRideEvent;
import art.arcane.gloss.service.GlossService;
import art.arcane.gloss.state.PlayerSections;
import art.arcane.gloss.util.common.Teleports;
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
import org.bukkit.event.player.PlayerTeleportEvent;
import org.bukkit.event.player.PlayerToggleSneakEvent;

import java.util.List;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.logging.Level;

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
    private final ConcurrentMap<UUID, CameraRide> rides = new ConcurrentHashMap<>();
    private int driverTaskId = -1;

    public CameraService(Gloss plugin, PlayerSections sections) {
        this.plugin = Objects.requireNonNull(plugin, "plugin");
        this.sections = Objects.requireNonNull(sections, "sections");
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
        if (!enabled() || path.isEmpty() || rides.containsKey(player.getUniqueId())) {
            return false;
        }
        Spline spline = new Spline(path);
        long maxTicks = (long) plugin.cfg().modules().camera().maxRideSeconds() * 20L;
        GlossCameraRideEvent event = new GlossCameraRideEvent(player,
            (int) Math.min(Integer.MAX_VALUE, Math.min(spline.totalTicks(), maxTicks)));
        plugin.getServer().getPluginManager().callEvent(event);
        if (event.isCancelled()) {
            return false;
        }
        CameraRide ride = new CameraRide(player, spline, options.skippable(), maxTicks);
        Entity carrier = spawnCarrier(player, spline);
        if (carrier == null) {
            return false;
        }
        journal.write(player, ride.savedLocation(), ride.savedGameMode());
        rides.put(player.getUniqueId(), ride);
        ride.start(carrier);
        letterbox(player, options.letterbox(), true);
        return true;
    }

    public void stop(Player player, EndReason reason) {
        end(player.getUniqueId(), reason);
    }

    /** Puts a player back where a crash mid-ride left them, then forgets the journal. */
    public void restoreJournalled(Player player) {
        journal.read(player.getUniqueId()).ifPresent(entry -> {
            journal.clear(player.getUniqueId());
            World world = Bukkit.getWorld(entry.world());
            if (world == null) {
                return;
            }
            player.setSpectatorTarget(null);
            player.setGameMode(gameMode(entry.gameMode()));
            Teleports.teleportAsync(player, new Location(world, entry.x(), entry.y(), entry.z(),
                entry.yaw(), entry.pitch()), PlayerTeleportEvent.TeleportCause.PLUGIN);
        });
    }

    @EventHandler
    public void onJoin(PlayerJoinEvent event) {
        restoreJournalled(event.getPlayer());
    }

    /** The last world-lane quit handler, so the cached state file is dropped after every writer ran. */
    @EventHandler
    public void onQuit(PlayerQuitEvent event) {
        end(event.getPlayer().getUniqueId(), EndReason.QUIT);
        sections.evict(event.getPlayer().getUniqueId());
    }

    @EventHandler
    public void onDeath(PlayerDeathEvent event) {
        end(event.getEntity().getUniqueId(), EndReason.DEATH);
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
        for (UUID riderId : List.copyOf(rides.keySet())) {
            CameraRide ride = rides.get(riderId);
            if (ride == null) {
                continue;
            }
            if (!ride.tick()) {
                end(riderId, ride.durationTicks() >= (long) plugin.cfg().modules().camera()
                    .maxRideSeconds() * 20L ? EndReason.TIMEOUT : EndReason.END);
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
        if (reason != EndReason.QUIT) {
            journal.clear(riderId);
        }
        letterbox(ride.player(), true, false);
        Runnable restore = () -> {
            try {
                ride.end();
            } catch (RuntimeException failure) {
                Gloss.logExceptionStack(false, failure,
                    "Camera ride for %s could not be restored after %s.", ride.player().getName(), reason);
            }
        };
        if (!FoliaScheduler.runEntity(plugin, ride.player(), restore, 0, restore)) {
            restore.run();
        }
    }

    private Entity spawnCarrier(Player player, Spline spline) {
        Spline.Pose start = spline.at(0L);
        World world = player.getWorld();
        Location at = new Location(world, start.x(), start.y(), start.z(), start.yaw(), start.pitch());
        try {
            return world.spawn(at, ArmorStand.class, CameraRide::prepare);
        } catch (RuntimeException failure) {
            Gloss.log(Level.WARNING, "Camera ride for %s could not spawn its carrier: %s",
                player.getName(), failure.getMessage());
            return null;
        }
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
}
