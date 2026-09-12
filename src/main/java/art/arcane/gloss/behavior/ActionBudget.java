package art.arcane.gloss.behavior;

import java.util.concurrent.atomic.AtomicInteger;

/**
 * Server-wide token bucket for resumed action runs: {@code maxActionsPerTick} tokens per tick,
 * refilled by the behavior service's tick task. Unconfigured (capacity 0) it never refuses, so a
 * click-driven list on a server without behaviors is unaffected.
 */
public final class ActionBudget {
    private static final ActionBudget GLOBAL = new ActionBudget();

    private final AtomicInteger tokens = new AtomicInteger();
    private volatile int capacity;

    public static ActionBudget global() {
        return GLOBAL;
    }

    public void configure(int perTick) {
        capacity = Math.max(0, perTick);
        tokens.set(capacity);
    }

    public void refill() {
        tokens.set(capacity);
    }

    public boolean take() {
        if (capacity <= 0) {
            return true;
        }
        while (true) {
            int current = tokens.get();
            if (current <= 0) {
                return false;
            }
            if (tokens.compareAndSet(current, current - 1)) {
                return true;
            }
        }
    }
}
