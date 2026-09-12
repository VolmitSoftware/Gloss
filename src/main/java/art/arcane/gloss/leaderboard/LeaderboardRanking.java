package art.arcane.gloss.leaderboard;

import java.time.DayOfWeek;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.ZonedDateTime;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/**
 * Ordering and period roll-over. Boundaries are computed in UTC so a server that moves between
 * zones, or a fleet spread across them, rolls every leaderboard at the same instant.
 */
public final class LeaderboardRanking {
    private LeaderboardRanking() {
    }

    /** The top {@code size} entries by period value, ties broken on name so the order is stable. */
    public static List<LeaderboardSnapshot.Entry> rank(LeaderboardSnapshot snapshot, int size,
                                                       LeaderboardDoc.Order order) {
        Comparator<LeaderboardSnapshot.Entry> byValue = Comparator
            .comparingDouble(LeaderboardSnapshot.Entry::period);
        List<LeaderboardSnapshot.Entry> ranked = new ArrayList<>(snapshot.entries());
        ranked.sort((order == LeaderboardDoc.Order.ASC ? byValue : byValue.reversed())
            .thenComparing(LeaderboardSnapshot.Entry::name));
        return List.copyOf(ranked.subList(0, Math.min(size, ranked.size())));
    }

    /** The same snapshot when the period still holds, otherwise one with its buckets reset. */
    public static LeaderboardSnapshot rollIfDue(LeaderboardSnapshot snapshot, LeaderboardDoc.Reset reset,
                                                long nowMs) {
        long start = periodStart(reset, nowMs);
        return start > snapshot.periodStart() ? snapshot.rolled(start) : snapshot;
    }

    /** The epoch millisecond the current period began, or 0 when the board never resets. */
    public static long periodStart(LeaderboardDoc.Reset reset, long nowMs) {
        if (reset == LeaderboardDoc.Reset.NEVER) {
            return 0L;
        }
        ZonedDateTime now = ZonedDateTime.ofInstant(Instant.ofEpochMilli(nowMs), ZoneOffset.UTC);
        return switch (reset) {
            case HOURLY -> millis(now.truncatedTo(ChronoUnit.HOURS));
            case DAILY -> millis(now.truncatedTo(ChronoUnit.DAYS));
            case WEEKLY -> millis(now.truncatedTo(ChronoUnit.DAYS)
                .minusDays(now.getDayOfWeek().getValue() - DayOfWeek.MONDAY.getValue()));
            case MONTHLY -> millis(now.truncatedTo(ChronoUnit.DAYS).withDayOfMonth(1));
            case NEVER -> 0L;
        };
    }

    /** The epoch millisecond the current period ends, or 0 when the board never resets. */
    public static long nextReset(LeaderboardDoc.Reset reset, long nowMs) {
        if (reset == LeaderboardDoc.Reset.NEVER) {
            return 0L;
        }
        ZonedDateTime start = ZonedDateTime.ofInstant(Instant.ofEpochMilli(periodStart(reset, nowMs)),
            ZoneOffset.UTC);
        return switch (reset) {
            case HOURLY -> millis(start.plusHours(1L));
            case DAILY -> millis(start.plusDays(1L));
            case WEEKLY -> millis(start.plusWeeks(1L));
            case MONTHLY -> millis(start.plusMonths(1L));
            case NEVER -> 0L;
        };
    }

    private static long millis(ZonedDateTime moment) {
        return moment.toInstant().toEpochMilli();
    }
}
