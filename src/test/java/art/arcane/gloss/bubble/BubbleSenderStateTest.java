package art.arcane.gloss.bubble;

import art.arcane.gloss.bubble.ChatBubblesService.BubbleRecord;
import art.arcane.gloss.bubble.ChatBubblesService.SenderState;
import art.arcane.gloss.particle.ParticleText;
import org.bukkit.util.Vector;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class BubbleSenderStateTest {
    private static BubbleRecord record(boolean followPlayer, long expiresAtMs) {
        return record(followPlayer, expiresAtMs, 1);
    }

    private static BubbleRecord record(boolean followPlayer, long expiresAtMs, int lineCount) {
        return record(followPlayer, expiresAtMs, lineCount, "&7");
    }

    private static BubbleRecord record(boolean followPlayer, long expiresAtMs, int lineCount, String prefix) {
        return new BubbleRecord(null, null, new Vector(), BubbleMotionPlan.compile(BubbleStyleDoc.DEFAULTS.motion()),
            BubbleShimmerPlan.compile(BubbleStyleDoc.DEFAULTS.shimmer()), followPlayer, 0L, 5000L, expiresAtMs,
            lineCount, 0.5D, prefix, "message", 32,
            List.of("message"), new ParticleText.Rendered("message", List.of()));
    }

    private static int indexOfViaScan(SenderState state, BubbleRecord record) {
        return Math.max(state.live.indexOf(record), 0);
    }

    @Test
    void storedLineIndexMatchesTheListScanItReplaces() {
        SenderState state = new SenderState();
        List<BubbleRecord> records = List.of(record(false, 0L), record(false, 0L), record(false, 0L));
        records.forEach(state::add);

        for (BubbleRecord entry : records) {
            assertEquals(indexOfViaScan(state, entry), entry.lineIndex);
        }
    }

    @Test
    void removingTheOldestLineShiftsEveryRemainingIndexDownByOne() {
        SenderState state = new SenderState();
        BubbleRecord oldest = record(false, 0L);
        BubbleRecord middle = record(false, 0L);
        BubbleRecord newest = record(false, 0L);
        state.add(oldest);
        state.add(middle);
        state.add(newest);

        state.remove(oldest);

        assertEquals(0, middle.lineIndex);
        assertEquals(1, newest.lineIndex);
        assertEquals(indexOfViaScan(state, middle), middle.lineIndex);
        assertEquals(indexOfViaScan(state, newest), newest.lineIndex);
    }

    @Test
    void removingAMiddleLineReindexesOnlyTheLinesBehindIt() {
        SenderState state = new SenderState();
        BubbleRecord first = record(false, 0L);
        BubbleRecord second = record(false, 0L);
        BubbleRecord third = record(false, 0L);
        state.add(first);
        state.add(second);
        state.add(third);

        state.remove(second);

        assertEquals(0, first.lineIndex);
        assertEquals(1, third.lineIndex);
    }

    @Test
    void anUntrackedRecordFallsBackToTheStackTopExactlyLikeTheClampedScan() {
        SenderState state = new SenderState();
        BubbleRecord tracked = record(false, 0L);
        BubbleRecord dropped = record(false, 0L);
        state.add(tracked);
        state.add(dropped);

        state.remove(dropped);

        assertEquals(0, dropped.lineIndex);
        assertEquals(indexOfViaScan(state, dropped), dropped.lineIndex);
    }

    @Test
    void removingAnUnknownRecordChangesNothing() {
        SenderState state = new SenderState();
        BubbleRecord tracked = record(true, 0L);
        state.add(tracked);

        state.remove(record(true, 0L));

        assertEquals(1, state.live.size());
        assertEquals(0, tracked.lineIndex);
    }

    @Test
    void ownerBoundRefreshUpdatesOnlyTheFollowingEyeAndDynamicPrefix() {
        SenderState state = new SenderState();
        BubbleRecord following = record(true, 0L, 1, "&7");
        BubbleRecord dynamicAnchored = record(false, 0L, 1, "{{ time.ticks }}");
        ChatBubblesService.EyePoint first = new ChatBubblesService.EyePoint(
            null, 1.0D, 2.0D, 3.0D, 4.0F, 5.0F);
        ChatBubblesService.EyePoint second = new ChatBubblesService.EyePoint(
            null, 6.0D, 7.0D, 8.0D, 9.0F, 10.0F);
        AtomicInteger refreshes = new AtomicInteger();

        ChatBubblesService.refreshOwnerState(state, following, first, refreshes::incrementAndGet);
        assertEquals(first, state.lastEye);
        assertEquals(0, refreshes.get());

        ChatBubblesService.refreshOwnerState(state, dynamicAnchored, second, refreshes::incrementAndGet);
        assertEquals(first, state.lastEye,
            "an anchored record must not overwrite the shared following eye");
        assertEquals(1, refreshes.get());
    }

    @Test
    void aStateWhoseLinesAllExpiredReportsEmptySoTheSenderEntryCanBePruned() {
        SenderState state = new SenderState();
        BubbleRecord only = record(true, 500L);
        state.add(only);

        assertTrue(only.expiresAtMs <= 500L);
        state.remove(only);

        assertTrue(state.live.isEmpty());
    }

    @Test
    void stackedLineCountIncludesEveryLineInNewerMessageBlocks() {
        SenderState state = new SenderState();
        BubbleRecord oldest = record(false, 0L, 3);
        BubbleRecord middle = record(false, 0L, 2);
        BubbleRecord newest = record(false, 0L, 1);
        state.add(oldest);
        state.add(middle);
        state.add(newest);

        assertEquals(3, state.stackedLineCount(oldest));
        assertEquals(1, state.stackedLineCount(middle));
        assertEquals(0, state.stackedLineCount(newest));
    }

    @Test
    void addingAtThePerSenderCeilingRetiresTheOldestRecord() {
        SenderState state = new SenderState();
        BubbleRecord first = record(false, 0L);
        BubbleRecord second = record(false, 0L);
        BubbleRecord third = record(false, 0L);
        BubbleRecord fourth = record(false, 0L);
        state.add(first);
        state.add(second);
        state.add(third);

        assertEquals(first, state.addAtLimit(fourth, 3, null));
        assertEquals(List.of(second, third, fourth), state.live);
        assertEquals(0, second.lineIndex);
        assertEquals(1, third.lineIndex);
        assertEquals(2, fourth.lineIndex);
    }

    @Test
    void concurrentExpiryAndPublicationNeverOrphanTheNewRecord() throws Exception {
        ExecutorService executor = Executors.newFixedThreadPool(2);
        try {
            for (int iteration = 0; iteration < 200; iteration++) {
                UUID senderId = UUID.randomUUID();
                ConcurrentMap<UUID, SenderState> states = new ConcurrentHashMap<>();
                BubbleRecord expired = record(false, 0L);
                BubbleRecord replacement = record(false, 1000L);
                ChatBubblesService.publishBubble(states, senderId, null, expired, 4);
                CountDownLatch ready = new CountDownLatch(2);
                CountDownLatch start = new CountDownLatch(1);
                Future<?> expiry = executor.submit(() -> {
                    ready.countDown();
                    await(start);
                    ChatBubblesService.removeBubble(states, senderId, expired);
                });
                Future<?> publication = executor.submit(() -> {
                    ready.countDown();
                    await(start);
                    ChatBubblesService.publishBubble(states, senderId, null, replacement, 4);
                });

                assertTrue(ready.await(5L, TimeUnit.SECONDS));
                start.countDown();
                expiry.get(5L, TimeUnit.SECONDS);
                publication.get(5L, TimeUnit.SECONDS);

                SenderState current = states.get(senderId);
                assertNotNull(current);
                assertEquals(List.of(replacement), current.live);
            }
        } finally {
            executor.shutdownNow();
        }
    }

    @Test
    void concurrentDuplicateRemovalCannotRemoveTheNextRecord() throws Exception {
        SenderState state = new SenderState();
        BubbleRecord first = record(true, 0L);
        BubbleRecord second = record(true, 0L);
        state.add(first);
        state.add(second);
        ExecutorService executor = Executors.newFixedThreadPool(2);
        CountDownLatch start = new CountDownLatch(1);
        try {
            Future<Boolean> left = executor.submit(() -> {
                await(start);
                return state.remove(first);
            });
            Future<Boolean> right = executor.submit(() -> {
                await(start);
                return state.remove(first);
            });
            start.countDown();

            boolean leftRemoved = left.get(5L, TimeUnit.SECONDS);
            boolean rightRemoved = right.get(5L, TimeUnit.SECONDS);
            assertTrue(leftRemoved ^ rightRemoved);
            assertEquals(List.of(second), state.live);
        } finally {
            executor.shutdownNow();
        }
    }

    @Test
    void retirementCanReleaseCapacityOnlyOnce() {
        BubbleRecord record = record(false, 0L);

        assertTrue(record.claimRetirement());
        assertFalse(record.claimRetirement());
    }

    @Test
    void expiryAccountingSweepRunsAtCoarseOneSecondCadence() {
        assertEquals(20, ChatBubblesService.EXPIRY_SWEEP_INTERVAL_TICKS);
    }

    private static void await(CountDownLatch latch) {
        try {
            latch.await();
        } catch (InterruptedException interruption) {
            Thread.currentThread().interrupt();
            throw new AssertionError(interruption);
        }
    }
}
