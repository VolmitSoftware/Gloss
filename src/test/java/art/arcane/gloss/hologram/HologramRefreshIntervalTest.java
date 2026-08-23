package art.arcane.gloss.hologram;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;

class HologramRefreshIntervalTest {
    @Test
    void ordinaryDynamicValuesKeepConfiguredCadence() {
        assertEquals(10, HologramService.refreshIntervalTicks(
            List.of("{{ player.name }}", "{{ fixed(server.tps, 1) }}"), 10));
    }

    @Test
    void animatedTextUsesEveryTick() {
        assertEquals(1, HologramService.refreshIntervalTicks(
            List.of("{{ wave('LIVE', ['&a', '&7'], time.ticks) }}"), 10));
        assertEquals(1, HologramService.refreshIntervalTicks(List.of("|animation.rainbow|"), 10));
    }
}
