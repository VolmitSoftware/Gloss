package art.arcane.gloss.behavior;

import org.bukkit.entity.Player;

import java.util.Collection;
import java.util.List;
import java.util.function.Supplier;

/**
 * One tick counter drives every {@code interval} entry: a global entry runs once on its tick on
 * the calling (global) thread, a player entry runs once per online player on that player's region.
 */
public final class IntervalScheduler {
    @FunctionalInterface
    public interface PlayerDispatch {
        void run(Player player, Runnable task);
    }

    @FunctionalInterface
    public interface EntryRunner {
        void run(BehaviorSubscriptions.Subscription subscription, TriggerEvent event);
    }

    private final Supplier<Collection<? extends Player>> online;
    private final PlayerDispatch dispatch;
    private final EntryRunner runner;
    private volatile List<BehaviorSubscriptions.Subscription> entries = List.of();
    private long tick;

    public IntervalScheduler(Supplier<Collection<? extends Player>> online, PlayerDispatch dispatch, EntryRunner runner) {
        this.online = online;
        this.dispatch = dispatch;
        this.runner = runner;
    }

    public void rebuild(BehaviorSubscriptions subscriptions) {
        entries = subscriptions.subscribed(BehaviorTrigger.INTERVAL);
    }

    public boolean hasEntries() {
        return !entries.isEmpty();
    }

    public void tick() {
        tick++;
        List<BehaviorSubscriptions.Subscription> current = entries;
        for (BehaviorSubscriptions.Subscription subscription : current) {
            BehaviorEntry entry = subscription.entry().entry();
            if (tick % entry.everyTicks() != 0) {
                continue;
            }
            if (entry.globalScope()) {
                runner.run(subscription, TriggerEvent.none());
                continue;
            }
            for (Player player : online.get()) {
                dispatch.run(player, () -> runner.run(subscription, TriggerEvent.viewer(player)));
            }
        }
    }
}
