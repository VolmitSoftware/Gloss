package art.arcane.gloss.config.action;

import art.arcane.gloss.enums.MenuActionType;
import art.arcane.gloss.sky.SkyOverride;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

class SkyActionDataTest {
    @Test
    void readsTheSpecAction() {
        SkyActionData data = new SkyActionData("arena", "18000", "thunder",
            new SkyOverride.Border(0, 0, 200, 5), 40, null, null, null);

        Assertions.assertEquals(MenuActionType.SKY, data.getType());
        Assertions.assertFalse(data.releases());
        SkyOverride override = data.toOverride();
        Assertions.assertEquals(18000L, override.time());
        Assertions.assertEquals("thunder", override.weather());
        Assertions.assertEquals(40, override.fadeTicks());
    }

    @Test
    void aResetInAnyFieldReleasesThePurpose() {
        Assertions.assertTrue(new SkyActionData("arena", "reset", null, null, null, null, null, null)
            .releases());
        Assertions.assertTrue(new SkyActionData("arena", null, "reset", null, null, null, null, null)
            .releases());
    }

    @Test
    void anActionWithoutAPurposeCanNeverDoAnything() {
        Assertions.assertNotNull(new SkyActionData(null, "18000", null, null, null, null, null, null)
            .invalidReason());
    }

    @Test
    void anActionThatNamesNothingToChangeIsInvalid() {
        Assertions.assertNotNull(new SkyActionData("arena", null, null, null, null, null, null, null)
            .invalidReason());
    }

    @Test
    void aTimeThatIsNotANumberIsInvalid() {
        Assertions.assertNotNull(new SkyActionData("arena", "noon", null, null, null, null, null, null)
            .invalidReason());
    }

    @Test
    void theEnvelopeCarriesTheWhenAndCooldown() {
        SkyActionData data = new SkyActionData("arena", "18000", null, null, null, null,
            "viewer.health > 5", 20);

        Assertions.assertEquals("viewer.health > 5", data.envelope().when());
        Assertions.assertEquals(20, data.envelope().cooldownTicks());
    }
}
