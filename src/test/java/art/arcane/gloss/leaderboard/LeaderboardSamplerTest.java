package art.arcane.gloss.leaderboard;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class LeaderboardSamplerTest {
    private static String format(String template, String name, double value) {
        return template.replace("{{ name }}", name).replace("{{ value }}", String.valueOf(value));
    }

    private static final UUID ALPHA = UUID.nameUUIDFromBytes("alpha".getBytes());
    private static final UUID BRAVO = UUID.nameUUIDFromBytes("bravo".getBytes());

    @Test
    void aFastSourceIsNeverQuarantined() {
        LeaderboardSampleGuard guard = new LeaderboardSampleGuard();

        for (int sample = 0; sample < 100; sample++) {
            assertFalse(guard.record("kills", 1_000L, sample));
        }

        assertTrue(guard.available("kills", 100L));
    }

    @Test
    void threeSlowSamplesQuarantineTheSourceOnceForFiveMinutes() {
        LeaderboardSampleGuard guard = new LeaderboardSampleGuard();
        long slow = LeaderboardSampleGuard.SLOW_SAMPLE_NANOS;

        assertFalse(guard.record("kills", slow, 0L));
        assertFalse(guard.record("kills", slow, 1L));
        assertTrue(guard.record("kills", slow, 2L));
        assertFalse(guard.record("kills", slow, 3L));

        assertFalse(guard.available("kills", 2L));
        assertFalse(guard.available("kills", 2L + LeaderboardSampleGuard.QUARANTINE_MS - 1L));
        assertTrue(guard.available("kills", 2L + LeaderboardSampleGuard.QUARANTINE_MS));
    }

    @Test
    void aQuarantineThatExpiresStartsTheSlowCountAgain() {
        LeaderboardSampleGuard guard = new LeaderboardSampleGuard();
        long slow = LeaderboardSampleGuard.SLOW_SAMPLE_NANOS;
        guard.record("kills", slow, 0L);
        guard.record("kills", slow, 0L);
        guard.record("kills", slow, 0L);

        long after = LeaderboardSampleGuard.QUARANTINE_MS;
        assertTrue(guard.available("kills", after));
        assertFalse(guard.record("kills", slow, after));
        assertFalse(guard.record("kills", slow, after));
        assertTrue(guard.record("kills", slow, after));
    }

    @Test
    void guardsAreKeptPerLeaderboard() {
        LeaderboardSampleGuard guard = new LeaderboardSampleGuard();
        long slow = LeaderboardSampleGuard.SLOW_SAMPLE_NANOS;
        guard.record("kills", slow, 0L);
        guard.record("kills", slow, 0L);
        guard.record("kills", slow, 0L);

        assertFalse(guard.available("kills", 0L));
        assertTrue(guard.available("playtime", 0L));
    }

    @Test
    void aViewRanksFormatsAndCarriesTheNextReset() {
        LeaderboardDoc doc = LeaderboardDoc.parse("playtime.json",
            "{\"schemaVersion\":1,\"revision\":1,\"source\":{\"type\":\"papi\",\"placeholder\":\"x\"},"
                + "\"size\":2,\"reset\":\"hourly\",\"format\":{\"value\":\"V:{{ value }}\"}}");
        LeaderboardSnapshot snapshot = LeaderboardSnapshot.empty("playtime")
            .sampled(ALPHA, "Alpha", 0.0D, 0L).sampled(ALPHA, "Alpha", 10.0D, 0L)
            .sampled(BRAVO, "Bravo", 0.0D, 0L).sampled(BRAVO, "Bravo", 30.0D, 0L);

        LeaderboardView view = LeaderboardView.of("playtime", doc, snapshot, 0L, List.of(),
            LeaderboardSamplerTest::format);

        assertEquals(2, view.size());
        assertEquals("Bravo", view.row(1).name());
        assertEquals("V:30.0", view.row(1).formatted());
        assertEquals(2, view.rankOf(ALPHA));
        assertEquals(LeaderboardRanking.nextReset(LeaderboardDoc.Reset.HOURLY, 0L), view.resetAt());
    }

    @Test
    void aBoardThatExcludesOfflinePlayersOnlyRanksTheOnlineOnes() {
        LeaderboardDoc doc = LeaderboardDoc.parse("playtime.json",
            "{\"schemaVersion\":1,\"revision\":1,\"source\":{\"type\":\"papi\",\"placeholder\":\"x\"},"
                + "\"includeOffline\":false}");
        LeaderboardSnapshot snapshot = LeaderboardSnapshot.empty("playtime")
            .sampled(ALPHA, "Alpha", 0.0D, 0L).sampled(ALPHA, "Alpha", 10.0D, 0L)
            .sampled(BRAVO, "Bravo", 0.0D, 0L).sampled(BRAVO, "Bravo", 30.0D, 0L);

        LeaderboardView view = LeaderboardView.of("playtime", doc, snapshot, 0L, List.of(ALPHA),
            LeaderboardSamplerTest::format);

        assertEquals(1, view.rows().size());
        assertEquals("Alpha", view.row(1).name());
        assertEquals(0, view.rankOf(BRAVO));
    }

    @Test
    void aRankBeyondTheViewIsAbsentRatherThanAnError() {
        LeaderboardView view = LeaderboardView.empty("playtime");

        assertEquals(null, view.row(1));
        assertEquals(0, view.size());
        assertEquals(0, view.rankOf(ALPHA));
    }
}
