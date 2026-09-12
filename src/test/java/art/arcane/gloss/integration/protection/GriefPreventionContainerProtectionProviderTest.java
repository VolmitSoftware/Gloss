package art.arcane.gloss.integration.protection;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Unclaimed ground is open; inside a claim the viewer needs the Inventory permission. */
class GriefPreventionContainerProtectionProviderTest {
    @Test
    void groundWithoutAClaimIsOpen() throws Exception {
        GriefPreventionContainerProtectionProvider provider = new GriefPreventionContainerProtectionProvider(
            location -> null, (claim, player) -> false);

        assertTrue(provider.canAccess(ProtectionFakes.player(), ProtectionFakes.block()));
        assertTrue(provider.canAccess(ProtectionFakes.player(), ProtectionFakes.entity()));
    }

    @Test
    void aClaimWithoutInventoryPermissionDenies() throws Exception {
        GriefPreventionContainerProtectionProvider provider = new GriefPreventionContainerProtectionProvider(
            location -> new Object(), (claim, player) -> false);

        assertFalse(provider.canAccess(ProtectionFakes.player(), ProtectionFakes.block()));
        assertFalse(provider.canAccess(ProtectionFakes.player(), ProtectionFakes.entity()));
    }

    @Test
    void aClaimThatGrantsInventoryPermissionAllows() throws Exception {
        GriefPreventionContainerProtectionProvider provider = new GriefPreventionContainerProtectionProvider(
            location -> new Object(), (claim, player) -> true);

        assertTrue(provider.canAccess(ProtectionFakes.player(), ProtectionFakes.block()));
    }
}
