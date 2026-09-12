package art.arcane.gloss.sky;

import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

class SkyFadeTest {
    @Test
    void aZeroFadeJumpsStraightToTheTarget() {
        Assertions.assertEquals(18000L, SkyFade.timeAt(0L, 18000L, 0, 0));
    }

    @Test
    void theMidpointOfAFadeIsHalfwayAlongTheShorterArc() {
        Assertions.assertEquals(6000L, SkyFade.timeAt(0L, 12000L, 40, 20));
        Assertions.assertEquals(21000L, SkyFade.timeAt(0L, 18000L, 40, 20));
    }

    @Test
    void aFadeEndsExactlyOnTheTarget() {
        Assertions.assertEquals(18000L, SkyFade.timeAt(0L, 18000L, 40, 40));
    }

    @Test
    void elapsedTimePastTheFadeStaysOnTheTarget() {
        Assertions.assertEquals(18000L, SkyFade.timeAt(0L, 18000L, 40, 400));
    }

    @Test
    void aFadeTakesTheShortWayRoundTheDayClock() {
        Assertions.assertEquals(23000L, SkyFade.timeAt(23000L, 1000L, 40, 0));
        Assertions.assertEquals(0L, SkyFade.timeAt(23000L, 1000L, 40, 20));
        Assertions.assertEquals(1000L, SkyFade.timeAt(23000L, 1000L, 40, 40));
    }

    @Test
    void aFadeBackwardsAlsoTakesTheShortWay() {
        Assertions.assertEquals(0L, SkyFade.timeAt(1000L, 23000L, 40, 20));
    }

    @Test
    void everyFadedTimeStaysInsideTheDayClock() {
        for (int elapsed = 0; elapsed <= 40; elapsed++) {
            long value = SkyFade.timeAt(23000L, 1000L, 40, elapsed);
            Assertions.assertTrue(value >= 0L && value < SkyFade.DAY_TICKS, "out of range: " + value);
        }
    }
}
