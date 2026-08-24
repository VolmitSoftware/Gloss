package art.arcane.gloss.tab;

import org.junit.jupiter.api.Test;

import java.util.HashSet;
import java.util.Set;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class TablistDeliveryRecoveryTest {
    @Test
    void unchangedHeaderFooterEventuallyResendsWithinTheCycleBudget() {
        TablistService.HeaderFooter rendered = new TablistService.HeaderFooter("header", "footer");
        TablistService.AppliedHeaderFooter applied =
            new TablistService.AppliedHeaderFooter(rendered, 100L);
        TablistService.HeaderFooterHeartbeatCycle cycle =
            new TablistService.HeaderFooterHeartbeatCycle(1);

        assertFalse(TablistService.shouldSendHeaderFooter(rendered, applied, 99L, cycle));
        assertTrue(TablistService.shouldSendHeaderFooter(rendered, applied, 100L, cycle));
        assertFalse(TablistService.shouldSendHeaderFooter(rendered, applied, 101L, cycle));
        assertEquals(0, cycle.remaining());
    }

    @Test
    void changedHeaderFooterNeverWaitsForAHeartbeatPermit() {
        TablistService.HeaderFooter previousContent =
            new TablistService.HeaderFooter("old", "footer");
        TablistService.AppliedHeaderFooter applied =
            new TablistService.AppliedHeaderFooter(previousContent, Long.MAX_VALUE);
        TablistService.HeaderFooter changed =
            new TablistService.HeaderFooter("new", "footer");

        assertTrue(TablistService.shouldSendHeaderFooter(changed, applied, 0L, null));
        assertTrue(TablistService.shouldSendHeaderFooter(changed, null, 0L, null));
    }

    @Test
    void firstHeartbeatDeadlinesAreDeterministicallyStaggered() {
        Set<Long> offsets = new HashSet<>();
        for (int player = 0; player < 1_000; player++) {
            UUID uuid = new UUID(0x9E3779B97F4A7C15L * player, player);
            long first = TablistService.initialHeartbeatOffsetNanos(uuid);
            long second = TablistService.initialHeartbeatOffsetNanos(uuid);
            assertTrue(first >= 0L);
            assertEquals(first, second);
            offsets.add(first);
        }

        assertTrue(offsets.size() > 990,
            "one thousand players must not converge on one anti-entropy refresh tick");
    }

    @Test
    void oneThousandDuePlayersCannotExceedTheHeartbeatCycleLimit() {
        TablistService.HeaderFooter rendered = new TablistService.HeaderFooter("header", "footer");
        TablistService.AppliedHeaderFooter applied =
            new TablistService.AppliedHeaderFooter(rendered, 0L);
        TablistService.HeaderFooterHeartbeatCycle cycle =
            new TablistService.HeaderFooterHeartbeatCycle(64);
        int admitted = 0;

        for (int player = 0; player < 1_000; player++) {
            if (TablistService.shouldSendHeaderFooter(rendered, applied, 1L, cycle)) {
                admitted++;
            }
        }

        assertEquals(64, admitted);
        assertEquals(0, cycle.remaining());
    }

    @Test
    void serverSideListNameOverwriteInvalidatesTheMemo() {
        assertFalse(TablistService.listNameNeedsApply("Admin Alex", "Admin Alex", "Admin Alex"));
        assertTrue(TablistService.listNameNeedsApply("Admin Alex", "Admin Alex", "Alex"));
        assertTrue(TablistService.listNameNeedsApply("Admin Alex", "Alex", "Alex"));
    }

    @Test
    void queuedPlayerRefreshesCollapseToTheLatestFullRequest() {
        TablistService.PlayerApplyQueue queue = new TablistService.PlayerApplyQueue();
        TablistService.ApplyRequest fast =
            new TablistService.ApplyRequest(null, 1L, TablistService.APPLY_FAST, null);
        TablistService.HeaderFooterHeartbeatCycle heartbeat =
            new TablistService.HeaderFooterHeartbeatCycle(64);
        TablistService.ApplyRequest full =
            new TablistService.ApplyRequest(null, 1L, TablistService.APPLY_FULL, heartbeat);

        assertTrue(queue.offer(fast));
        for (int refresh = 0; refresh < 1_000; refresh++) {
            assertFalse(queue.offer(fast));
        }
        assertFalse(queue.offer(full));

        TablistService.ApplyRequest merged = queue.next();
        assertEquals(TablistService.APPLY_FULL, merged.mode());
        assertEquals(heartbeat, merged.heartbeatCycle());
        assertNull(queue.next());
        assertTrue(queue.offer(fast), "an idle queue must admit the next owner-thread dispatch");
    }

    @Test
    void aNewDriverEpochReplacesStaleQueuedWork() {
        TablistService.PlayerApplyQueue queue = new TablistService.PlayerApplyQueue();
        TablistService.ApplyRequest old =
            new TablistService.ApplyRequest(null, 7L, TablistService.APPLY_FULL, null);
        TablistService.ApplyRequest current =
            new TablistService.ApplyRequest(null, 8L, TablistService.APPLY_FAST, null);

        assertTrue(queue.offer(old));
        assertFalse(queue.offer(current));
        assertEquals(8L, queue.next().epoch());
    }
}
