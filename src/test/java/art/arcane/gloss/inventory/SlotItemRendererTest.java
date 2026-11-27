package art.arcane.gloss.inventory;

import art.arcane.gloss.Gloss;
import art.arcane.gloss.config.icon.AnimatedImageData;
import art.arcane.gloss.config.icon.BlockIconData;
import art.arcane.gloss.config.icon.CustomItemIconData;
import art.arcane.gloss.config.icon.EntityIconData;
import art.arcane.gloss.config.icon.ItemIconData;
import art.arcane.gloss.config.icon.MenuIconData;
import art.arcane.gloss.config.icon.PlayerHeadIconData;
import art.arcane.gloss.config.icon.TextIconData;
import art.arcane.gloss.config.icon.TextImageIconData;
import art.arcane.gloss.integration.ItemProvider;
import art.arcane.gloss.integration.ItemProviderRegistry;
import art.arcane.gloss.menu.CharacterizationSupport;
import org.bukkit.Material;
import org.bukkit.Server;
import org.bukkit.inventory.ItemStack;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.Collection;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * What each icon kind draws in a chest slot. Three of the nine kinds are display-entity surfaces
 * with no item form at all; they degrade to a signposted stack rather than leaving a hole, because
 * a blank slot in a shop reads as a bug and a lore note reads as an answer.
 */
class SlotItemRendererTest {

    private Object previousServer;
    private Gloss previousGloss;

    @BeforeEach
    void installHeadlessServer() throws ReflectiveOperationException {
        Server server = CharacterizationSupport.server(Map.of());
        previousServer = CharacterizationSupport.installServer(server);
        previousGloss = CharacterizationSupport.installGloss(CharacterizationSupport.bareGloss(server));
    }

    @AfterEach
    void restore() throws ReflectiveOperationException {
        CharacterizationSupport.restoreGloss(previousGloss);
        CharacterizationSupport.restoreServer(previousServer);
    }

    @Test
    void anItemIconDrawsItsMaterialNameAndLore() {
        SlotItemRenderer.SlotItem slot = SlotItemRenderer.resolve(null,
            new ItemIconData(Material.DIAMOND_SWORD, 3, 0, null, "&bSword", List.of("&7100 coins"), null), null);
        assertEquals(Material.DIAMOND_SWORD, slot.material());
        assertEquals(3, slot.amount());
        assertEquals("&bSword", slot.name());
        assertEquals(List.of("&7100 coins"), slot.lore());
        assertFalse(slot.unsupported());
    }

    @Test
    void anItemIconWithoutACountDrawsOne() {
        SlotItemRenderer.SlotItem slot = SlotItemRenderer.resolve(null,
            new ItemIconData(Material.STONE, 0, 0, null, null, null, null), null);
        assertEquals(1, slot.amount());
    }

    @Test
    void aBlockIconDrawsItsBlockAsAnItem() {
        SlotItemRenderer.SlotItem slot = SlotItemRenderer.resolve(null,
            new BlockIconData(Material.OAK_LOG, null, "&6Timber", List.of("&7Building")), null);
        assertEquals(Material.OAK_LOG, slot.material());
        assertEquals("&6Timber", slot.name());
        assertEquals(List.of("&7Building"), slot.lore());
        assertFalse(slot.unsupported());
    }

    @Test
    void aTextIconDrawsPaperNamedWithItsText() {
        SlotItemRenderer.SlotItem slot = SlotItemRenderer.resolve(null,
            new TextIconData("&7Next page", null, null, null), null);
        assertEquals(Material.PAPER, slot.material());
        assertEquals("&7Next page", slot.name());
        assertFalse(slot.unsupported());
    }

    @Test
    void aPlayerHeadIconDrawsAHead() {
        SlotItemRenderer.SlotItem slot = SlotItemRenderer.resolve(null,
            new PlayerHeadIconData("Notch", null, null, "&bProfile", List.of("&7Inspect")), null);
        assertEquals(SlotItemRenderer.UNRESOLVED_HEAD, slot.material());
        assertEquals("&bProfile", slot.name());
        assertEquals(List.of("&7Inspect"), slot.lore());
        assertFalse(slot.unsupported());
    }

