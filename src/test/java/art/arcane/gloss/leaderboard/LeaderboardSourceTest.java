package art.arcane.gloss.leaderboard;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class LeaderboardSourceTest {
    @Test
    void aPlaceholderSourceIsAlwaysAvailable() {
        assertNotNull(LeaderboardSource.of(doc("{\"type\":\"papi\",\"placeholder\":\"%stats_kills%\"}")));
    }

    @Test
    void anUntypedStatisticResolvesByName() {
        assertNotNull(LeaderboardSource.of(doc("{\"type\":\"statistic\",\"statistic\":\"PLAY_ONE_MINUTE\"}")));
    }

    @Test
    void anUnknownStatisticIsRefusedByName() {
        IllegalArgumentException failure = assertThrows(IllegalArgumentException.class,
            () -> LeaderboardSource.of(doc("{\"type\":\"statistic\",\"statistic\":\"NOT_A_STAT\"}")));

        assertTrue(failure.getMessage().contains("NOT_A_STAT"), failure.getMessage());
    }

    @Test
    void aStatisticThatNeedsASubjectIsRefusedWithoutOne() {
        IllegalArgumentException failure = assertThrows(IllegalArgumentException.class,
            () -> LeaderboardSource.of(doc("{\"type\":\"statistic\",\"statistic\":\"MINE_BLOCK\"}")));

        assertTrue(failure.getMessage().contains("material"), failure.getMessage());
    }

    @Test
    void aMetricSourceIsRefusedUntilAFleetPluginPublishesPerPlayerGroups() {
        IllegalArgumentException failure = assertThrows(IllegalArgumentException.class,
            () -> LeaderboardSource.of(doc("{\"type\":\"metric\",\"key\":\"react.tps\"}")));

        assertTrue(failure.getMessage().contains("metric"), failure.getMessage());
    }

    private static LeaderboardDoc doc(String source) {
        return LeaderboardDoc.parse("board.json",
            "{\"schemaVersion\":1,\"revision\":1,\"source\":" + source + "}");
    }
}
