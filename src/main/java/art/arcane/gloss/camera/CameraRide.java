package art.arcane.gloss.camera;

import art.arcane.gloss.util.common.Teleports;
import org.bukkit.GameMode;
import org.bukkit.Location;
import org.bukkit.entity.ArmorStand;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Player;
import org.bukkit.event.player.PlayerTeleportEvent;
import org.bukkit.util.Vector;

import java.util.Objects;

/**
 * One player's ride: the state that has to come back, the carrier the client is watching, and the
 * cursor into the spline. Restoring is idempotent, because several exit paths can fire for the
 * same ride in the same tick.
 */
public final class CameraRide {
    private record Saved(Location location, GameMode gameMode, boolean allowFlight, boolean flying,
                         Vector velocity) {
    }

    private final Player player;
    private final Spline spline;
    private final boolean skippable;
    private final Saved saved;
    private final long maxTicks;
    private Entity carrier;
    private long elapsedTicks;
    private boolean ended;

    CameraRide(Player player, Spline spline, boolean skippable, long maxTicks) {
        this.player = Objects.requireNonNull(player, "player");
        this.spline = Objects.requireNonNull(spline, "spline");
        this.skippable = skippable;
        this.maxTicks = maxTicks;
        this.saved = new Saved(player.getLocation().clone(), player.getGameMode(),
            player.getAllowFlight(), player.isFlying(), player.getVelocity().clone());
    }

    public Player player() {
        return player;
    }

    public Location savedLocation() {
        return saved.location().clone();
    }

    public GameMode savedGameMode() {
        return saved.gameMode();
    }

    public boolean skippable() {
        return skippable;
    }

    public long durationTicks() {
        return Math.min(spline.totalTicks(), maxTicks);
    }

    void start(Entity carrier) {
        this.carrier = carrier;
        player.setGameMode(GameMode.SPECTATOR);
        player.setSpectatorTarget(carrier);
    }

    /** @return false once the ride has run its course and should be ended */
    boolean tick() {
        if (ended) {
            return false;
        }
        elapsedTicks++;
        if (elapsedTicks > durationTicks()) {
            return false;
        }
        if (carrier == null || !carrier.isValid()) {
            return false;
        }
        Spline.Pose pose = spline.at(elapsedTicks);
        Location at = new Location(saved.location().getWorld(), pose.x(), pose.y(), pose.z(),
            pose.yaw(), pose.pitch());
        carrier.teleport(at);
        return true;
    }

    void end() {
        if (ended) {
            return;
        }
        ended = true;
        if (carrier != null) {
            carrier.remove();
            carrier = null;
        }
        player.setSpectatorTarget(null);
        player.setGameMode(saved.gameMode());
        Teleports.teleportAsync(player, saved.location(), PlayerTeleportEvent.TeleportCause.PLUGIN);
        player.setAllowFlight(saved.allowFlight());
        player.setFlying(saved.flying());
        player.setVelocity(saved.velocity());
    }

    boolean ended() {
        return ended;
    }

    static void prepare(ArmorStand carrier) {
        carrier.setInvisible(true);
        carrier.setMarker(true);
        carrier.setSilent(true);
        carrier.setGravity(false);
        carrier.setInvulnerable(true);
        carrier.setPersistent(false);
        carrier.setCustomNameVisible(false);
    }
}
