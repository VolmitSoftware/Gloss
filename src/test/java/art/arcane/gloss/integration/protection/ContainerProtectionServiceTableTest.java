package art.arcane.gloss.integration.protection;

import org.bukkit.block.Block;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Player;
import org.bukkit.event.Event;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Every installed claim plugin gets a vote on a container preview and the strictest one wins, so a
 * viewer who cannot open the chest never sees inside it. A provider that throws denies its own
 * answer only: removing it restores access instead of leaving previews locked forever.
 */
class ContainerProtectionServiceTableTest {
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

    private static ContainerProtectionProvider throwing() {
        return new ContainerProtectionProvider() {
            @Override
            public boolean canAccess(Player player, Block block) throws ReflectiveOperationException {
                throw new ReflectiveOperationException("boom");
            }

            @Override
            public boolean canAccess(Player player, Entity entity) throws ReflectiveOperationException {
                throw new ReflectiveOperationException("boom");
            }
        };
    }

    @Test
    void denyWinsAcrossInstalledProviders() {
        List<Event> events = new ArrayList<>();
        ContainerProtectionService service = new ContainerProtectionService(ProtectionFakes.plugin(),
            List.of(), fixed(true), events::add);
        service.install("A", fixed(true));
        service.install("B", fixed(false));

        assertFalse(service.canAccess(ProtectionFakes.player(), ProtectionFakes.block()));
        assertEquals(List.of("A", "B"), service.activeProviderNames());
        assertTrue(events.isEmpty(), "a denied preview never reaches the access event");
    }

    @Test
    void everyProviderMustAllowBeforeTheAccessEventIsFired() {
        List<Event> events = new ArrayList<>();
        ContainerProtectionService service = new ContainerProtectionService(ProtectionFakes.plugin(),
            List.of(), fixed(true), events::add);
        service.install("A", fixed(true));
        service.install("B", fixed(true));

        assertTrue(service.canAccess(ProtectionFakes.player(), ProtectionFakes.block()));
        assertEquals(1, events.size());
    }

    @Test
    void aFailingProviderDeniesOnlyItsOwnAnswer() {
        List<Event> events = new ArrayList<>();
        ContainerProtectionService service = new ContainerProtectionService(ProtectionFakes.plugin(),
            List.of(), fixed(true), events::add);
        service.install("A", throwing());

        assertFalse(service.canAccess(ProtectionFakes.player(), ProtectionFakes.block()));

        service.uninstall("A");
        assertEquals(List.of(), service.activeProviderNames());
        assertTrue(service.canAccess(ProtectionFakes.player(), ProtectionFakes.block()),
            "removing the broken provider must restore previews");
    }

    @Test
    void uninstallingOneProviderLeavesTheOthersVoting() {
        ContainerProtectionService service = new ContainerProtectionService(ProtectionFakes.plugin(),
            List.of(), fixed(true), event -> {
        });
        service.install("A", fixed(false));
        service.install("B", fixed(true));

        assertFalse(service.canAccess(ProtectionFakes.player(), ProtectionFakes.entity()));

        service.uninstall("A");
        assertEquals(List.of("B"), service.activeProviderNames());
        assertTrue(service.canAccess(ProtectionFakes.player(), ProtectionFakes.entity()));
    }
}
