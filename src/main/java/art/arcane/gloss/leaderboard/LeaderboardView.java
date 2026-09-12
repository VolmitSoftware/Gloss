package art.arcane.gloss.leaderboard;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.UUID;

/**
 * One leaderboard as every surface reads it: already ranked, already formatted, already capped.
 * Rendering the authored formats once per recompute rather than once per lookup is what keeps a
 * hologram line that names ten ranks off the expression engine entirely.
 */
public record LeaderboardView(String id, int size, long resetAt, List<Row> rows) {
    public LeaderboardView {
        rows = List.copyOf(rows);
    }

    public record Row(UUID uuid, String name, double value, String formatted) {
    }

    /** Renders {@code format.value} and {@code format.name} for one ranked row. */
    @FunctionalInterface
    public interface RowFormatter {
        String format(String template, String name, double value);
    }

    public static LeaderboardView empty(String id) {
        return new LeaderboardView(id, 0, 0L, List.of());
    }

    public static LeaderboardView of(String id, LeaderboardDoc doc, LeaderboardSnapshot snapshot,
                                     long nowMs, Collection<UUID> online, RowFormatter formatter) {
        LeaderboardSnapshot visible = doc.includeOffline() ? snapshot : onlyOnline(snapshot, online);
        List<LeaderboardSnapshot.Entry> ranked = LeaderboardRanking.rank(visible, doc.size(), doc.order());
        List<Row> rows = new ArrayList<>(ranked.size());
        for (LeaderboardSnapshot.Entry entry : ranked) {
            rows.add(new Row(entry.uuid(),
                formatter.format(doc.format().name(), entry.name(), entry.period()),
                entry.period(),
                formatter.format(doc.format().value(), entry.name(), entry.period())));
        }
        return new LeaderboardView(id, rows.size(), LeaderboardRanking.nextReset(doc.reset(), nowMs), rows);
    }

    /** @param rank one-based; null when the view does not go that deep */
    public Row row(int rank) {
        return rank < 1 || rank > rows.size() ? null : rows.get(rank - 1);
    }

    public Row row(UUID uuid) {
        for (Row row : rows) {
            if (row.uuid().equals(uuid)) {
                return row;
            }
        }
        return null;
    }

    /** @return the one-based rank, or 0 when the player is not in the view */
    public int rankOf(UUID uuid) {
        for (int index = 0; index < rows.size(); index++) {
            if (rows.get(index).uuid().equals(uuid)) {
                return index + 1;
            }
        }
        return 0;
    }

    private static LeaderboardSnapshot onlyOnline(LeaderboardSnapshot snapshot, Collection<UUID> online) {
        List<LeaderboardSnapshot.Entry> kept = new ArrayList<>(online.size());
        for (LeaderboardSnapshot.Entry entry : snapshot.entries()) {
            if (online.contains(entry.uuid())) {
                kept.add(entry);
            }
        }
        return new LeaderboardSnapshot(snapshot.id(), snapshot.periodStart(), kept);
    }
}
