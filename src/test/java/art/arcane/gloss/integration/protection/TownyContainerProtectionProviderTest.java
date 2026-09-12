package art.arcane.gloss.integration.protection;

import org.bukkit.Material;
import org.junit.jupiter.api.Test;

import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Towny answers the switch permission for the container's own material at its own location. */
class TownyContainerProtectionProviderTest {
    @Test
    void theSwitchPermissionDecides() throws Exception {
        assertTrue(new TownyContainerProtectionProvider((player, location, material) -> true)
            .canAccess(ProtectionFakes.player(), ProtectionFakes.block()));
        assertFalse(new TownyContainerProtectionProvider((player, location, material) -> false)
            .canAccess(ProtectionFakes.player(), ProtectionFakes.block()));
    }

    @Test
    void theContainerMaterialIsWhatIsAskedAbout() throws Exception {
        AtomicReference<Material> asked = new AtomicReference<>();
        TownyContainerProtectionProvider provider = new TownyContainerProtectionProvider(
            (player, location, material) -> {
                asked.set(material);
                return true;
            });

        provider.canAccess(ProtectionFakes.player(), ProtectionFakes.block());

        assertEquals(Material.CHEST, asked.get());
    }

    @Test
    void containerEntitiesAskAboutTheChestMaterial() throws Exception {
        AtomicReference<Material> asked = new AtomicReference<>();
        TownyContainerProtectionProvider provider = new TownyContainerProtectionProvider(
            (player, location, material) -> {
                asked.set(material);
                return true;
            });

        assertTrue(provider.canAccess(ProtectionFakes.player(), ProtectionFakes.entity()));
        assertEquals(Material.CHEST, asked.get());
    }
}
