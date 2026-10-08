package art.arcane.gloss.surface;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;

class SurfaceQueueTest {
    private static final SurfaceDispatchPolicy FIFO = new SurfaceDispatchPolicy("queue", "never", 2, "reject", 0, "none", 100);

    @Test
    void queuedDurationStartsAtActivationAndPollingDoesNotExtendIt() {
        SurfaceQueue<String> queue = new SurfaceQueue<>();
        queue.offer(request("a", 0, FIFO), 0);
        assertEquals(SurfaceQueue.Outcome.QUEUED, queue.offer(request("b", 0, FIFO), 1));
        SurfaceQueue.Active<String> active = queue.advance(2);
        assertSame(active, queue.advance(9));
        assertEquals("b", queue.advance(10).request().value());
        assertEquals(20, queue.advance(15).endsAt());
        assertNull(queue.advance(20));
    }

    @Test
    void priorityAndExplicitPreemptionControlWhoCanInterrupt() {
        SurfaceQueue<String> queue = new SurfaceQueue<>();
        SurfaceDispatchPolicy policy = new SurfaceDispatchPolicy("queue", "higher", 2, "reject", 0, "none", 100);
        queue.offer(request("a", 5, policy), 0);
        assertEquals(SurfaceQueue.Outcome.QUEUED, queue.offer(request("equal", 5, policy), 1));
        assertEquals(SurfaceQueue.Outcome.STARTED, queue.offer(request("higher", 6, policy), 2));
        assertEquals("higher", queue.advance(2).request().value());
        assertEquals("equal", queue.advance(12).request().value());
    }

    @Test
    void overflowRejectAndDropOldestApplyToPendingRequests() {
        SurfaceQueue<String> queue = new SurfaceQueue<>();
        queue.offer(request("active", 0, FIFO), 0);
        queue.offer(request("one", 0, FIFO), 0);
        queue.offer(request("two", 0, FIFO), 0);
        assertEquals(SurfaceQueue.Outcome.REJECTED, queue.offer(request("rejected", 0, FIFO), 0));
        SurfaceDispatchPolicy dropOldest = new SurfaceDispatchPolicy("queue", "never", 2, "drop-oldest", 0, "none", 100);
        assertEquals(SurfaceQueue.Outcome.QUEUED, queue.offer(request("three", 0, dropOldest), 0));
        assertEquals(2, queue.pendingCount());
        assertEquals("two", queue.advance(10).request().value());
        assertEquals("three", queue.advance(20).request().value());
    }

    @Test
    void expiredQueuedRequestsNeverDisplayAndDoNotBlockTheNext() {
        SurfaceQueue<String> queue = new SurfaceQueue<>();
        queue.offer(request("active", 0, FIFO), 0);
        SurfaceDispatchPolicy shortExpiry = new SurfaceDispatchPolicy("queue", "never", 2, "reject", 0, "none", 2);
        queue.offer(request("expired", 0, shortExpiry), 1);
        queue.offer(request("next", 0, FIFO), 2);
        assertEquals("next", queue.advance(10).request().value());
    }

    @Test
    void cooldownAndDeduplicationIncludeActiveRequests() {
        SurfaceQueue<String> queue = new SurfaceQueue<>();
        SurfaceDispatchPolicy policy = new SurfaceDispatchPolicy("queue", "never", 2, "reject", 20, "content", 100);
        queue.offer(request("a", 0, policy), 0);
        assertEquals(SurfaceQueue.Outcome.COOLDOWN, queue.offer(request("a", 0, policy), 1));
        assertEquals(SurfaceQueue.Outcome.DUPLICATE, queue.offer(new SurfaceQueue.Request<>("other", "a", 0, 10, policy, "other"), 1));
        assertEquals(SurfaceQueue.Outcome.COOLDOWN, queue.offer(request("a", 0, policy), 15));
        assertEquals(SurfaceQueue.Outcome.STARTED, queue.offer(request("a", 0, policy), 20));
    }

    @Test
    void dropNeverQueuesOrPreemptsAndRemovalClearsOnlyMatchingPurpose() {
        SurfaceQueue<String> queue = new SurfaceQueue<>();
        queue.offer(request("active", 0, FIFO), 0);
        SurfaceDispatchPolicy drop = new SurfaceDispatchPolicy("drop", "always", 2, "reject", 0, "none", 100);
        assertEquals(SurfaceQueue.Outcome.REJECTED, queue.offer(request("urgent", 100, drop), 0));
        queue.offer(request("next", 0, FIFO), 0);
        queue.remove(request -> request.purpose().equals("active"));
        assertEquals("next", queue.advance(1).request().value());
        queue.clear();
        assertNull(queue.advance(2));
    }

    @Test
    void policiesRejectUnknownOrUnboundedOptions() {
        assertThrows(IllegalArgumentException.class, () -> new SurfaceDispatchPolicy("ignore", null, null, null, null, null, null));
        assertThrows(IllegalArgumentException.class, () -> new SurfaceDispatchPolicy(null, null, 257, null, null, null, null));
        assertThrows(IllegalArgumentException.class, () -> new SurfaceTrigger("interval", 0, null, null));
        assertThrows(IllegalArgumentException.class, () -> new SurfaceTrigger("world_change", null, null, null).requirePlatform(true));
        assertThrows(IllegalArgumentException.class, () -> new SurfaceTrigger("server_change", null, null, null).requirePlatform(false));
    }

    @Test
    void aCompositorDelayCannotRestartTheTitleLifetime() {
        CompositorDelivery.TitleTiming timing = new CompositorDelivery.TitleTiming(10, 40, 10);
        assertEquals(new CompositorDelivery.TitleTiming(5, 40, 10), CompositorDelivery.TitleTiming.remaining(timing, 5));
        assertEquals(new CompositorDelivery.TitleTiming(0, 25, 10), CompositorDelivery.TitleTiming.remaining(timing, 25));
        assertEquals(new CompositorDelivery.TitleTiming(0, 0, 5), CompositorDelivery.TitleTiming.remaining(timing, 55));
        assertEquals(new CompositorDelivery.TitleTiming(0, 0, 0), CompositorDelivery.TitleTiming.remaining(timing, 60));
    }

    private static SurfaceQueue.Request<String> request(String name, int priority, SurfaceDispatchPolicy policy) {
        return new SurfaceQueue.Request<>(name, name, priority, 10, policy, name);
    }
}
