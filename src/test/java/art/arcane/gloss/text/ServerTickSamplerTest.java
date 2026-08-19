package art.arcane.gloss.text;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class ServerTickSamplerTest {
    @Test
    void samplesTwentyTicksAndSmoothsLag() {
        assertEquals(20.0D, ServerTickSampler.smooth(20.0D, 1_000_000_000L), 0.0001D);
        assertEquals(17.5D, ServerTickSampler.smooth(20.0D, 2_000_000_000L), 0.0001D);
    }

    @Test
    void ignoresInvalidElapsedTime() {
        assertEquals(17.5D, ServerTickSampler.smooth(17.5D, 0L), 0.0001D);
    }
}
