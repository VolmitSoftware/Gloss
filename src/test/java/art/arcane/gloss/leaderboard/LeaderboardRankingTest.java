package art.arcane.gloss.leaderboard;

import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.time.ZoneOffset;
import java.time.ZonedDateTime;
import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

class LeaderboardRankingTest {
    private static final UUID ALPHA = UUID.nameUUIDFromBytes("alpha".getBytes());
    private static final UUID BRAVO = UUID.nameUUIDFromBytes("bravo".getBytes());
    private static final UUID CHARLIE = UUID.nameUUIDFromBytes("charlie".getBytes());

    @Test
    void descendingOrderPutsTheLargestValueFirstAndCapsAtSize() {
        LeaderboardSnapshot snapshot = snapshot(
            entry(ALPHA, "Alpha", 10.0D), entry(BRAVO, "Bravo", 30.0D), entry(CHARLIE, "Charlie", 20.0D));

        List<LeaderboardSnapshot.Entry> ranked = LeaderboardRanking.rank(snapshot, 2, LeaderboardDoc.Order.DESC);

        assertEquals(List.of("Bravo", "Charlie"), names(ranked));
    }

    @Test
    void ascendingOrderReversesTheComparison() {
        LeaderboardSnapshot snapshot = snapshot(
            entry(ALPHA, "Alpha", 10.0D), entry(BRAVO, "Bravo", 30.0D), entry(CHARLIE, "Charlie", 20.0D));

        assertEquals(List.of("Alpha", "Charlie", "Bravo"),
            names(LeaderboardRanking.rank(snapshot, 10, LeaderboardDoc.Order.ASC)));
    }

    @Test
    void tiesBreakOnNameSoTheOrderIsStable() {
        LeaderboardSnapshot snapshot = snapshot(
            entry(CHARLIE, "Charlie", 10.0D), entry(ALPHA, "Alpha", 10.0D), entry(BRAVO, "Bravo", 10.0D));

        assertEquals(List.of("Alpha", "Bravo", "Charlie"),
            names(LeaderboardRanking.rank(snapshot, 10, LeaderboardDoc.Order.DESC)));
    }

    @Test
    void samplingKeepsTheAllTimeValueAndTheDeltaSinceThePeriodStarted() {
        LeaderboardSnapshot first = LeaderboardSnapshot.empty("playtime")
            .sampled(ALPHA, "Alpha", 100.0D, 1_000L);
        LeaderboardSnapshot second = first.sampled(ALPHA, "Alpha", 175.0D, 2_000L);

        assertEquals(100.0D, first.entry(ALPHA).allTime());
        assertEquals(0.0D, first.entry(ALPHA).period());
        assertEquals(175.0D, second.entry(ALPHA).allTime());
        assertEquals(75.0D, second.entry(ALPHA).period());
        assertEquals(2_000L, second.entry(ALPHA).lastSeen());
    }

    @Test
    void aRollResetsThePeriodButKeepsTheAllTimeTotal() {
        long start = epoch(2026, 9, 11, 10, 30);
        LeaderboardSnapshot snapshot = LeaderboardSnapshot.empty("playtime")
            .sampled(ALPHA, "Alpha", 100.0D, start)
            .sampled(ALPHA, "Alpha", 160.0D, start);

        LeaderboardSnapshot rolled = LeaderboardRanking.rollIfDue(snapshot, LeaderboardDoc.Reset.HOURLY,
            epoch(2026, 9, 11, 11, 5));

        assertEquals(160.0D, rolled.entry(ALPHA).allTime());
        assertEquals(0.0D, rolled.entry(ALPHA).period());
        assertEquals(60.0D, snapshot.entry(ALPHA).period());
        assertEquals(75.0D, rolled.sampled(ALPHA, "Alpha", 235.0D, 0L).entry(ALPHA).period());
    }

    @Test
    void eachResetKindRollsOnItsOwnBoundary() {
        long tenThirty = epoch(2026, 9, 11, 10, 30);
        LeaderboardSnapshot snapshot = LeaderboardSnapshot.empty("playtime")
            .withPeriodStart(LeaderboardRanking.periodStart(LeaderboardDoc.Reset.HOURLY, tenThirty));

        assertSame(snapshot, LeaderboardRanking.rollIfDue(snapshot, LeaderboardDoc.Reset.HOURLY,
            epoch(2026, 9, 11, 10, 59)));
        assertTrue(LeaderboardRanking.rollIfDue(snapshot, LeaderboardDoc.Reset.HOURLY,
            epoch(2026, 9, 11, 11, 0)) != snapshot);
        assertSame(snapshot, LeaderboardRanking.rollIfDue(snapshot, LeaderboardDoc.Reset.NEVER,
            epoch(2030, 1, 1, 0, 0)));
    }

    @Test
    void periodStartTruncatesToTheResetBoundary() {
        long moment = epoch(2026, 9, 11, 10, 30);

        assertEquals(0L, LeaderboardRanking.periodStart(LeaderboardDoc.Reset.NEVER, moment));
        assertEquals(epoch(2026, 9, 11, 10, 0),
            LeaderboardRanking.periodStart(LeaderboardDoc.Reset.HOURLY, moment));
        assertEquals(epoch(2026, 9, 11, 0, 0),
            LeaderboardRanking.periodStart(LeaderboardDoc.Reset.DAILY, moment));
        assertEquals(epoch(2026, 9, 7, 0, 0),
            LeaderboardRanking.periodStart(LeaderboardDoc.Reset.WEEKLY, moment));
        assertEquals(epoch(2026, 9, 1, 0, 0),
            LeaderboardRanking.periodStart(LeaderboardDoc.Reset.MONTHLY, moment));
    }

    @Test
    void theNextResetIsTheEndOfTheCurrentPeriod() {
        long moment = epoch(2026, 9, 11, 10, 30);

        assertEquals(epoch(2026, 9, 11, 11, 0),
            LeaderboardRanking.nextReset(LeaderboardDoc.Reset.HOURLY, moment));
        assertEquals(epoch(2026, 10, 1, 0, 0),
            LeaderboardRanking.nextReset(LeaderboardDoc.Reset.MONTHLY, moment));
        assertEquals(0L, LeaderboardRanking.nextReset(LeaderboardDoc.Reset.NEVER, moment));
    }

    private static long epoch(int year, int month, int day, int hour, int minute) {
        return ZonedDateTime.of(year, month, day, hour, minute, 0, 0, ZoneOffset.UTC)
            .toInstant().toEpochMilli();
    }

    private static List<String> names(List<LeaderboardSnapshot.Entry> ranked) {
        return ranked.stream().map(LeaderboardSnapshot.Entry::name).toList();
    }

    private static LeaderboardSnapshot.Entry entry(UUID id, String name, double value) {
        return new LeaderboardSnapshot.Entry(id, name, value, value, Instant.now().toEpochMilli());
    }

    private static LeaderboardSnapshot snapshot(LeaderboardSnapshot.Entry... entries) {
        return new LeaderboardSnapshot("playtime", 0L, List.of(entries));
    }
}
