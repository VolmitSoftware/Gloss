package art.arcane.gloss.behavior;

import art.arcane.gloss.Gloss;
import art.arcane.volmlib.util.scheduling.SchedulerUtils;

import java.util.List;
import java.util.Map;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;

/** Shared admission for delayed continuations, including runs without a player. */
public final class PendingTimers {
    private static final PendingTimers GLOBAL = new PendingTimers();

    private final Map<UUID, Counter> pending = new HashMap<>();
    private Accounting accounting = new Accounting();

    public static PendingTimers global() {
        return GLOBAL;
    }

    /** @return null when the player already has {@code cap} timers pending (a cap of 0 is unlimited) */
    public synchronized Lease acquire(UUID player, int cap) {
        Objects.requireNonNull(player, "player");
        return acquire(player, new Limits(cap, 0, 0));
    }

    public synchronized Lease acquire(UUID player, Limits limits) {
        Objects.requireNonNull(limits, "limits");
        Counter counter = pending.get(player);
        int subjectCap = player == null ? limits.withoutPlayer() : limits.perPlayer();
        int subjectCount = player == null ? accounting.withoutPlayer : counter == null ? 0 : counter.leases.size();
        if ((limits.global() > 0 && accounting.leases.size() >= limits.global())
            || (subjectCap > 0 && subjectCount >= subjectCap)) {
            return null;
        }
        if (counter == null) {
            counter = new Counter();
            pending.put(player, counter);
        }
        Lease lease = new Lease(player, counter);
        counter.leases.add(lease);
        accounting.leases.add(lease);
        if (player == null) {
            accounting.withoutPlayer++;
        }
        return lease;
    }

    public synchronized int total() {
        return accounting.leases.size();
    }

    public synchronized int pending(UUID player) {
        Counter counter = pending.get(player);
        return counter == null ? 0 : counter.leases.size();
    }

    public void forget(UUID player) {
        List<Lease> retiring;
        synchronized (this) {
            Counter counter = pending.remove(Objects.requireNonNull(player, "player"));
            retiring = counter == null ? List.of() : List.copyOf(counter.leases);
        }
        for (Lease lease : retiring) {
            lease.cancel();
        }
    }

    public void clear() {
        List<Lease> retiring;
        synchronized (this) {
            pending.clear();
            retiring = List.copyOf(accounting.leases);
        }
        for (Lease lease : retiring) {
            lease.cancel();
        }
    }

    synchronized void resetAfterShutdown() {
        pending.clear();
        accounting = new Accounting();
    }

    public final class Lease implements AutoCloseable {
        private final UUID player;
        private final Counter counter;
        private final Accounting admitted;
        private boolean released;
        private boolean cancellationRequested;
        private SchedulerUtils.TaskHandle task;

        private Lease(UUID player, Counter counter) {
            this.player = player;
            this.counter = counter;
            this.admitted = accounting;
        }

        void attach(SchedulerUtils.TaskHandle scheduled) {
            boolean cancel;
            synchronized (PendingTimers.this) {
                cancel = released || cancellationRequested || admitted != accounting || pending.get(player) != counter;
                if (!released) {
                    task = Objects.requireNonNull(scheduled, "scheduled");
                }
            }
            if (cancel) {
                cancelTask(scheduled);
            }
        }

        private void cancel() {
            SchedulerUtils.TaskHandle scheduled;
            synchronized (PendingTimers.this) {
                cancellationRequested = true;
                scheduled = task;
            }
            if (scheduled != null) {
                cancelTask(scheduled);
            }
        }

        private void cancelTask(SchedulerUtils.TaskHandle scheduled) {
            try {
                scheduled.cancel();
            } catch (RuntimeException failure) {
                Gloss.logExceptionStack(false, failure,
                    "behavior: timer cancellation failed; capacity remains reserved until its callback drains.");
            }
        }

        @Override
        public void close() {
            resume();
        }

        public boolean resume() {
            synchronized (PendingTimers.this) {
                if (released) {
                    return false;
                }
                released = true;
                task = null;
                admitted.leases.remove(this);
                counter.leases.remove(this);
                if (player == null) {
                    admitted.withoutPlayer--;
                }
                if (admitted != accounting || pending.get(player) != counter) {
                    return false;
                }
                if (counter.leases.isEmpty()) {
                    pending.remove(player);
                }
                return true;
            }
        }
    }

    private static final class Counter {
        private final Set<Lease> leases = new HashSet<>();
    }

    private static final class Accounting {
        private final Set<Lease> leases = new HashSet<>();
        private int withoutPlayer;
    }

    public record Limits(int perPlayer, int global, int withoutPlayer) {
        public static final Limits DEFAULT = new Limits(16, 8192, 256);

        public Limits {
            if (perPlayer < 0 || global < 0 || withoutPlayer < 0) {
                throw new IllegalArgumentException("Timer limits must not be negative");
            }
        }
    }
}
