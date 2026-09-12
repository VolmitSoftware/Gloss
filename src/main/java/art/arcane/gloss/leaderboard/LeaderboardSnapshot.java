package art.arcane.gloss.leaderboard;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * What one leaderboard knows about every player it has ever sampled. The period value is the delta
 * since {@code periodStart}, so the baseline a roll-over installs is simply
 * {@code allTime - period} and never needs a field of its own.
 */
public record LeaderboardSnapshot(String id, long periodStart, List<Entry> entries) {
    public LeaderboardSnapshot {
        entries = List.copyOf(entries);
    }

    public record Entry(UUID uuid, String name, double allTime, double period, long lastSeen) {
    }

    public static LeaderboardSnapshot empty(String id) {
        return new LeaderboardSnapshot(id, 0L, List.of());
    }

    public Entry entry(UUID uuid) {
        for (Entry entry : entries) {
            if (entry.uuid().equals(uuid)) {
                return entry;
            }
        }
        return null;
    }

    public LeaderboardSnapshot withPeriodStart(long periodStart) {
        return new LeaderboardSnapshot(id, periodStart, entries);
    }

    /** A fresh reading for one player, keeping every other entry untouched. */
    public LeaderboardSnapshot sampled(UUID uuid, String name, double value, long nowMs) {
        List<Entry> updated = new ArrayList<>(entries.size() + 1);
        boolean replaced = false;
        for (Entry entry : entries) {
            if (!entry.uuid().equals(uuid)) {
                updated.add(entry);
                continue;
            }
            updated.add(new Entry(uuid, name, value,
                Math.max(0.0D, value - (entry.allTime() - entry.period())), nowMs));
            replaced = true;
        }
        if (!replaced) {
            updated.add(new Entry(uuid, name, value, 0.0D, nowMs));
        }
        return new LeaderboardSnapshot(id, periodStart, updated);
    }

    /** Every entry's period bucket reset, with all-time totals preserved. */
    public LeaderboardSnapshot rolled(long periodStart) {
        List<Entry> reset = new ArrayList<>(entries.size());
        for (Entry entry : entries) {
            reset.add(new Entry(entry.uuid(), entry.name(), entry.allTime(), 0.0D, entry.lastSeen()));
        }
        return new LeaderboardSnapshot(id, periodStart, reset);
    }
}
