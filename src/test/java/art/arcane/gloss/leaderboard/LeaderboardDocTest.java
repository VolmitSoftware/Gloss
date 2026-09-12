package art.arcane.gloss.leaderboard;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class LeaderboardDocTest {
    @Test
    void theSpecExampleParsesWithEveryBlockIntact() {
        LeaderboardDoc doc = LeaderboardDoc.parse("playtime.json", """
            {
              "schemaVersion": 1, "revision": 1,
              "source": { "type": "statistic", "statistic": "PLAY_ONE_MINUTE" },
              "size": 10,
              "order": "desc",
              "reset": "monthly",
              "includeOffline": true,
              "format": { "value": "{{ fixed(value / 72000, 1) }}h", "name": "{{ name }}" },
              "sample": { "playersPerTick": 8 }
            }
            """);

        assertEquals(LeaderboardDoc.SourceType.STATISTIC, doc.source().type());
        assertEquals("PLAY_ONE_MINUTE", doc.source().statistic());
        assertEquals(10, doc.size());
        assertEquals(LeaderboardDoc.Order.DESC, doc.order());
        assertEquals(LeaderboardDoc.Reset.MONTHLY, doc.reset());
        assertTrue(doc.includeOffline());
        assertEquals(8, doc.sample().playersPerTick());
    }

    @Test
    void absentBlocksTakeTheDocumentedDefaults() {
        LeaderboardDoc doc = LeaderboardDoc.parse("kills.json",
            "{\"schemaVersion\":1,\"revision\":1,\"source\":{\"type\":\"papi\",\"placeholder\":\"%stats_kills%\"}}");

        assertEquals(LeaderboardDoc.Order.DESC, doc.order());
        assertEquals(LeaderboardDoc.Reset.NEVER, doc.reset());
        assertEquals(LeaderboardDoc.DEFAULT_SIZE, doc.size());
        assertEquals(LeaderboardDoc.DEFAULT_PLAYERS_PER_TICK, doc.sample().playersPerTick());
        assertEquals("{{ value }}", doc.format().value());
        assertEquals("{{ name }}", doc.format().name());
    }

    @Test
    void aSourceMissingTheFieldItsTypeNeedsIsRefused() {
        assertThrows(IllegalArgumentException.class, () -> LeaderboardDoc.parse("bad.json",
            "{\"schemaVersion\":1,\"revision\":1,\"source\":{\"type\":\"papi\"}}"));
        assertThrows(IllegalArgumentException.class, () -> LeaderboardDoc.parse("bad.json",
            "{\"schemaVersion\":1,\"revision\":1,\"source\":{\"type\":\"statistic\"}}"));
        assertThrows(IllegalArgumentException.class, () -> LeaderboardDoc.parse("bad.json",
            "{\"schemaVersion\":1,\"revision\":1,\"source\":{\"type\":\"metric\"}}"));
    }

    @Test
    void aDocumentWithNoSourceIsRefused() {
        assertThrows(IllegalArgumentException.class, () -> LeaderboardDoc.parse("bad.json",
            "{\"schemaVersion\":1,\"revision\":1}"));
    }

    @Test
    void sizeAndSamplingAreClampedToTheirBounds() {
        LeaderboardDoc doc = LeaderboardDoc.parse("kills.json",
            "{\"schemaVersion\":1,\"revision\":1,\"source\":{\"type\":\"papi\",\"placeholder\":\"x\"},"
                + "\"size\":100000,\"sample\":{\"playersPerTick\":0}}");

        assertEquals(LeaderboardDoc.MAX_SIZE, doc.size());
        assertEquals(1, doc.sample().playersPerTick());
    }

    @Test
    void anotherSchemaVersionIsRefused() {
        assertThrows(IllegalArgumentException.class, () -> LeaderboardDoc.parse("bad.json",
            "{\"schemaVersion\":2,\"revision\":1,\"source\":{\"type\":\"papi\",\"placeholder\":\"x\"}}"));
    }
}
