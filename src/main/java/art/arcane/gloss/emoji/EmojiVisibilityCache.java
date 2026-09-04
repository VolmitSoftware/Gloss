package art.arcane.gloss.emoji;

import art.arcane.gloss.condition.ShowCondition;

import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Predicate;

final class EmojiVisibilityCache {
    private final List<ShowCondition> conditions;
    private final Map<UUID, Sample> samples = new ConcurrentHashMap<>();
    private volatile boolean closed;

    EmojiVisibilityCache(Collection<ShowCondition> conditions) {
        this.conditions = List.copyOf(conditions);
    }

    boolean isDynamic() {
        return !conditions.isEmpty();
    }

    Map<ShowCondition, Boolean> snapshot(UUID playerId) {
        Sample sample = samples.get(playerId);
        return closed || sample == null || sample.retired ? Map.of() : sample.values;
    }

    Sample begin(UUID playerId) {
        if (closed || conditions.isEmpty()) {
            return null;
        }
        Sample sample = samples.computeIfAbsent(playerId, ignored -> new Sample());
        if (closed) {
            remove(playerId);
            return null;
        }
        return !sample.retired && sample.pending.compareAndSet(false, true) ? sample : null;
    }

    void capture(Sample sample, Predicate<ShowCondition> resolver) {
        try {
            if (closed || sample.retired) {
                return;
            }
            Map<ShowCondition, Boolean> values = new HashMap<>(conditions.size());
            for (ShowCondition show : conditions) {
                values.put(show, resolver.test(show));
            }
            if (!closed && !sample.retired) {
                sample.values = Map.copyOf(values);
            }
        } finally {
            sample.pending.set(false);
        }
    }

    void remove(UUID playerId) {
        Sample sample = samples.remove(playerId);
        if (sample != null) {
            sample.retire();
        }
    }

    void discard(UUID playerId, Sample sample) {
        samples.remove(playerId, sample);
        sample.retire();
    }

    void close() {
        closed = true;
        for (Sample sample : samples.values()) {
            sample.retire();
        }
        samples.clear();
    }

    static final class Sample {
        private final AtomicBoolean pending = new AtomicBoolean();
        private volatile Map<ShowCondition, Boolean> values = Map.of();
        private volatile boolean retired;

        void retire() {
            retired = true;
            values = Map.of();
            pending.set(false);
        }
    }
}
