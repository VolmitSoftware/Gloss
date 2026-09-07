package art.arcane.gloss.drop;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The audience memo exists to keep hide/show off the per-poll path without ever leaving a viewer on
 * a decision that was never applied to them. The condition itself is evaluated on the viewer's own
 * thread, so the memo is written after the mutation lands, not before it is scheduled.
 */
class RealDropAudienceMemoTest {
    private static final UUID VIEWER = UUID.fromString("00000000-0000-0000-0000-0000000000c1");
    private static final UUID OTHER = UUID.fromString("00000000-0000-0000-0000-0000000000c2");

    @Test
    void aViewerWithNoEntryIsEvaluatedAndDispatchedAtOnce() {
        RealDropAudienceMemo memo = new RealDropAudienceMemo();

        assertTrue(memo.refreshRequired(VIEWER, 1, 100L));
        assertTrue(memo.changed(VIEWER, true, 1));
        assertNull(memo.visibility(VIEWER));
    }

    @Test
    void anUnchangedDecisionIsReEvaluatedOncePerRefreshWindowAndDispatchedNever() {
        RealDropAudienceMemo memo = new RealDropAudienceMemo();
        memo.applied(VIEWER, true, 1, 100L);

        assertFalse(memo.refreshRequired(VIEWER, 1, 100L + RealDropAudienceMemo.REFRESH_TICKS - 1));
        assertTrue(memo.refreshRequired(VIEWER, 1, 100L + RealDropAudienceMemo.REFRESH_TICKS));
        assertFalse(memo.changed(VIEWER, true, 1));
        assertTrue(memo.changed(VIEWER, false, 1));
        assertEquals(Boolean.TRUE, memo.visibility(VIEWER));
    }

    @Test
    void aChangedDisplaySetForcesTheEvaluationAndTheDispatch() {
        RealDropAudienceMemo memo = new RealDropAudienceMemo();
        memo.applied(VIEWER, true, 1, 100L);

        assertTrue(memo.refreshRequired(VIEWER, 2, 101L));
        assertTrue(memo.changed(VIEWER, true, 2));
    }

    @Test
    void aViewerTaskThatBailsLeavesNoEntryToInheritOnRelog() {
        RealDropAudienceMemo memo = new RealDropAudienceMemo();

        assertTrue(memo.refreshRequired(VIEWER, 1, 100L));
        assertNull(memo.visibility(VIEWER));
        assertTrue(memo.refreshRequired(VIEWER, 1, 101L));
        assertEquals(List.of(), List.copyOf(memo.viewers()));
    }

    @Test
    void aQuitViewerIsFullyDispatchedAgainWhenItComesBack() {
        RealDropAudienceMemo memo = new RealDropAudienceMemo();
        memo.applied(VIEWER, true, 1, 100L);
        memo.applied(OTHER, true, 1, 100L);

        memo.forget(VIEWER);

        assertTrue(memo.refreshRequired(VIEWER, 1, 101L));
        assertTrue(memo.changed(VIEWER, true, 1));
        assertNull(memo.visibility(VIEWER));
        assertEquals(List.of(OTHER), List.copyOf(memo.viewers()));
        assertFalse(memo.refreshRequired(OTHER, 1, 101L));
    }

    @Test
    void restoreWalksEveryViewerTheDropEverAppliedToAndThenForgetsThem() {
        RealDropAudienceMemo memo = new RealDropAudienceMemo();
        memo.applied(VIEWER, true, 1, 100L);
        memo.applied(OTHER, false, 1, 100L);

        assertEquals(2, memo.viewers().size());
        memo.clear();
        assertTrue(memo.isEmpty());
        assertTrue(memo.refreshRequired(VIEWER, 1, 101L));
    }
}
