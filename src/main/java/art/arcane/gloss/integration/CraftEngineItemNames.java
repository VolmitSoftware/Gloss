package art.arcane.gloss.integration;

import com.google.gson.JsonElement;
import net.kyori.adventure.text.serializer.gson.GsonComponentSerializer;
import net.kyori.adventure.text.serializer.legacy.LegacyComponentSerializer;
import net.momirealms.craftengine.bukkit.api.BukkitAdaptor;
import net.momirealms.craftengine.bukkit.api.CraftEngineItems;
import net.momirealms.craftengine.bukkit.item.BukkitItemManager;
import net.momirealms.craftengine.bukkit.plugin.user.BukkitServerPlayer;
import net.momirealms.craftengine.core.item.Item;
import net.momirealms.craftengine.core.item.network.ItemPacketSource;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.bukkit.plugin.Plugin;

import java.util.function.Function;

public final class CraftEngineItemNames {
    private static final LegacyComponentSerializer LEGACY = LegacyComponentSerializer.builder()
        .hexColors().useUnusualXRepeatedCharacterHexFormat().build();

    private CraftEngineItemNames() {
    }

    public static Function<Player, String> capture(ItemStack stack, Plugin plugin) {
        if (CraftEngineItems.byItemStack(stack) == null) {
            return null;
        }
        ItemStack snapshot = stack.clone();
        return viewer -> resolve(snapshot, plugin, viewer);
    }

    private static String resolve(ItemStack snapshot, Plugin plugin, Player viewer) {
        if (!plugin.isEnabled()) {
            return null;
        }
        BukkitServerPlayer player = BukkitAdaptor.adapt(viewer);
        if (player == null) {
            return null;
        }
        BukkitItemManager manager = BukkitItemManager.instance();
        Item source = manager.wrap(snapshot.clone());
        Item displayed = manager.s2c(source, player, ItemPacketSource.ENTITY_DATA).orElse(source);
        JsonElement json = displayed.customNameJson().orElse(null);
        if (json == null && displayed.hasNonDefaultComponent("minecraft:item_name")) {
            json = displayed.itemNameJson().orElse(null);
        }
        return json == null ? null : LEGACY.serialize(GsonComponentSerializer.gson().deserialize(json.toString()));
    }
}
