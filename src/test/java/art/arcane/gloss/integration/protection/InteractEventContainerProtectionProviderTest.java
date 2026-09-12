package art.arcane.gloss.integration.protection;

import org.bukkit.event.Event;
import org.bukkit.event.block.Action;
import org.bukkit.event.player.PlayerInteractEntityEvent;
import org.bukkit.event.player.PlayerInteractEvent;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * With no claim plugin installed the server's own listeners still get a say: the provider asks
 * them with a right-click they never see the result of, and a cancel or a denied block use is a no.
 */
class InteractEventContainerProtectionProviderTest {
    @Test
    void anUncontestedRightClickAllowsTheView() throws Exception {
        List<Event> fired = new ArrayList<>();
        InteractEventContainerProtectionProvider provider =
            new InteractEventContainerProtectionProvider(fired::add);

        assertTrue(provider.canAccess(ProtectionFakes.player(), ProtectionFakes.block()));
        assertEquals(1, fired.size());
        assertEquals(Action.RIGHT_CLICK_BLOCK, ((PlayerInteractEvent) fired.getFirst()).getAction());
    }

    @Test
    void aCancelledInteractionDeniesTheView() throws Exception {
        InteractEventContainerProtectionProvider provider = new InteractEventContainerProtectionProvider(
            event -> ((PlayerInteractEvent) event).setCancelled(true));

        assertFalse(provider.canAccess(ProtectionFakes.player(), ProtectionFakes.block()));
    }

    @Test
    void aDeniedBlockUseDeniesTheView() throws Exception {
        InteractEventContainerProtectionProvider provider = new InteractEventContainerProtectionProvider(
            event -> ((PlayerInteractEvent) event).setUseInteractedBlock(Event.Result.DENY));

        assertFalse(provider.canAccess(ProtectionFakes.player(), ProtectionFakes.block()));
    }

    @Test
    void entitiesAreAskedWithAnEntityInteraction() throws Exception {
        List<Event> fired = new ArrayList<>();
        InteractEventContainerProtectionProvider allowing =
            new InteractEventContainerProtectionProvider(fired::add);

        assertTrue(allowing.canAccess(ProtectionFakes.player(), ProtectionFakes.entity()));
        assertEquals(1, fired.size());
        assertTrue(fired.getFirst() instanceof PlayerInteractEntityEvent);

        InteractEventContainerProtectionProvider denying = new InteractEventContainerProtectionProvider(
            event -> ((PlayerInteractEntityEvent) event).setCancelled(true));
        assertFalse(denying.canAccess(ProtectionFakes.player(), ProtectionFakes.entity()));
    }
}
