package art.arcane.gloss.surface;

import java.util.ArrayDeque;
import java.util.Iterator;
import java.util.Objects;
import java.util.function.LongSupplier;

/**
 * Per-viewer buffer of titles waiting for a compositor claim. Identical text coalesces so a driver
 * pass that keeps re-asserting the same title never grows the queue, and the oldest request is
 * dropped once the configured limit is reached. A head that no claim would grant is dropped once
 * its own window has passed, so one refusal cannot hold everything behind it.
 */
public final class TitleQueue {
    private final int limit;
    private final LongSupplier clock;
    private final ArrayDeque<Queued> pending;

    public TitleQueue(int limit) {
        this(limit, System::currentTimeMillis);
    }

    public TitleQueue(int limit, LongSupplier clock) {
        this.limit = Math.max(1, limit);
        this.clock = clock;
        this.pending = new ArrayDeque<>(Math.min(this.limit, 16));
    }

    public synchronized boolean offer(TitleRequest request) {
        Objects.requireNonNull(request, "request");
        for (Queued queued : pending) {
            if (queued.request().sameText(request)) {
                return false;
            }
        }
        if (pending.size() >= limit) {
            pending.pollFirst();
        }
        pending.addLast(new Queued(request, clock.getAsLong()));
        return true;
    }

    public synchronized TitleRequest peek() {
        Queued head = pending.peekFirst();
        return head == null ? null : head.request();
    }

    public synchronized TitleRequest poll() {
        Queued head = pending.pollFirst();
        return head == null ? null : head.request();
    }

    /** Drops everything queued for one purpose, for a surface that has stood down. */
    public synchronized void drop(String purpose) {
        Iterator<Queued> iterator = pending.iterator();
        while (iterator.hasNext()) {
            if (iterator.next().request().purpose().equals(purpose)) {
                iterator.remove();
            }
        }
    }

    /** Drops a head that outlived its own window, so one refusal cannot hold everything behind it. */
    public synchronized void dropStaleHead() {
        Queued head = pending.peekFirst();
        if (head != null && clock.getAsLong() - head.queuedAtMillis() > head.request().ttlMillis()) {
            pending.pollFirst();
        }
    }

    public synchronized int size() {
        return pending.size();
    }

    public synchronized void clear() {
        pending.clear();
    }

    private record Queued(TitleRequest request, long queuedAtMillis) {
    }

    public record TitleRequest(String purpose, int priority, String title, String subtitle, int fadeInTicks,
                               int stayTicks, int fadeOutTicks) {
        public TitleRequest {
            purpose = Objects.requireNonNull(purpose, "purpose");
            title = title == null ? "" : title;
            subtitle = subtitle == null ? "" : subtitle;
        }

        public long ttlMillis() {
            return Math.max(1L, (long) (fadeInTicks + stayTicks + fadeOutTicks) * 50L);
        }

        boolean sameText(TitleRequest other) {
            return title.equals(other.title) && subtitle.equals(other.subtitle);
        }
    }
}
