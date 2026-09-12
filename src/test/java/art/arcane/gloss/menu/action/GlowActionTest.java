package art.arcane.gloss.menu.action;

import art.arcane.gloss.config.action.GlowActionData;
import art.arcane.gloss.enums.MenuActionType;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

import java.util.UUID;

class GlowActionTest {
    private static final UUID BOSS = UUID.fromString("6a1f9f1e-0000-4000-8000-000000000001");

    @Test
    void readsTheSpecAction() {
        GlowActionData data = new GlowActionData("subject", "red", 100, "quest", 5, null, null, null);

        Assertions.assertEquals(MenuActionType.GLOW, data.getType());
        Assertions.assertTrue(data.isRole());
        Assertions.assertEquals("quest", data.purposeOrDefault());
        Assertions.assertEquals(5, data.priorityOrDefault());
        Assertions.assertEquals(100L, data.ticksOrDefault());
        Assertions.assertNull(data.invalidReason());
    }

    @Test
    void anEntityUuidTargetIsParsed() {
        GlowActionData data = new GlowActionData(BOSS.toString(), "aqua", null, null, null, null, null, null);

        Assertions.assertFalse(data.isRole());
        Assertions.assertEquals(BOSS, data.targetId());
        Assertions.assertNull(data.invalidReason());
    }

    @Test
    void anAbsentTargetMeansTheViewer() {
        GlowActionData data = new GlowActionData(null, "red", null, null, null, null, null, null);

        Assertions.assertFalse(data.isRole());
        Assertions.assertNull(data.targetId());
    }

    @Test
    void aTargetThatIsNeitherARoleNorAUuidIsInvalid() {
        Assertions.assertNotNull(new GlowActionData("that guy", "red", null, null, null, null, null, null)
            .invalidReason());
    }

    @Test
    void anActionWithoutAColorIsInvalid() {
        Assertions.assertNotNull(new GlowActionData("viewer", null, null, null, null, null, null, null)
            .invalidReason());
    }

    @Test
    void defaultsFillInThePurposeAndPriority() {
        GlowActionData data = new GlowActionData("viewer", "red", null, null, null, null, null, null);

        Assertions.assertEquals(GlowActionData.DEFAULT_PURPOSE, data.purposeOrDefault());
        Assertions.assertEquals(0, data.priorityOrDefault());
        Assertions.assertEquals(0L, data.ticksOrDefault());
    }

    @Test
    void theEnvelopeCarriesTheWhenAndCooldown() {
        GlowActionData data = new GlowActionData("viewer", "red", null, null, null, null,
            "viewer.health > 5", 40);

        Assertions.assertEquals("viewer.health > 5", data.envelope().when());
        Assertions.assertEquals(40, data.envelope().cooldownTicks());
    }
}
