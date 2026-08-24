package art.arcane.gloss.indicator;

import org.bukkit.util.Vector;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class DamageIndicatorSettingsDocTest {
    @Test
    void envelopeOnlyDocumentUsesTheCompleteShippedDefaults() {
        DamageIndicatorSettingsDoc parsed = DamageIndicatorSettingsDoc.parse("default.json", """
            {"schemaVersion":1,"revision":1}
            """);

        assertEquals(DamageIndicatorSettingsDoc.DEFAULTS, parsed);
    }

    @Test
    void partialStylesInheritTheirOwnVariantDefaults() {
        DamageIndicatorSettingsDoc parsed = DamageIndicatorSettingsDoc.parse("default.json", """
            {
              "schemaVersion": 1,
              "revision": 1,
              "damage": {},
              "healing": {"enabled": false}
            }
            """);

        assertEquals(DamageIndicatorSettingsDoc.DEFAULTS.damage(), parsed.damage());
        assertEquals("&a&l{amount}", parsed.healing().format());
        assertEquals(0.45D, parsed.healing().motion().horizontalSpeed());
        assertEquals(1.1D, parsed.healing().presentation().endScale());
        assertEquals(false, parsed.healing().enabled());
    }

    @Test
    void numericValuesClampAtTheRuntimeBoundary() {
        DamageIndicatorSettingsDoc parsed = DamageIndicatorSettingsDoc.parse("default.json", """
            {
              "schemaVersion": 1,
              "revision": 1,
              "limits": {
                "maxPerSecond": 0,
                "lifetimeMs": 999999,
                "minimumDelta": -1,
                "decimals": 99
              },
              "damage": {
                "offset": [999, -999, 2],
                "motion": {
                  "horizontalSpeed": 99,
                  "verticalSpeed": -99,
                  "verticalAcceleration": 99,
                  "spinDegreesPerSecond": -9999
                },
                "presentation": {
                  "startScale": -1,
                  "endScale": 99,
                  "fadeStartFraction": 4
                }
              }
            }
            """);

        assertEquals(1, parsed.limits().maxPerSecond());
        assertEquals(30000L, parsed.limits().lifetimeMs());
        assertEquals(0.0D, parsed.limits().minimumDelta());
        assertEquals(4, parsed.limits().decimals());
        assertEquals(new Vector(32.0D, -32.0D, 2.0D), parsed.damage().offset());
        assertEquals(16.0D, parsed.damage().motion().horizontalSpeed());
        assertEquals(-16.0D, parsed.damage().motion().verticalSpeed());
        assertEquals(32.0D, parsed.damage().motion().verticalAcceleration());
        assertEquals(-1440.0D, parsed.damage().motion().spinDegreesPerSecond());
        assertEquals(0.0D, parsed.damage().presentation().startScale());
        assertEquals(16.0D, parsed.damage().presentation().endScale());
        assertEquals(1.0D, parsed.damage().presentation().fadeStartFraction());
    }

    @Test
    void invalidOffsetComponentsFallBackToZero() {
        DamageIndicatorSettingsDoc.Style style = new DamageIndicatorSettingsDoc.Style(
            true,
            "{amount}",
            new Vector(Double.NaN, Double.POSITIVE_INFINITY, Double.NEGATIVE_INFINITY),
            new DamageIndicatorSettingsDoc.Motion(0.0D, 0.0D, 0.0D, 0.0D),
            new DamageIndicatorSettingsDoc.Presentation(1.0D, 1.0D, 1.0D));

        assertEquals(new Vector(), style.offset());
    }

    @Test
    void disabledWorldsAreCleanAndDistinct() {
        DamageIndicatorSettingsDoc.Filters filters = new DamageIndicatorSettingsDoc.Filters(
            List.of(" world ", "world", "", "nether"));

        assertEquals(List.of("world", "nether"), filters.disabledWorlds());
    }

    @Test
    void formatsMustCarryTheAmountToken() {
        IllegalArgumentException failure = assertThrows(IllegalArgumentException.class,
            () -> new DamageIndicatorSettingsDoc.Style(
                true, "&cDamage", new Vector(), null, null));

        assertTrue(failure.getMessage().contains("{amount}"));
    }
}
