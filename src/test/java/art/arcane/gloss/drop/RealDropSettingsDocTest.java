package art.arcane.gloss.drop;

import art.arcane.gloss.GlossConfig;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class RealDropSettingsDocTest {
    @Test
    void absentSectionsResolveToTheShippedWebEditableDefaults() {
        RealDropSettingsDoc parsed = RealDropSettingsDoc.parse("default.json", """
            {"schemaVersion":1,"revision":1}
            """);
        GlossConfig.RealDrops config = parsed.toConfig(true);

        assertTrue(config.enabled());
        assertEquals(2, config.limits().updateIntervalTicks());
        assertEquals(1.35F, config.motion().speedMultiplier());
        assertEquals("NATURAL", config.landing().mode());
        assertTrue(config.motion().tumble());
        assertTrue(config.motion().changeOnBounce());
        assertTrue(config.labels().seeThrough());
        assertEquals(List.of("BEDROCK", "BARRIER"), config.filters().materialBlacklist());
    }

    @Test
    void documentValuesClampAndNormalizeAtTheRuntimeBoundary() {
        RealDropSettingsDoc parsed = RealDropSettingsDoc.parse("default.json", """
            {
              "schemaVersion": 1,
              "revision": 8,
              "limits": {
                "updateIntervalTicks": 0,
                "settledPollIntervalTicks": 999,
                "maxVisualsPerStack": 99,
                "maxVisualsPerChunk": 1,
                "viewRange": 999,
                "spread": -2
              },
              "motion": {
                "tumble": false,
                "speedMultiplier": 99,
                "degreesPerSecondX": -9999,
                "degreesPerSecondY": 9999,
                "degreesPerSecondZ": 9999,
                "variance": 8,
                "changeOnBounce": false
              },
              "landing": {
                "mode": "sideways",
                "tiltDegrees": 99,
                "randomYaw": false,
                "transitionTicks": 99
              },
              "labels": {
                "enabled": false,
                "yOffset": -1,
                "scale": 99,
                "viewRange": 1,
                "billboard": "diagonal",
                "seeThrough": false,
                "backgroundRed": -1,
                "backgroundGreen": 999,
                "backgroundBlue": 999,
                "backgroundAlpha": -1
              },
              "filters": {
                "disabledWorlds": [" world ", ""],
                "materialBlacklist": [" stone ", ""],
                "onlyPlayerDrops": true
              }
            }
            """);
        GlossConfig.RealDrops config = parsed.toConfig(false);

        assertFalse(config.enabled());
        assertEquals(1, config.limits().updateIntervalTicks());
        assertEquals(200, config.limits().settledPollIntervalTicks());
        assertEquals(5, config.limits().maxVisualsPerStack());
        assertEquals(8, config.limits().maxVisualsPerChunk());
        assertEquals(128.0F, config.limits().viewRange());
        assertEquals(0.0F, config.limits().spread());
        assertFalse(config.motion().tumble());
        assertEquals(4.0F, config.motion().speedMultiplier());
        assertEquals(-1440.0F, config.motion().degreesPerSecondX());
        assertEquals(1440.0F, config.motion().degreesPerSecondY());
        assertEquals(1.0F, config.motion().variance());
        assertFalse(config.motion().changeOnBounce());
        assertEquals("NATURAL", config.landing().mode());
        assertEquals(45.0F, config.landing().tiltDegrees());
        assertFalse(config.landing().randomYaw());
        assertEquals(20, config.landing().transitionTicks());
        assertFalse(config.labels().enabled());
        assertFalse(config.labels().seeThrough());
        assertEquals(0, config.labels().backgroundRed());
        assertEquals(255, config.labels().backgroundGreen());
        assertEquals(0, config.labels().backgroundAlpha());
        assertEquals(List.of("world"), config.filters().disabledWorlds());
        assertEquals(List.of("stone"), config.filters().materialBlacklist());
        assertTrue(config.filters().onlyPlayerDrops());
    }
}
