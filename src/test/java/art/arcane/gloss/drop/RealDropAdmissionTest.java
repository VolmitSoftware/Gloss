package art.arcane.gloss.drop;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class RealDropAdmissionTest {
    @Test
    void presentationCountHasAFixedServerWideCeiling() {
        assertEquals(2048, RealDropService.MAX_ACTIVE_PRESENTATIONS);
    }

    @Test
    void generationGuardRejectsConcurrentDisableReloadAndFeatureChanges() {
        assertTrue(RealDropService.presentationStillCurrent(true, 4L, 4L, true));
        assertFalse(RealDropService.presentationStillCurrent(false, 4L, 4L, true));
        assertFalse(RealDropService.presentationStillCurrent(true, 5L, 4L, true));
        assertFalse(RealDropService.presentationStillCurrent(true, 4L, 4L, false));
    }
}
