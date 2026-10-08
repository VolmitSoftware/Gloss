package art.arcane.gloss.indicator;

import art.arcane.gloss.service.AdmissionBudget;
import org.junit.jupiter.api.Test;

import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.assertEquals;

class IndicatorSampleWindowTest {
    @Test
    void pendingSamplesCoalescePerTargetAndReleaseCapacityExactlyOnce() {
        IndicatorSampleWindow windows = new IndicatorSampleWindow();
        UUID first = UUID.randomUUID();
        UUID second = UUID.randomUUID();
        AdmissionBudget.Lease lease = windows.begin(first, 1);
        assertNotNull(lease);
        assertNull(windows.begin(first, 2));
        assertNull(windows.begin(second, 1));
        assertTrue(windows.finish(first, lease));
        assertFalse(windows.finish(first, lease));
        AdmissionBudget.Lease next = windows.begin(second, 1);
        assertNotNull(next);
        assertNull(windows.begin(first, 1));
        assertTrue(windows.finish(second, next));
    }

    @Test
    void reloadRetiresOutstandingWindowsWithoutRetiringTheirReplacements() {
        IndicatorSampleWindow windows = new IndicatorSampleWindow();
        UUID target = UUID.randomUUID();
        AdmissionBudget.Lease stale = windows.begin(target, 1);
        windows.clear();
        AdmissionBudget.Lease current = windows.begin(target, 1);
        assertNotNull(current);
        assertFalse(windows.finish(target, stale));
        assertNull(windows.begin(UUID.randomUUID(), 1));
        assertTrue(windows.finish(target, current));
    }

    @Test
    void aggregationAndAdmissionBoundsAreExplicit() {
        DamageIndicatorSettingsDoc.Limits defaults = DamageIndicatorSettingsDoc.DEFAULTS.limits();
        assertEquals(2, defaults.aggregationTicks());
        assertEquals(256, defaults.maxPendingSamples());
        DamageIndicatorSettingsDoc.Limits clamped = new DamageIndicatorSettingsDoc.Limits(
            null, null, null, null, null, null, 1000, 0);
        assertEquals(200, clamped.aggregationTicks());
        assertEquals(1, clamped.maxPendingSamples());
    }
}
