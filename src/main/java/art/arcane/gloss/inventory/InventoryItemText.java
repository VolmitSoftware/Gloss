package art.arcane.gloss.inventory;

import art.arcane.gloss.util.common.TextUtils;
import com.github.retrooper.packetevents.protocol.component.ComponentTypes;
import com.github.retrooper.packetevents.protocol.component.builtin.item.ItemLore;
import com.github.retrooper.packetevents.protocol.item.ItemStack;
import net.kyori.adventure.text.Component;

import java.util.ArrayList;
import java.util.List;

final class InventoryItemText {
    private InventoryItemText() {
    }

    static ItemStack apply(ItemStack item, String name, List<String> lore) {
        if (name != null) {
            item.setComponent(ComponentTypes.CUSTOM_NAME, TextUtils.parse(name));
        }
        if (!lore.isEmpty()) {
            List<Component> lines = new ArrayList<>(lore.size());
            for (String line : lore) {
                lines.add(TextUtils.parse(line));
            }
            item.setComponent(ComponentTypes.LORE, new ItemLore(lines));
        }
        return item;
    }
}
