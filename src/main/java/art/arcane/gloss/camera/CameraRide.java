package art.arcane.gloss.camera;

import org.bukkit.GameMode;
import org.bukkit.Location;
import org.bukkit.entity.ArmorStand;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Player;

import java.util.Objects;

/**
 * One player's ride: the state that has to come back, the carrier the client is watching, and the
 * cursor into the spline. Restoring is idempotent, because several exit paths can fire for the
 * same ride in the same tick.
 */
public final class CameraRide {
    private final Player player;
    private final Spline spline;
    private final boolean skippable;
    private final Location origin;
    private final GameMode gameMode;
    private final long maxTicks;
    private Entity carrier;
    private long elapsedTicks;
    private boolean ended;
    private boolean moving;
    private boolean started;
    private boolean restoreOnEnd;
    private boolean finalized;

    CameraRide(Player player, Spline spline, boolean skippable, long maxTicks) {
        this.player = Objects.requireNonNull(player, "player");
        this.spline = Objects.requireNonNull(spline, "spline");
        this.skippable = skippable;
        this.maxTicks = maxTicks;
        this.origin = player.getLocation().clone();
        this.gameMode = player.getGameMode();
    }

    public Player player() {
        return player;
    }

    public Location savedLocation() {
        return origin.clone();
    }

    public GameMode savedGameMode() {
        return gameMode;
    }

    public boolean skippable() {
        return skippable;
    }

    public long durationTicks() {
        return Math.min(spline.totalTicks(), maxTicks);
    }

    synchronized void start(Entity carrier) {
        this.carrier = carrier;
        moving = true;
    }

    /** @return false once the ride has run its course and should be ended */
    synchronized boolean tick() {
        if (ended || !started) {
            return !ended;
        }
        elapsedTicks++;
        return elapsedTicks <= durationTicks();
    }

    synchronized Location beginMove() {
        if (ended || !started || moving) {
            return null;
        }
        moving = true;
        return position(elapsedTicks);
    }

    Location position(long tick) {
        Spline.Pose pose = spline.at(tick);
        return new Location(origin.getWorld(), pose.x(), pose.y(), pose.z(), pose.yaw(), pose.pitch());
    }

    synchronized void moved() {
        moving = false;
    }

    synchronized void attached() {
        started = true;
        moving = false;
    }

    synchronized Entity carrier() {
        return carrier;
    }

    synchronized void end(boolean restore) {
        if (ended) {
            return;
        }
        ended = true;
        restoreOnEnd = restore;
    }

    synchronized boolean finalizeEnd() {
        if (!ended || moving || finalized) {
            return false;
        }
        finalized = true;
        return true;
    }

    synchronized boolean restoreOnEnd() {
        return restoreOnEnd;
    }

    synchronized boolean ended() {
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
