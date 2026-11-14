package art.arcane.gloss.inventory;

import art.arcane.gloss.text.ClientTextLines;
import art.arcane.gloss.text.ClientTextLines.Run;
import art.arcane.gloss.util.common.TextUtils;
import com.github.retrooper.packetevents.PacketEvents;
import com.github.retrooper.packetevents.PacketEventsAPI;
import com.github.retrooper.packetevents.injector.ChannelInjector;
import com.github.retrooper.packetevents.manager.player.PlayerManager;
import com.github.retrooper.packetevents.manager.protocol.ProtocolManager;
import com.github.retrooper.packetevents.manager.server.ServerManager;
import com.github.retrooper.packetevents.manager.server.ServerVersion;
import com.github.retrooper.packetevents.netty.NettyManager;
import com.github.retrooper.packetevents.protocol.component.ComponentTypes;
import io.github.retrooper.packetevents.netty.NettyManagerImpl;
import com.github.retrooper.packetevents.protocol.item.ItemStack;
import com.github.retrooper.packetevents.protocol.item.type.ItemTypes;
import net.kyori.adventure.key.Key;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;

class InventoryItemTextTest {
    private static final int WHITE = NamedTextColor.WHITE.value();
    private static final int RED = NamedTextColor.RED.value();
    private static final int GOLD = NamedTextColor.GOLD.value();
    private static final int GRAY = NamedTextColor.GRAY.value();
    private static final int DARK_PURPLE = NamedTextColor.DARK_PURPLE.value();

    @BeforeEach
    void install() {
        PacketEvents.setAPI(new ItemApi());
    }

    @AfterEach
    void clear() {
        PacketEvents.setAPI(null);
    }

    @Test
    void itemComponentsRetainFontsAndUnrelatedItemData() {
        ItemStack item = ItemStack.builder().type(ItemTypes.DIAMOND_SWORD).amount(3).build();
        item.setComponent(ComponentTypes.DAMAGE, 5);
        assertSame(item, InventoryItemText.apply(item, "<font:nativeqa:gold>\ue000</font>",
                List.of("<font:nativeqa:cyan>\ue001</font>")));
        Component name = item.getComponent(ComponentTypes.CUSTOM_NAME).orElseThrow();
        Component lore = item.getComponent(ComponentTypes.LORE).orElseThrow().getLines().getFirst();
        assertEquals("\ue000", TextUtils.content(name));
        assertEquals(Key.key("nativeqa:gold"), name.font());
        assertEquals("\ue001", TextUtils.content(lore));
        assertEquals(Key.key("nativeqa:cyan"), lore.font());
        assertEquals(5, item.getComponent(ComponentTypes.DAMAGE).orElseThrow());
        assertEquals(3, item.getAmount());
    }

    @Test
    void omittedNameAndLoreKeepExistingComponents() {
        ItemStack item = ItemStack.builder().type(ItemTypes.PAPER).build();
        InventoryItemText.apply(item, "Kept", List.of("Kept lore"));
        InventoryItemText.apply(item, null, List.of());
        assertEquals("Kept", TextUtils.content(item.getComponent(ComponentTypes.CUSTOM_NAME).orElseThrow()));
        assertEquals("Kept lore", TextUtils.content(item.getComponent(ComponentTypes.LORE).orElseThrow().getLines().getFirst()));
    }

    @Test
    void aColourCodeClearsTheItemItalicBesideMiniMessageLines() {
        ItemStack item = ItemStack.builder().type(ItemTypes.DIAMOND_SWORD).build();
        InventoryItemText.apply(item, "&cSword", List.of("<gold>Title", "&7Info"));

        assertEquals(List.of(List.of(Run.plain("Sword", RED))), name(item));
        assertEquals(List.of(List.of(Run.italic("Title", GOLD)), List.of(Run.plain("Info", GRAY))), lore(item));
    }

    @Test
    void aResetReturnsToTheItemDefault() {
        ItemStack item = ItemStack.builder().type(ItemTypes.PAPER).build();
        InventoryItemText.apply(item, "&cA&rB", List.of("&cA&rB", "&lC&cD"));

        assertEquals(List.of(List.of(Run.plain("A", RED), Run.italic("B", WHITE))), name(item));
        assertEquals(List.of(
            List.of(Run.plain("A", RED), Run.italic("B", DARK_PURPLE)),
            List.of(new Run("C", DARK_PURPLE, true, true, false, false, false), Run.plain("D", RED))), lore(item));
    }

    private static List<List<Run>> name(ItemStack item) {
        return ClientTextLines.customName(item.getComponent(ComponentTypes.CUSTOM_NAME).orElseThrow());
    }

    private static List<List<Run>> lore(ItemStack item) {
        return ClientTextLines.loreLines(item.getComponent(ComponentTypes.LORE).orElseThrow().getLines());
    }

    private static final class ItemApi extends PacketEventsAPI<Object> {
        @Override
        public boolean isLoaded() {
            return true;
        }

        @Override
        public void init() {
        }

        @Override
        public boolean isInitialized() {
            return true;
        }

        @Override
        public boolean isTerminated() {
            return false;
        }

        @Override
        public Object getPlugin() {
            return this;
        }

        @Override
        public ServerManager getServerManager() {
            return () -> ServerVersion.V_26_1_2;
        }

        @Override
        public ProtocolManager getProtocolManager() {
            return null;
        }

        @Override
        public PlayerManager getPlayerManager() {
            return null;
        }

        @Override
        public NettyManager getNettyManager() {
            return new NettyManagerImpl();
        }

        @Override
        public ChannelInjector getInjector() {
            return null;
        }
    }
}
