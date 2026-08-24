package art.arcane.gloss.indicator;

import java.util.HashMap;
import java.util.Map;
import java.util.PriorityQueue;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

final class IndicatorPermissionCache {
    private final Set<UUID> tracked = ConcurrentHashMap.newKeySet();
    private final ConcurrentHashMap<UUID, Snapshot> permissions = new ConcurrentHashMap<>();
    private final Object scheduleLock = new Object();
    private final PriorityQueue<ScheduledRefresh> schedule = new PriorityQueue<>();
    private final Map<UUID, Long> scheduledVersions = new HashMap<>();
    private long nextVersion;

    boolean track(UUID playerId) {
        if (tracked.add(playerId)) {
            schedule(playerId, Long.MIN_VALUE);
            return true;
        }
        return false;
    }

    void update(UUID playerId, boolean allowed) {
        permissions.compute(playerId, (ignored, existing) -> tracked.contains(playerId)
            ? new Snapshot(allowed)
            : null);
    }

    boolean allowed(UUID playerId) {
        Snapshot snapshot = permissions.get(playerId);
        return snapshot != null && snapshot.allowed();
    }

    UUID claimNextRefresh(long nowMs, long refreshIntervalMs) {
        synchronized (scheduleLock) {
            while (true) {
                ScheduledRefresh next = schedule.peek();
                if (next == null || next.dueAtMs() > nowMs) {
                    return null;
                }
                schedule.poll();
                Long currentVersion = scheduledVersions.get(next.playerId());
                if (!tracked.contains(next.playerId())
                    || currentVersion == null || currentVersion.longValue() != next.version()) {
                    continue;
                }
                scheduleLocked(next.playerId(), nowMs + refreshIntervalMs);
                return next.playerId();
            }
        }
    }

    void defer(UUID playerId, long nowMs, long refreshIntervalMs) {
        permissions.compute(playerId, (ignored, existing) -> {
            if (!tracked.contains(playerId)) {
                return null;
            }
            boolean allowed = existing != null && existing.allowed();
            return new Snapshot(allowed);
        });
        scheduleIfTracked(playerId, nowMs + refreshIntervalMs);
    }

    void makeDue(UUID playerId) {
        scheduleIfTracked(playerId, Long.MIN_VALUE);
    }

    void remove(UUID playerId) {
        tracked.remove(playerId);
        permissions.remove(playerId);
        synchronized (scheduleLock) {
            scheduledVersions.remove(playerId);
        }
    }

    void clear() {
        tracked.clear();
        permissions.clear();
        synchronized (scheduleLock) {
            schedule.clear();
            scheduledVersions.clear();
        }
    }

    private void scheduleIfTracked(UUID playerId, long dueAtMs) {
        if (!tracked.contains(playerId)) {
            return;
        }
        schedule(playerId, dueAtMs);
    }

    private void schedule(UUID playerId, long dueAtMs) {
        synchronized (scheduleLock) {
            if (tracked.contains(playerId)) {
                scheduleLocked(playerId, dueAtMs);
            }
        }
    }

    private void scheduleLocked(UUID playerId, long dueAtMs) {
        long version = ++nextVersion;
        scheduledVersions.put(playerId, version);
        schedule.offer(new ScheduledRefresh(playerId, dueAtMs, version));
    }

    private record Snapshot(boolean allowed) {
    }

    private record ScheduledRefresh(UUID playerId, long dueAtMs, long version)
        implements Comparable<ScheduledRefresh> {
        @Override
        public int compareTo(ScheduledRefresh other) {
            int dueComparison = Long.compare(dueAtMs, other.dueAtMs);
            return dueComparison != 0 ? dueComparison : Long.compare(version, other.version);
        }
    }
}
