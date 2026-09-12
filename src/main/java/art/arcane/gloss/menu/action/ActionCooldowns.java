package art.arcane.gloss.menu.action;

import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.LongSupplier;

/**
 * Per-player action cooldown ledger keyed by the action's position in its document. Entries are
 * ticks-as-millis deadlines; an expired entry is overwritten on the next successful claim and the
 * whole map is swept opportunistically so a busy server never accumulates dead keys.
 */
public final class ActionCooldowns {
    private static final long TICK_MILLIS = 50L;
    private static final int SWEEP_EVERY = 1024;
    private static final ActionCooldowns GLOBAL = new ActionCooldowns(System::currentTimeMillis);

    private final LongSupplier clock;
    private final Map<Key, Long> deadlines = new ConcurrentHashMap<>();
    private int claims;

    ActionCooldowns(LongSupplier clock) {
        this.clock = Objects.requireNonNull(clock, "clock");
    }

    public static ActionCooldowns global() {
        return GLOBAL;
    }

    /** @return true when the window was free and is now claimed for {@code ticks} */
    public boolean claim(UUID playerId, String actionKey, int ticks) {
        Key key = new Key(playerId, actionKey);
        long now = clock.getAsLong();
        Long deadline = deadlines.get(key);
        if (deadline != null && deadline > now) {
            return false;
        }
        deadlines.put(key, now + ticks * TICK_MILLIS);
        if (++claims % SWEEP_EVERY == 0) {
            deadlines.entrySet().removeIf(entry -> entry.getValue() <= now);
        }
        return true;
    }

    public void forget(UUID playerId) {
        deadlines.keySet().removeIf(key -> key.playerId().equals(playerId));
    }

    public void clear() {
        deadlines.clear();
    }

    private record Key(UUID playerId, String actionKey) {
    }
}
