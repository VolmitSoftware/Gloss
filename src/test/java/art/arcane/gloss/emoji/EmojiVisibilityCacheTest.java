package art.arcane.gloss.emoji;

import art.arcane.gloss.condition.ShowCondition;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class EmojiVisibilityCacheTest {
    private final ShowCondition show = ShowCondition.of("world.time < 12000");
    private final EmojiVisibilityCache cache = new EmojiVisibilityCache(List.of(show));
    private final UUID playerId = UUID.randomUUID();

    @Test
    void missingSnapshotsHideConditionalEmojiAndOnlyCompletedCapturesPublish() {
        assertTrue(cache.snapshot(playerId).isEmpty());
        EmojiVisibilityCache.Sample sample = cache.begin(playerId);
        assertTrue(cache.snapshot(playerId).isEmpty());
        cache.capture(sample, condition -> true);
        assertEquals(Map.of(show, true), cache.snapshot(playerId));
    }

    @Test
    void publishedSnapshotsAreImmutableAndRefreshDoesNotMutateEarlierReaders() {
        cache.capture(cache.begin(playerId), condition -> true);
        Map<ShowCondition, Boolean> previous = cache.snapshot(playerId);
        assertThrows(UnsupportedOperationException.class, () -> previous.put(show, false));
        cache.capture(cache.begin(playerId), condition -> false);
        assertEquals(Map.of(show, true), previous);
        assertEquals(Map.of(show, false), cache.snapshot(playerId));
    }

    @Test
    void viewersNeverShareVisibilityResults() {
        UUID other = UUID.randomUUID();
        cache.capture(cache.begin(playerId), condition -> true);
        cache.capture(cache.begin(other), condition -> false);
        assertEquals(Map.of(show, true), cache.snapshot(playerId));
        assertEquals(Map.of(show, false), cache.snapshot(other));
    }

    @Test
    void queuedCapturesAreDeduplicatedAndCanRetryAfterSchedulerRejection() {
        EmojiVisibilityCache.Sample pending = cache.begin(playerId);
        assertNull(cache.begin(playerId));
        cache.discard(playerId, pending);
        assertNotNull(cache.begin(playerId));
    }

    @Test
    void quitInvalidatesQueuedWorkAndReconnectStartsWithNoVisibility() {
        EmojiVisibilityCache.Sample pending = cache.begin(playerId);
        cache.remove(playerId);
        EmojiVisibilityCache.Sample reconnect = cache.begin(playerId);
        AtomicBoolean called = new AtomicBoolean();
        cache.capture(pending, condition -> {
            called.set(true);
            return true;
        });
        assertFalse(called.get());
        assertTrue(cache.snapshot(playerId).isEmpty());
        cache.capture(reconnect, condition -> false);
        cache.discard(playerId, pending);
        assertEquals(Map.of(show, false), cache.snapshot(playerId));
    }

    @Test
    void quitDuringCaptureCannotRepublishRemovedSnapshot() {
        cache.capture(cache.begin(playerId), condition -> {
            cache.remove(playerId);
            return true;
        });
        assertTrue(cache.snapshot(playerId).isEmpty());
    }

    @Test
    void disableOrReloadClosesSnapshotsAndInvalidatesPendingCaptures() {
        cache.capture(cache.begin(playerId), condition -> true);
        EmojiVisibilityCache.Sample pending = cache.begin(playerId);
        cache.close();
        cache.capture(pending, condition -> {
            throw new AssertionError("closed capture accessed viewer state");
        });
        assertTrue(cache.snapshot(playerId).isEmpty());
        assertNull(cache.begin(playerId));
    }

    @Test
    void constantOnlyRegistryDoesNotCreatePlayerCaptures() {
        EmojiVisibilityCache constants = new EmojiVisibilityCache(List.of());
        assertFalse(constants.isDynamic());
        assertNull(constants.begin(playerId));
    }
}
