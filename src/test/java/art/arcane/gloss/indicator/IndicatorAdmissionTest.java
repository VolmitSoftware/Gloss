package art.arcane.gloss.indicator;

import org.bukkit.Location;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Supplier;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

class IndicatorAdmissionTest {
    private static final DamageIndicatorEventSnapshot SNAPSHOT = new DamageIndicatorEventSnapshot(
        true, "entity_attack", 8.0D, false, true, "player",
        new DamageIndicatorEventSnapshot.EntityState(Map.of()));

    @Test
    void motionOffsetsNeverMutateTheCapturedOrigin() {
        Location origin = new Location(null, 10.0D, 20.0D, 30.0D);

        Location first = DamageIndicatorsService.offsetFrom(origin, 1.0D, 2.0D, 3.0D);
        Location second = DamageIndicatorsService.offsetFrom(origin, 1.0D, 4.0D, 3.0D);

        assertEquals(10.0D, origin.getX());
        assertEquals(20.0D, origin.getY());
        assertEquals(30.0D, origin.getZ());
        assertEquals(11.0D, first.getX());
        assertEquals(22.0D, first.getY());
        assertEquals(11.0D, second.getX());
        assertEquals(24.0D, second.getY());
    }

    static Stream<Arguments> limits() {
        return Stream.of(
            Arguments.of("defaultRateAndLifetimeAllowOneExpectedLifetimeWindow", 40, 3000L, 120),
            Arguments.of("partialSecondsRoundUp", 41, 3001L, 124),
            Arguments.of("configuredExtremesCannotExceedTheHardCeiling", 1000, 30000L,
                DamageIndicatorsService.MAX_LIVE_INDICATORS),
            Arguments.of("invalidInputsStillProduceAPositiveLimit", 0, 0L, 1)
        );
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("limits")
    void liveLimitBoundsTheFleet(String label, int rate, long lifetime, int expected) {
        assertEquals(expected, DamageIndicatorsService.liveLimit(rate, lifetime), label);
    }

    @Test
    void theEventSnapshotIsBuiltOnlyAfterTheAdmissionGates() {
        DamageIndicatorsService service = new DamageIndicatorsService(null);
        UUID entity = UUID.randomUUID();
        AtomicInteger built = new AtomicInteger();
        Supplier<DamageIndicatorEventSnapshot> supplier = () -> {
            built.incrementAndGet();
            return SNAPSHOT;
        };

        assertSame(SNAPSHOT, service.admit(entity, 40, 0L, supplier));
        assertNull(service.admit(entity, 40, 10L, supplier),
            "a debounced event must be rejected before the snapshot is built");
        assertEquals(1, built.get(), "a rejected event must not allocate a snapshot");
    }

    @Test
    void theDebounceGateIsPerEntity() {
        DamageIndicatorsService service = new DamageIndicatorsService(null);
        AtomicInteger built = new AtomicInteger();
        Supplier<DamageIndicatorEventSnapshot> supplier = () -> {
            built.incrementAndGet();
            return SNAPSHOT;
        };

        assertSame(SNAPSHOT, service.admit(UUID.randomUUID(), 40, 0L, supplier));
        assertSame(SNAPSHOT, service.admit(UUID.randomUUID(), 40, 0L, supplier));

        assertEquals(2, built.get());
    }

    @Test
    void theDebounceWindowReopensAfterItExpires() {
        DamageIndicatorsService service = new DamageIndicatorsService(null);
        UUID entity = UUID.randomUUID();
        Supplier<DamageIndicatorEventSnapshot> supplier = () -> SNAPSHOT;

        assertSame(SNAPSHOT, service.admit(entity, 40, 0L, supplier));
        assertNull(service.admit(entity, 40, 149L, supplier));
        assertSame(SNAPSHOT, service.admit(entity, 40, 150L, supplier));
    }

    @Test
    void lifecycleEpochRejectsAConcurrentDisableOrReload() {
        assertTrue(DamageIndicatorsService.spawnStillCurrent(true, 8L, 8L, true));
        assertFalse(DamageIndicatorsService.spawnStillCurrent(false, 8L, 8L, true));
        assertFalse(DamageIndicatorsService.spawnStillCurrent(true, 9L, 8L, true));
        assertFalse(DamageIndicatorsService.spawnStillCurrent(true, 8L, 8L, false));
    }
}
