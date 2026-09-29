package art.arcane.gloss.service;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class GlossTelemetryTest {
    @BeforeEach
    void resetBefore() {
        GlossTelemetry.clear();
    }

    @AfterEach
    void resetAfter() {
        GlossTelemetry.clear();
    }

    @Test
    void ratesComputeOverTheElapsedWindow() {
        long start = 10_000L;
        assertEquals(0D, GlossTelemetry.packetsPerSecond(start));

        GlossTelemetry.countPackets(500L);
        GlossTelemetry.countSpawnChurn();
        GlossTelemetry.countSpawnChurn();
        GlossTelemetry.countPreviewRefresh();
        GlossTelemetry.addTickNanos(4_000_000L);

        long later = start + 2_000L;
        assertEquals(250D, GlossTelemetry.packetsPerSecond(later));
        assertEquals(1D, GlossTelemetry.spawnsPerSecond(later));
        assertEquals(0.5D, GlossTelemetry.previewRefreshPerSecond(later));
        assertEquals(2D, GlossTelemetry.tickMsPerSecond(later));
    }

    @Test
    void midWindowReadsServeThePreviousRateUntilTheWindowRolls() {
        long start = 50_000L;
        GlossTelemetry.packetsPerSecond(start);

        GlossTelemetry.countPackets(100L);
        assertEquals(100D, GlossTelemetry.packetsPerSecond(start + 1_000L));

        GlossTelemetry.countPackets(999L);
        assertEquals(100D, GlossTelemetry.packetsPerSecond(start + 1_500L));
        assertEquals(999D, GlossTelemetry.packetsPerSecond(start + 2_000L));
    }

    @Test
    void nonPositiveCountsAreIgnored() {
        long start = 70_000L;
        GlossTelemetry.packetsPerSecond(start);

        GlossTelemetry.countPackets(0L);
        GlossTelemetry.countPackets(-5L);
        GlossTelemetry.addTickNanos(0L);
        GlossTelemetry.addTickNanos(-1L);

        assertEquals(0D, GlossTelemetry.packetsPerSecond(start + 2_000L));
        assertEquals(0D, GlossTelemetry.tickMsPerSecond(start + 2_000L));
    }

    @Test
    void clearResetsCountersRatesAndTheWindow() {
        long start = 90_000L;
        GlossTelemetry.packetsPerSecond(start);
        GlossTelemetry.countPackets(10L);
        GlossTelemetry.packetsPerSecond(start + 1_000L);

        GlossTelemetry.clear();

        assertEquals(0D, GlossTelemetry.packetsPerSecond(start + 5_000L));
        GlossTelemetry.countPackets(30L);
        assertEquals(10D, GlossTelemetry.packetsPerSecond(start + 8_000L));
    }
}
