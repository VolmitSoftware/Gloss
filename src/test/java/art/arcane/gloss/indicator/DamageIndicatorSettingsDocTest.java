package art.arcane.gloss.indicator;

import org.bukkit.util.Vector;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class DamageIndicatorSettingsDocTest {
    @Test
    void retiredV1DocumentIsRejected() {
        assertThrows(IllegalArgumentException.class, () -> DamageIndicatorSettingsDoc.parse(
            "default.json", "{\"schemaVersion\":1,\"revision\":1}"));
    }

    @Test
    void envelopeOnlyDocumentUsesTheCompleteShippedDefaults() {
        DamageIndicatorSettingsDoc parsed = DamageIndicatorSettingsDoc.parse("default.json", """
            {"schemaVersion":3,"revision":1}
            """);

        assertEquals(DamageIndicatorSettingsDoc.DEFAULTS, parsed);
    }

    @Test
    void partialBasePresentationsInheritTheirEventDefaults() {
        DamageIndicatorSettingsDoc parsed = DamageIndicatorSettingsDoc.parse("default.json", """
            {
              "schemaVersion": 3,
              "revision": 1,
              "damage": {"when": "subject.health < 5"},
              "healing": {"presentation": {"transform": {"endScale": 2.0}}}
            }
            """);

        assertEquals("subject.health < 5", parsed.damage().when());
        assertEquals("&c&l{amount}", parsed.damage().presentation().format());
        assertEquals(0.8D, parsed.damage().presentation().motion().horizontalSpeed());
        assertEquals(2.0D, parsed.healing().presentation().transform().endScale());
        assertEquals(0.62D, parsed.healing().presentation().transform().fadeStartFraction());
    }

    @Test
    void numericValuesClampAtTheRuntimeBoundary() {
        DamageIndicatorSettingsDoc parsed = DamageIndicatorSettingsDoc.parse("default.json", """
            {
              "schemaVersion": 3,
              "revision": 1,
              "limits": {
                "maxPerSecond": 0,
                "lifetimeMs": 999999,
                "minimumDelta": -1,
                "decimals": 99
              },
              "damage": {
                "presentation": {
                  "offset": [999, -999, 2],
                  "motion": {
                    "horizontalSpeed": 99,
                    "verticalSpeed": -99,
                    "verticalAcceleration": 99,
                    "spinDegreesPerSecond": -9999
                  },
                  "transform": {
                    "startScale": -1,
                    "endScale": 99,
                    "fadeStartFraction": 4
                  }
                }
              }
            }
            """);

        DamageIndicatorSettingsDoc.IndicatorPresentation presentation =
            parsed.damage().presentation();
        assertEquals(1, parsed.limits().maxPerSecond());
        assertEquals(30000L, parsed.limits().lifetimeMs());
        assertEquals(0.0D, parsed.limits().minimumDelta());
        assertEquals(4, parsed.limits().decimals());
        assertEquals(new Vector(32.0D, -32.0D, 2.0D), presentation.offset());
        assertEquals(16.0D, presentation.motion().horizontalSpeed());
        assertEquals(-16.0D, presentation.motion().verticalSpeed());
        assertEquals(32.0D, presentation.motion().verticalAcceleration());
        assertEquals(-1440.0D, presentation.motion().spinDegreesPerSecond());
        assertEquals(0.0D, presentation.transform().startScale());
        assertEquals(16.0D, presentation.transform().endScale());
        assertEquals(1.0D, presentation.transform().fadeStartFraction());
    }

    @Test
    void invalidOffsetComponentsFallBackToZero() {
        DamageIndicatorSettingsDoc.IndicatorPresentation presentation =
            new DamageIndicatorSettingsDoc.IndicatorPresentation(
                "{amount}",
                new Vector(Double.NaN, Double.POSITIVE_INFINITY, Double.NEGATIVE_INFINITY),
                new DamageIndicatorSettingsDoc.Motion(0.0D, 0.0D, 0.0D, 0.0D),
                new DamageIndicatorSettingsDoc.Transform(1.0D, 1.0D, 1.0D), List.of());

        assertEquals(new Vector(), presentation.offset());
    }

    @Test
    void variantPresentationsMustBeComplete() {
        IllegalArgumentException failure = assertThrows(IllegalArgumentException.class,
            () -> DamageIndicatorSettingsDoc.parse("default.json", """
                {
                  "schemaVersion": 3,
                  "revision": 1,
                  "damage": {
                    "variants": [{
                      "id": "large",
                      "priority": 10,
                      "when": "event.amount >= 10",
                      "presentation": {"format": "&4{amount}"}
                    }]
                  }
                }
                """));

        assertTrue(failure.getMessage().contains("must be complete"));
    }

    @Test
    void invalidConditionsAreRejectedAtDocumentLoad() {
        assertThrows(IllegalArgumentException.class,
            () -> DamageIndicatorSettingsDoc.parse("default.json", """
                {
                  "schemaVersion": 3,
                  "revision": 1,
                  "audience": {"when": "viewer.health <"}
                }
                """));
    }

    @Test
    void formatsMustCarryTheAmountToken() {
        IllegalArgumentException failure = assertThrows(IllegalArgumentException.class,
            () -> new DamageIndicatorSettingsDoc.IndicatorPresentation(
                "&cDamage", new Vector(), null, null, List.of()));

        assertTrue(failure.getMessage().contains("{amount}"));
    }

}
