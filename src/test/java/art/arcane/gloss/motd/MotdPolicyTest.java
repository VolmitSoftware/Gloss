package art.arcane.gloss.motd;

import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;
import java.util.Random;
import java.util.concurrent.atomic.AtomicLong;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.assertThrows;

class MotdPolicyTest {
    @Test
    void weightsPreserveTheirDistribution() {
        List<MotdPolicy.Candidate> entries = List.of(new MotdPolicy.Candidate(0, 1, MotdPolicy.Selector.ANY),
            new MotdPolicy.Candidate(1, 9, MotdPolicy.Selector.ANY));
        Random random = new Random(21L);
        int common = 0;
        for (int sample = 0; sample < 10000; sample++) {
            common += MotdPolicy.Rotation.DEFAULT.choose(entries, new MotdPolicy.Request("", null), 0, random) == 1 ? 1 : 0;
        }
        assertTrue(common > 8800 && common < 9200);
    }

    @Test
    void untrustedHostMetadataMatchesOnlyExactNamesOrSubdomains() {
        MotdPolicy.Selector selector = new MotdPolicy.Selector(List.of("*.example.org", "direct.test"),
            770, 800, null, null, null, null, null, null, null);
        assertTrue(selector.matchesRequest(new MotdPolicy.Request("PLAY.EXAMPLE.ORG.\0ignored", 774)));
        assertTrue(selector.matchesRequest(new MotdPolicy.Request("direct.test", 774)));
        assertFalse(selector.matchesRequest(new MotdPolicy.Request("example.org", 774)));
        assertFalse(selector.matchesRequest(new MotdPolicy.Request("notexample.org", 774)));
        assertFalse(selector.matchesRequest(new MotdPolicy.Request("play.example.org", null)));
        assertFalse(selector.matchesRequest(new MotdPolicy.Request("play.example.org", 769)));
    }

    @Test
    void overnightCalendarAndStateSelectionUsesRealCounts() {
        MotdPolicy.Selector selector = new MotdPolicy.Selector(null, null, null, "UTC", "22:00", "02:00",
            List.of(3, 4), List.of("event"), 2, 10);
        assertTrue(selector.matchesSnapshot(Instant.parse("2026-10-07T23:00:00Z").toEpochMilli(), "event", 5));
        assertTrue(selector.matchesSnapshot(Instant.parse("2026-10-08T01:59:59Z").toEpochMilli(), "event", 5));
        assertFalse(selector.matchesSnapshot(Instant.parse("2026-10-08T02:00:00Z").toEpochMilli(), "event", 5));
        assertFalse(selector.matchesSnapshot(Instant.parse("2026-10-07T23:00:00Z").toEpochMilli(), "normal", 5));
        assertFalse(selector.matchesSnapshot(Instant.parse("2026-10-07T23:00:00Z").toEpochMilli(), "event", 1));
    }

    @Test
    void sequenceAndTimeRotateOnlyThroughEligibleEntries() {
        MotdPolicy.Selector host = new MotdPolicy.Selector(List.of("event.test"), null, null, null,
            null, null, null, null, null, null);
        List<MotdPolicy.Candidate> entries = List.of(new MotdPolicy.Candidate(0, 1, host),
            new MotdPolicy.Candidate(1, 1, MotdPolicy.Selector.ANY),
            new MotdPolicy.Candidate(2, 1, MotdPolicy.Selector.ANY));
        MotdPolicy.Rotation sequence = new MotdPolicy.Rotation("sequence", null);
        MotdPolicy.Request request = new MotdPolicy.Request("main.test", null);
        AtomicLong counter = new AtomicLong();
        assertEquals(1, sequence.choose(entries, request, sequence.position(counter, 0), new Random(1)));
        assertEquals(2, sequence.choose(entries, request, sequence.position(counter, 0), new Random(1)));
        assertEquals(1, sequence.choose(entries, request, sequence.position(counter, 0), new Random(1)));
        MotdPolicy.Rotation time = new MotdPolicy.Rotation("time", 2L);
        assertEquals(2, time.choose(entries, request, time.position(counter, 2000L), new Random(1)));
        assertEquals(1, time.choose(entries, request, time.position(counter, 4000L), new Random(1)));
        assertEquals(-1, time.choose(List.of(), request, 0, new Random(1)));
    }

    @Test
    void offsetsCannotOverflowAndInvalidPoliciesFailAtLoad() {
        assertEquals(Integer.MAX_VALUE, MotdPolicy.count("offset", 20, Integer.MAX_VALUE));
        assertEquals(0, MotdPolicy.count("offset", -20, 10));
        assertThrows(IllegalArgumentException.class, () -> new MotdPolicy.Counts("fixed", -1, null, null, null));
        assertThrows(IllegalArgumentException.class, () -> new MotdPolicy.Rotation("time", 0L));
        assertThrows(IllegalArgumentException.class, () -> new MotdPolicy.Selector(List.of("*evil*"),
            null, null, null, null, null, null, null, null, null));
    }
}
