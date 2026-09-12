package art.arcane.gloss.integration.protection;

import org.bukkit.block.Block;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Player;
import org.bukkit.event.Event;
import org.bukkit.event.player.PlayerInteractEntityEvent;
import org.bukkit.event.player.PlayerInteractEvent;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The interact-event fallback exists for servers with no claim plugin Gloss has an adapter for.
 * It pushes a synthetic right-click through every other plugin's handlers, so it must not run when
 * an adapter can answer, and the click it does fire must be recognisable as a probe.
 */
class ContainerProtectionFallbackTest {
    private static ContainerProtectionProvider fixed(boolean answer) {
        return new ContainerProtectionProvider() {
            @Override
            public boolean canAccess(Player player, Block block) {
                return answer;
            }

            @Override
            public boolean canAccess(Player player, Entity entity) {
                return answer;
            }
        };
    }

    private static ContainerProtectionProvider counting(List<String> calls) {
        return new ContainerProtectionProvider() {
            @Override
            public boolean canAccess(Player player, Block block) {
                calls.add("block");
                return true;
            }

            @Override
            public boolean canAccess(Player player, Entity entity) {
                calls.add("entity");
                return true;
            }
        };
    }

    @Test
    void aClaimAdapterAnswersInsteadOfTheSyntheticClick() {
        List<String> fallbackCalls = new ArrayList<>();
        ContainerProtectionService service = new ContainerProtectionService(ProtectionFakes.plugin(),
            List.of(), counting(fallbackCalls), event -> {
        });
        service.install("WorldGuard", fixed(true));

        assertTrue(service.canAccess(ProtectionFakes.player(), ProtectionFakes.block()));
        assertTrue(service.canAccess(ProtectionFakes.player(), ProtectionFakes.entity()));
        assertEquals(List.of(), fallbackCalls,
            "an installed adapter answers; the fallback must not fire a synthetic click");
    }

    @Test
    void theSyntheticClickStillRunsWithNoAdapterInstalled() {
        List<String> fallbackCalls = new ArrayList<>();
        ContainerProtectionService service = new ContainerProtectionService(ProtectionFakes.plugin(),
            List.of(), counting(fallbackCalls), event -> {
        });

        assertTrue(service.canAccess(ProtectionFakes.player(), ProtectionFakes.block()));
        assertEquals(List.of("block"), fallbackCalls);
    }

    @Test
    void theProbeIsMarkedSoGlossSkipsItsOwnClickHandler() throws Exception {
        List<Event> fired = new ArrayList<>();
        InteractEventContainerProtectionProvider provider =
            new InteractEventContainerProtectionProvider(fired::add);

        provider.canAccess(ProtectionFakes.player(), ProtectionFakes.block());
        provider.canAccess(ProtectionFakes.player(), ProtectionFakes.entity());

        assertEquals(2, fired.size());
        assertInstanceOf(PlayerInteractEvent.class, fired.getFirst(),
            "other plugins must still see a plain interact event");
        assertInstanceOf(PlayerInteractEntityEvent.class, fired.get(1));
        assertTrue(ContainerProtectionProbe.isProbe(fired.getFirst()));
        assertTrue(ContainerProtectionProbe.isProbe(fired.get(1)));
    }

    @Test
    void aViewersOwnClickIsNotMistakenForAProbe() {
        assertFalse(ContainerProtectionProbe.isProbe(new PlayerInteractEvent(ProtectionFakes.player(),
            org.bukkit.event.block.Action.RIGHT_CLICK_BLOCK, null, ProtectionFakes.block(),
            org.bukkit.block.BlockFace.UP)));
        assertFalse(ContainerProtectionProbe.isProbe(
            new PlayerInteractEntityEvent(ProtectionFakes.player(), ProtectionFakes.entity())));
    }

    @Test
    void anAdapterThatCannotBeBuiltKeepsDenyingInsteadOfDisappearing() {
        ContainerProtectionService service = new ContainerProtectionService(ProtectionFakes.plugin(),
            List.of(), fixed(true), event -> {
        });

        service.build(new ProtectionProviderDefinition("GriefPrevention", plugin -> {
            throw new ReflectiveOperationException("class moved");
        }), ProtectionFakes.plugin());

        assertEquals(List.of("GriefPrevention"), service.activeProviderNames(),
            "a provider that failed to load must stay in the table, refusing");
        assertFalse(service.canAccess(ProtectionFakes.player(), ProtectionFakes.block()));
        assertFalse(service.canAccess(ProtectionFakes.player(), ProtectionFakes.entity()));

        service.uninstall("GriefPrevention");
        assertTrue(service.canAccess(ProtectionFakes.player(), ProtectionFakes.block()),
            "uninstalling the broken plugin restores previews");
    }
}
