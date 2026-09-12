package art.arcane.gloss.hologram;

import art.arcane.gloss.Gloss;
import art.arcane.gloss.GlossConfig;
import art.arcane.gloss.config.icon.CustomItemIconData;
import art.arcane.gloss.config.icon.ItemIconData;
import art.arcane.gloss.config.icon.ItemStackIconData;
import art.arcane.gloss.config.icon.MenuIconData;
import art.arcane.gloss.exceptions.MenuIconException;
import art.arcane.gloss.integration.ItemProviderRegistry;
import art.arcane.gloss.profile.PlayerHeadItems;
import art.arcane.gloss.profile.PlayerHeadService;
import art.arcane.gloss.util.common.DisplayEntity;
import art.arcane.gloss.util.common.ItemUtils;
import art.arcane.volmlib.util.bukkit.registry.RegistryUtil;
import com.github.retrooper.packetevents.protocol.entity.type.EntityType;
import com.github.retrooper.packetevents.protocol.entity.type.EntityTypes;
import com.github.retrooper.packetevents.util.Vector3f;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.block.data.BlockData;
import org.bukkit.inventory.ItemStack;

import java.util.ArrayList;
import java.util.List;

/**
 * Object lines drawn as packet display entities under the hologram's text column: an item, a head,
 * a block or a raw entity, each one row tall at its authored scale.
 */
final class HologramLineRenderer {
    /** Height of one rendered text row at scale one; the same value the box layout measures with. */
    static final double TEXT_ROW_HEIGHT = 0.25D;

    private HologramLineRenderer() {
    }

    record ObjectLine(HologramLine source, DisplayEntity display, double height) {
    }

    /**
     * Builds one display entity per object line, stacked downwards under a text column of
     * {@code textRows} rows. Lines that cannot be resolved are skipped.
     */
    static List<ObjectLine> build(Gloss plugin, String hologramId, List<HologramLine> lines, Location anchor,
                                  int textRows, float textScaleY) {
        List<ObjectLine> built = new ArrayList<>();
        double offset = -TEXT_ROW_HEIGHT * textRows * textScaleY / 2.0D;
        for (HologramLine line : lines) {
            if (line.isText()) {
                continue;
            }
            double height = line.scale();
            offset -= height / 2.0D;
            DisplayEntity display = display(plugin, hologramId, line, anchor, offset);
            offset -= height / 2.0D;
            if (display == null) {
                continue;
            }
            built.add(new ObjectLine(line, display, height));
        }
        return List.copyOf(built);
    }

    private static DisplayEntity display(Gloss plugin, String hologramId, HologramLine line, Location anchor,
                                         double offsetY) {
        Location position = anchor.clone().add(0.0D, offsetY, 0.0D);
        float scale = (float) line.scale();
        return switch (line.kind()) {
            case ITEM -> DisplayEntity.Builder.itemDisplay(item(plugin, hologramId, line.item()), position, scale);
            case HEAD -> DisplayEntity.Builder.itemDisplay(head(plugin, line.value()), position, scale);
            case BLOCK -> blockDisplay(hologramId, line.value(), position, scale);
            case ENTITY -> entityDisplay(hologramId, line.value(), position);
            case TEXT -> null;
        };
    }

    private static DisplayEntity blockDisplay(String hologramId, String key, Location position, float scale) {
        Material material = material(key);
        if (material == null || !material.isBlock()) {
            Gloss.warnThrottled("hologram-block-" + hologramId + "-" + key,
                "Hologram %s names unknown block %s; that line is skipped.", hologramId, key);
            return null;
        }
        BlockData data = material.createBlockData();
        DisplayEntity display = DisplayEntity.Builder.blockDisplay(data, position);
        display.scale(new Vector3f(scale, scale, scale));
        return display;
    }

    private static DisplayEntity entityDisplay(String hologramId, String key, Location position) {
        EntityType type;
        try {
            type = EntityTypes.getByName(key);
        } catch (RuntimeException failure) {
            type = null;
        }
        if (type == null) {
            Gloss.warnThrottled("hologram-entity-" + hologramId + "-" + key,
                "Hologram %s names unknown entity %s; that line is skipped.", hologramId, key);
            return null;
        }
        return DisplayEntity.Builder.entity(type, position);
    }

    private static ItemStack head(Gloss plugin, String name) {
        PlayerHeadService heads = plugin.playerHeads();
        String fallback = GlossConfig.current().playerHeads().unknownFallbackItem();
        if (heads == null) {
            return new ItemStack(PlayerHeadItems.fallbackMaterial(fallback));
        }
        return PlayerHeadItems.stackFor(heads.lookup(name), fallback);
    }

    private static ItemStack item(Gloss plugin, String hologramId, MenuIconData icon) {
        ItemStack resolved = null;
        try {
            if (icon instanceof ItemIconData item) {
                Material material = item.requireMaterial();
                resolved = new ItemUtils.Builder(material, item.count() > 0 ? item.count() : 1)
                    .modelData(item.customModelValue()).get();
            } else if (icon instanceof ItemStackIconData stack) {
                resolved = stack.stack().clone();
            } else if (icon instanceof CustomItemIconData custom) {
                ItemProviderRegistry registry = plugin.getItemProviders();
                resolved = registry == null ? null : registry.resolve(custom.provider(), custom.item());
            }
        } catch (MenuIconException | RuntimeException failure) {
            resolved = null;
        }
        if (resolved == null) {
            Gloss.warnThrottled("hologram-item-" + hologramId,
                "Hologram %s could not resolve an item line; using a barrier.", hologramId);
            return new ItemStack(Material.BARRIER);
        }
        return resolved;
    }

    private static Material material(String key) {
        try {
            NamespacedKey namespaced = NamespacedKey.fromString(key);
            return namespaced == null ? null : RegistryUtil.find(Material.class, namespaced);
        } catch (RuntimeException | LinkageError failure) {
            return Material.matchMaterial(key);
        }
    }
}
