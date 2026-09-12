package art.arcane.gloss.surface;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class TitleQueueTest {
    @Test
    void requestsAreServedInTheOrderTheyWereOffered() {
        TitleQueue queue = new TitleQueue(8);
        queue.offer(request("first"));
        queue.offer(request("second"));

        assertEquals(2, queue.size());
        assertEquals("first", queue.poll().title());
        assertEquals("second", queue.poll().title());
        assertNull(queue.poll());
    }

    @Test
    void anIdenticalTitleAndSubtitleIsCoalescedInsteadOfQueuedTwice() {
        TitleQueue queue = new TitleQueue(8);

        assertTrue(queue.offer(request("same")));
        assertFalse(queue.offer(request("same")));
        assertEquals(1, queue.size());
    }

    @Test
    void aDifferentSubtitleWithTheSameTitleIsItsOwnRequest() {
        TitleQueue queue = new TitleQueue(8);
        queue.offer(new TitleQueue.TitleRequest("gloss:surface:a", 40, "same", "one", 10, 40, 10));
        queue.offer(new TitleQueue.TitleRequest("gloss:surface:a", 40, "same", "two", 10, 40, 10));

        assertEquals(2, queue.size());
    }

    @Test
    void theOldestRequestIsDroppedWhenTheQueueIsFull() {
        TitleQueue queue = new TitleQueue(2);
        queue.offer(request("one"));
        queue.offer(request("two"));
        queue.offer(request("three"));

        assertEquals(2, queue.size());
        assertEquals("two", queue.poll().title());
        assertEquals("three", queue.poll().title());
    }

    @Test
    void peekLeavesTheHeadInPlaceSoADeniedClaimCanReplayIt() {
        TitleQueue queue = new TitleQueue(4);
        queue.offer(request("head"));

        assertEquals("head", queue.peek().title());
        assertEquals(1, queue.size());
        assertEquals("head", queue.poll().title());
        assertEquals(0, queue.size());
    }

    @Test
    void clearDropsEverythingPending() {
        TitleQueue queue = new TitleQueue(4);
        queue.offer(request("one"));
        queue.offer(request("two"));
        queue.clear();

        assertEquals(0, queue.size());
        assertNull(queue.peek());
    }

    @Test
    void aHeadNothingWouldGrantIsDroppedOnceItsOwnWindowHasPassed() {
        java.util.concurrent.atomic.AtomicLong clock = new java.util.concurrent.atomic.AtomicLong(1_000L);
        TitleQueue queue = new TitleQueue(4, clock::get);
        queue.offer(request("stuck"));
        queue.offer(request("next"));

        queue.dropStaleHead();
        assertEquals("stuck", queue.peek().title());

        clock.addAndGet(request("stuck").ttlMillis() + 1L);
        queue.dropStaleHead();

        assertEquals("next", queue.peek().title());
    }

    @Test
    void everythingQueuedForAPurposeThatStoodDownIsDropped() {
        TitleQueue queue = new TitleQueue(4);
        queue.offer(new TitleQueue.TitleRequest("gloss:camera:letterbox", 10, "bars", "bars", 10, 600, 10));
        queue.offer(request("welcome"));

        queue.drop("gloss:camera:letterbox");

        assertEquals(1, queue.size());
        assertEquals("welcome", queue.peek().title());
    }

    private static TitleQueue.TitleRequest request(String title) {
        return new TitleQueue.TitleRequest("gloss:surface:a", 40, title, "", 10, 40, 10);
    }
}
