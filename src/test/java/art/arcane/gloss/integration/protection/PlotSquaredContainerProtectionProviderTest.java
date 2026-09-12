package art.arcane.gloss.integration.protection;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Outside a plot PlotSquared has no opinion; inside one only added players may look in. */
class PlotSquaredContainerProtectionProviderTest {
    @Test
    void groundWithoutAPlotIsOpen() throws Exception {
        PlotSquaredContainerProtectionProvider provider = new PlotSquaredContainerProtectionProvider(
            location -> null, (plot, player) -> false);

        assertTrue(provider.canAccess(ProtectionFakes.player(), ProtectionFakes.block()));
        assertTrue(provider.canAccess(ProtectionFakes.player(), ProtectionFakes.entity()));
    }

    @Test
    void onlyAddedPlayersSeeInsideAPlot() throws Exception {
        assertFalse(new PlotSquaredContainerProtectionProvider(location -> new Object(), (plot, player) -> false)
            .canAccess(ProtectionFakes.player(), ProtectionFakes.block()));
        assertTrue(new PlotSquaredContainerProtectionProvider(location -> new Object(),
            (plot, player) -> ProtectionFakes.PLAYER_ID.equals(player.getUniqueId()))
            .canAccess(ProtectionFakes.player(), ProtectionFakes.block()));
    }
}
