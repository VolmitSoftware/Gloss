package art.arcane.gloss.hologram;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class AnimatorLoopPolicyTest {
    @Test
    void floorDerivesFromMaxFps() {
        assertEquals(8L, AnimatorLoopPolicy.floorMillis(120));
        assertEquals(8L, AnimatorLoopPolicy.floorMillis(125));
        assertEquals(4L, AnimatorLoopPolicy.floorMillis(240));
        assertEquals(50L, AnimatorLoopPolicy.floorMillis(20));
        assertEquals(17L, AnimatorLoopPolicy.floorMillis(60));
    }

    @Test
    void floorClampsToMinimumFourMillis() {
        assertEquals(4L, AnimatorLoopPolicy.floorMillis(1000));
        assertEquals(4L, AnimatorLoopPolicy.floorMillis(500));
    }

    @Test
    void floorClampsToCeilingForVeryLowFps() {
        assertEquals(250L, AnimatorLoopPolicy.floorMillis(1));
        assertEquals(250L, AnimatorLoopPolicy.floorMillis(0));
        assertEquals(250L, AnimatorLoopPolicy.floorMillis(-5));
    }

    @Test
    void slowPassBacksOffByOneFloor() {
        assertEquals(16L, AnimatorLoopPolicy.nextIntervalMillis(8L, 11.0D, 8L));
        assertEquals(24L, AnimatorLoopPolicy.nextIntervalMillis(16L, 30.0D, 8L));
    }

    @Test
    void fastPassRecoversByOneFloor() {
        assertEquals(16L, AnimatorLoopPolicy.nextIntervalMillis(24L, 2.0D, 8L));
        assertEquals(8L, AnimatorLoopPolicy.nextIntervalMillis(16L, 2.0D, 8L));
    }

    @Test
    void passWithinBudgetFactorDoesNotBackOff() {
        assertEquals(8L, AnimatorLoopPolicy.nextIntervalMillis(8L, 10.0D, 8L));
        assertEquals(16L, AnimatorLoopPolicy.nextIntervalMillis(8L, 10.1D, 8L));
    }

    @Test
    void intervalClampsToFloorAndCeiling() {
        assertEquals(8L, AnimatorLoopPolicy.nextIntervalMillis(8L, 1.0D, 8L));
        assertEquals(250L, AnimatorLoopPolicy.nextIntervalMillis(250L, 10_000.0D, 8L));
        assertEquals(250L, AnimatorLoopPolicy.nextIntervalMillis(9_999L, 10_000.0D, 8L));
        assertEquals(8L, AnimatorLoopPolicy.nextIntervalMillis(1L, 1.0D, 8L));
    }

    @Test
    void sendIntervalScalesWithAudienceSize() {
        assertEquals(0L, AnimatorLoopPolicy.minSendIntervalMillis(1, 20_000));
        assertEquals(0L, AnimatorLoopPolicy.minSendIntervalMillis(19, 20_000));
        assertEquals(1L, AnimatorLoopPolicy.minSendIntervalMillis(20, 20_000));
        assertEquals(10L, AnimatorLoopPolicy.minSendIntervalMillis(200, 20_000));
        assertEquals(20L, AnimatorLoopPolicy.minSendIntervalMillis(2, 100));
        assertEquals(0L, AnimatorLoopPolicy.minSendIntervalMillis(0, 100));
        assertEquals(2000L, AnimatorLoopPolicy.minSendIntervalMillis(2, 0));
    }
}
