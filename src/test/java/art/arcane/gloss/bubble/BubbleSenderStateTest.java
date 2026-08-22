package art.arcane.gloss.bubble;

import art.arcane.gloss.bubble.ChatBubblesService.BubbleRecord;
import art.arcane.gloss.bubble.ChatBubblesService.SenderState;
import org.bukkit.util.Vector;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class BubbleSenderStateTest {
    private static BubbleRecord record(boolean followPlayer, long expiresAtMs) {
        return record(followPlayer, expiresAtMs, 1);
    }

    private static BubbleRecord record(boolean followPlayer, long expiresAtMs, int lineCount) {
        return new BubbleRecord(null, null, new Vector(), BubbleMotionPlan.compile(BubbleStyleDoc.DEFAULTS.motion()),
            BubbleShimmerPlan.compile(BubbleStyleDoc.DEFAULTS.shimmer()), followPlayer, 0L, 5000L, expiresAtMs,
            lineCount, 0.5D, "&7", "message", 32,
            List.of("message"));
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
        assertEquals(1, state.followCount.get());
        assertEquals(0, tracked.lineIndex);
    }

    @Test
    void followCountTracksOnlyFollowingLines() {
        SenderState state = new SenderState();
        BubbleRecord following = record(true, 0L);
        BubbleRecord anchored = record(false, 0L);
        state.add(following);
        state.add(anchored);

        assertEquals(1, state.followCount.get());

        state.remove(anchored);
        assertEquals(1, state.followCount.get());

        state.remove(following);
        assertEquals(0, state.followCount.get());
    }

    @Test
    void aStateWhoseLinesAllExpiredReportsEmptySoTheSenderEntryCanBePruned() {
        SenderState state = new SenderState();
        BubbleRecord only = record(true, 500L);
        state.add(only);

        assertTrue(only.expiresAtMs <= 500L);
        state.remove(only);

        assertTrue(state.live.isEmpty());
        assertEquals(0, state.followCount.get());
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
}
