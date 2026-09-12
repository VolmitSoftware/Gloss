package art.arcane.gloss.behavior;

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;

/** Pending delayed continuations per player, bounded by {@code maxTimersPerPlayer}. */
public final class PendingTimers {
    private static final PendingTimers GLOBAL = new PendingTimers();

    private final Map<UUID, AtomicInteger> pending = new ConcurrentHashMap<>();

    public static PendingTimers global() {
        return GLOBAL;
    }

    /** @return false when the player already has {@code cap} timers pending (a cap of 0 is unlimited) */
    public boolean acquire(UUID player, int cap) {
        AtomicInteger counter = pending.computeIfAbsent(player, id -> new AtomicInteger());
        while (true) {
            int current = counter.get();
            if (cap > 0 && current >= cap) {
                return false;
            }
            if (counter.compareAndSet(current, current + 1)) {
                return true;
            }
        }
    }

    public void release(UUID player) {
        AtomicInteger counter = pending.get(player);
        if (counter != null && counter.decrementAndGet() <= 0) {
            pending.remove(player, counter);
        }
    }

    public int pending(UUID player) {
        AtomicInteger counter = pending.get(player);
        return counter == null ? 0 : Math.max(0, counter.get());
    }

    public void forget(UUID player) {
        pending.remove(player);
    }

    public void clear() {
        pending.clear();
    }
}
