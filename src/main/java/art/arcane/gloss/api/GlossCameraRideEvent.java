package art.arcane.gloss.api;

import org.bukkit.entity.Player;
import org.bukkit.event.Cancellable;
import org.bukkit.event.Event;
import org.bukkit.event.HandlerList;

import java.util.Objects;

/**
 * Fired before a camera ride puts a player into spectator mode. Gamemode managers, anti-cheats and
 * combat-tag plugins cancel this to refuse the ride; nothing about the player has changed yet when
 * it fires.
 */
public final class GlossCameraRideEvent extends Event implements Cancellable {
    private static final HandlerList HANDLERS = new HandlerList();

    private final Player player;
    private final int durationTicks;
    private boolean cancelled;

    public GlossCameraRideEvent(Player player, int durationTicks) {
        this.player = Objects.requireNonNull(player, "player");
        this.durationTicks = Math.max(0, durationTicks);
    }

    public static HandlerList getHandlerList() {
        return HANDLERS;
    }

    public Player player() {
        return player;
    }

    public int durationTicks() {
        return durationTicks;
    }

    @Override
    public boolean isCancelled() {
        return cancelled;
    }

    @Override
    public void setCancelled(boolean cancel) {
        this.cancelled = cancel;
    }

    @Override
    public HandlerList getHandlers() {
        return HANDLERS;
    }
}