    @Test
    void anUnresolvableCustomItemDegradesWithANote() {
        SlotItemRenderer.SlotItem slot = SlotItemRenderer.resolve(null,
            new CustomItemIconData("nexo", "ruby", 1, null, "&cRuby", List.of("&7Gem"), null), null);
        assertTrue(slot.unsupported());
        assertEquals(SlotItemRenderer.defaultUnsupportedMaterial(), slot.material());
    }

    @Test
    void aResolvedCustomItemDrawsTheProviderStackWithAuthoredNameAndLore() throws ReflectiveOperationException {
        installProvider(new StubProvider(Material.EMERALD));
        SlotItemRenderer.SlotItem slot = SlotItemRenderer.resolve(null,
            new CustomItemIconData("stub", "ruby", 2, null, "&cRuby Shop", List.of("&7Costs 5 coins"), null), null);
        assertFalse(slot.unsupported());
        assertEquals(Material.EMERALD, slot.material());
        assertEquals(2, slot.amount());
        assertEquals("&cRuby Shop", slot.name());
        assertEquals(List.of("&7Costs 5 coins"), slot.lore());
        assertEquals(Material.EMERALD, slot.prebuilt().getType());
    }

    @Test
    void aResolvedCustomItemWithoutOverridesKeepsTheProviderText() throws ReflectiveOperationException {
        installProvider(new StubProvider(Material.EMERALD));
        SlotItemRenderer.SlotItem slot = SlotItemRenderer.resolve(null,
            new CustomItemIconData("stub", "ruby", 1, null, null, null, null), null);
        assertFalse(slot.unsupported());
        assertNull(slot.name());
        assertTrue(slot.lore().isEmpty());
    }

    @Test
    void theThreeDisplayOnlyIconsDegradeWithANote() {
        for (MenuIconData icon : List.of(
            new TextImageIconData("logo.png", null),
            new AnimatedImageData(List.of("a.png", "b.png"), 4, null),
            new EntityIconData(null, null, null))) {
            SlotItemRenderer.SlotItem slot = SlotItemRenderer.resolve(null, icon, null);
            assertTrue(slot.unsupported(), icon.getType() + " should degrade");
            assertEquals(SlotItemRenderer.defaultUnsupportedMaterial(), slot.material());
            assertFalse(slot.lore().isEmpty(), icon.getType() + " should carry the reason in its lore");
        }
    }

    @Test
    void aMissingIconDegradesRatherThanLeavingTheSlotEmpty() {
        SlotItemRenderer.SlotItem slot = SlotItemRenderer.resolve(null, null, null);
        assertTrue(slot.unsupported());
    }

    private static void installProvider(ItemProvider provider) throws ReflectiveOperationException {
        ItemProviderRegistry registry = new ItemProviderRegistry(Gloss.instance);
        CharacterizationSupport.invoke(registry, "register", new Class<?>[]{ItemProvider.class}, provider);
        CharacterizationSupport.setField(Gloss.instance, "itemProviders", registry);
    }

    private record StubProvider(Material material) implements ItemProvider {
        @Override
        public String id() {
            return "stub";
        }

        @Override
        public String pluginName() {
            return "Stub";
        }

        @Override
        public ItemStack resolve(String itemId) {
            return new StubStack(material);
        }

        @Override
        public Collection<String> listIds() {
            return List.of("ruby");
        }
    }

    private static final class StubStack extends ItemStack {
        private final Material material;
        private int amount = 1;

        private StubStack(Material material) {
            this.material = material;
        }

        @Override
        public Material getType() {
            return material;
        }

        @Override
        public int getAmount() {
            return amount;
        }

        @Override
        public void setAmount(int amount) {
            this.amount = amount;
        }

        @Override
        public ItemStack clone() {
            StubStack copy = new StubStack(material);
            copy.amount = amount;
            return copy;
        }
    }
}
