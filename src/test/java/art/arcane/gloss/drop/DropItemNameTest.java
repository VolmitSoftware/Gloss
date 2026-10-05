package art.arcane.gloss.drop;

import art.arcane.gloss.GlossConfig;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Proxy;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class DropItemNameTest {
    private DropFakes.Harness harness;

    @BeforeEach
    void setUp() {
        harness = DropFakes.harness("");
    }

    @AfterEach
    void tearDown() {
        harness.restore();
    }

    @Test
    void customNameWinsOverTheItemNameComponent() {
        DropItemName name = DropItemName.capture(new NamedStack("Renamed Ticket", "Amber Ticket"), labels(true));
        assertEquals("Renamed Ticket", name.resolve(null));
        assertTrue(name.literal());
    }

    @Test
    void itemNameComponentWorksWithoutACustomDisplayNameOrCraftEngine() {
        DropItemName name = DropItemName.capture(new NamedStack(null, "Amber Ticket"), labels(true));
        assertEquals("Amber Ticket", name.resolve(null));
        assertTrue(name.literal());
    }

    @Test
    void blankCustomNameFallsBackToTheItemNameComponent() {
        assertEquals("Amber Ticket",
            DropItemName.capture(new NamedStack(" ", "Amber Ticket"), labels(true)).resolve(null));
    }

    @Test
    void disabledItemNamesAndUnnamedItemsKeepTheConfiguredMaterialLabel() {
        DropItemName disabled = DropItemName.capture(new NamedStack("Custom", "Item"), labels(false));
        DropItemName unnamed = DropItemName.capture(new NamedStack(null, null), labels(true));
        assertEquals("Paper Label", disabled.resolve(null));
        assertEquals("Paper Label", unnamed.resolve(null));
        assertFalse(disabled.literal());
        assertFalse(unnamed.literal());
    }

    @Test
    void viewerNamesStaySeparateAndAbsentNamesUseTheSnapshotFallback() {
        DropItemName name = new DropItemName("Amber Ticket", true,
            viewer -> viewer.getName().equals("unknown") ? null : viewer.getName() + " Ticket");
        assertEquals("Amber Ticket", name.resolve(null));
        assertEquals("alpha Ticket", name.resolve(player("alpha")));
        assertEquals("beta Ticket", name.resolve(player("beta")));
        assertEquals("Amber Ticket", name.resolve(player("unknown")));
    }

    private static GlossConfig.RealDrops.Labels labels(boolean enabled) {
        return RealDropSettingsDoc.parse("default.json", """
            {"schemaVersion":4,"revision":1,"presentation":{"labels":{
              "useItemDisplayNames":%s,"names":{"paper":"Paper Label"}}}}
            """.formatted(enabled)).toConfig(true).labels();
    }

    private static Player player(String name) {
        return (Player) Proxy.newProxyInstance(Player.class.getClassLoader(), new Class<?>[]{Player.class},
            (proxy, method, arguments) -> {
                if (method.getName().equals("getName")) {
                    return name;
                }
                throw new UnsupportedOperationException(method.getName());
            });
    }

    private static final class NamedStack extends ItemStack {
        private final ItemMeta meta;

        private NamedStack(String customName, String itemName) {
            meta = (ItemMeta) Proxy.newProxyInstance(ItemMeta.class.getClassLoader(), new Class<?>[]{ItemMeta.class},
                (proxy, method, arguments) -> switch (method.getName()) {
                    case "hasDisplayName" -> customName != null;
                    case "getDisplayName" -> customName;
                    case "hasItemName" -> itemName != null;
                    case "getItemName" -> itemName;
                    default -> throw new UnsupportedOperationException(method.getName());
                });
        }

        @Override
        public Material getType() {
            return Material.PAPER;
        }

        @Override
        public ItemMeta getItemMeta() {
            return meta;
        }
    }
}
