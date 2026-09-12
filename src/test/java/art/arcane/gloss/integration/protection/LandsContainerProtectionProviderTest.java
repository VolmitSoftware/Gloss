package art.arcane.gloss.integration.protection;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Wilderness has no area and is open; inside a land the INTERACT_CONTAINER role flag decides. */
class LandsContainerProtectionProviderTest {
    @Test
    void wildernessIsOpen() throws Exception {
        LandsContainerProtectionProvider provider = new LandsContainerProtectionProvider(
            location -> null, (area, player) -> false);

        assertTrue(provider.canAccess(ProtectionFakes.player(), ProtectionFakes.block()));
        assertTrue(provider.canAccess(ProtectionFakes.player(), ProtectionFakes.entity()));
    }

    @Test
    void theContainerRoleFlagDecidesInsideALand() throws Exception {
        assertFalse(new LandsContainerProtectionProvider(location -> new Object(), (area, player) -> false)
            .canAccess(ProtectionFakes.player(), ProtectionFakes.block()));
        assertTrue(new LandsContainerProtectionProvider(location -> new Object(), (area, player) -> true)
            .canAccess(ProtectionFakes.player(), ProtectionFakes.block()));
    }

    @Test
    void theViewerIdIsWhatIsAskedAbout() throws Exception {
        LandsContainerProtectionProvider provider = new LandsContainerProtectionProvider(
            location -> new Object(), (area, player) -> ProtectionFakes.PLAYER_ID.equals(player.getUniqueId()));

        assertTrue(provider.canAccess(ProtectionFakes.player(), ProtectionFakes.block()));
    }
}
