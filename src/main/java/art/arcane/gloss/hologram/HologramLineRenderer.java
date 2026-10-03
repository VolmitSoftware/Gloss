package art.arcane.gloss.hologram;

import art.arcane.gloss.Gloss;
import art.arcane.gloss.GlossConfig;
import art.arcane.gloss.api.IconDisplayStyle;
import art.arcane.gloss.config.icon.CustomItemIconData;
import art.arcane.gloss.config.icon.ItemIconData;
import art.arcane.gloss.config.icon.ItemStackIconData;
import art.arcane.gloss.config.icon.MenuIconData;
import art.arcane.gloss.exceptions.MenuIconException;
import art.arcane.gloss.integration.ItemProviderRegistry;
import art.arcane.gloss.profile.PlayerHeadItems;
import art.arcane.gloss.profile.PlayerHeadService;
import art.arcane.gloss.text.TextDisplayLayout;
import art.arcane.gloss.util.common.DisplayEntity;
import art.arcane.gloss.util.common.ItemUtils;
import art.arcane.gloss.util.common.RawEntityDimensions;
import art.arcane.gloss.particle.ParticleFrame;
import art.arcane.volmlib.util.bukkit.registry.RegistryUtil;
import com.github.retrooper.packetevents.protocol.entity.type.EntityType;
import com.github.retrooper.packetevents.protocol.entity.type.EntityTypes;
import com.github.retrooper.packetevents.util.Vector3f;
import org.bukkit.Location;
import org.bukkit.util.Vector;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.block.data.BlockData;
import org.bukkit.inventory.ItemStack;

import java.util.ArrayList;
import java.util.List;

/**
 * Object lines drawn as packet display entities in the hologram's authored row order: an item, a head,
 * a block or a raw entity, each one row tall at its authored scale.
 */
final class HologramLineRenderer {
    private static final byte HEAD_DISPLAY_CONTEXT = 8;
    /** Height of one rendered text row at scale one; the same value the box layout measures with. */
    static final double TEXT_ROW_HEIGHT = TextDisplayLayout.ROW_PIXELS * (double) TextDisplayLayout.PIXEL_SIZE;

    private HologramLineRenderer() {
    }

    record ObjectLine(HologramLine source, DisplayEntity display, double height, double centerY) {
    }

    /**
     * Builds one display entity per object line, reserving text rows around each object.
     * Lines that cannot be resolved are skipped.
     */
    static List<ObjectLine> build(Gloss plugin, String hologramId, List<HologramLine> lines, Location anchor,
                                  IconDisplayStyle style) {
        float textScaleY = style.scaleY();
        List<ObjectLine> built = new ArrayList<>();
        int totalRows = 0;
        for (HologramLine line : lines) {
            totalRows += rows(line, textScaleY);
        }
        double rowHeight = TEXT_ROW_HEIGHT * textScaleY;
        double offset = rowHeight * totalRows;
        for (HologramLine line : lines) {
            double height = rows(line, textScaleY) * rowHeight;
            offset -= height / 2.0D;
            if (!line.isText()) {
                DisplayEntity display = display(plugin, hologramId, line, anchor, offset, style);
                if (display != null) {
                    built.add(new ObjectLine(line, display, height, offset));
                }
            }
            offset -= height / 2.0D;
        }
        return List.copyOf(built);
    }

    static int rows(HologramLine line, float textScaleY) {
        return line.isText() ? line.text().split("\n", -1).length
            : Math.max(1, (int) Math.ceil(reservedHeight(line) / (TEXT_ROW_HEIGHT * textScaleY)));
    }

    static double objectHeight(HologramLine line) {
        EntityType type = line.kind() == HologramLine.Kind.ENTITY ? EntityTypes.getByName(line.value()) : null;
        return type == null ? line.scale()
            : EntityLineBounds.forType(type).height() * RawEntityDimensions.normalizedScale(type, line.scale());
    }

    static Location rawPosition(ObjectLine line, ParticleFrame frame, Location eye) {
        EntityLineBounds bounds = EntityLineBounds.forType(line.display().entityType());
        double scale = RawEntityDimensions.normalizedScale(line.display().entityType(), line.source().scale());
        Location rowCenter = frame.origin().add(frame.up().multiply(line.centerY()));
        float yaw = (float) Math.toDegrees(Math.atan2(rowCenter.getX() - eye.getX(),
            eye.getZ() - rowCenter.getZ()));
        double rotation = Math.PI - Math.toRadians(yaw);
        Vector right = new Vector(Math.cos(rotation), 0D, -Math.sin(rotation));
        Vector forward = new Vector(Math.sin(rotation), 0D, Math.cos(rotation));
        Vector normal = frame.back();
        double depth = scale / 2D * (bounds.width() * Math.abs(normal.dot(right))
            + bounds.height() * Math.abs(normal.getY()) + bounds.depth() * Math.abs(normal.dot(forward))) + 0.02D;
        Vector towardEye = eye.toVector().subtract(rowCenter.toVector());
        double forwardDistance = -towardEye.dot(normal);
        Vector displacement = forwardDistance > depth
            ? towardEye.multiply(depth / forwardDistance) : normal.multiply(-depth);
        Location position = rowCenter.add(displacement).subtract(forward.multiply(bounds.centerZ() * scale))
            .subtract(0D, bounds.centerY() * scale, 0D);
        position.setYaw(yaw);
        position.setPitch(0F);
        return position;
    }

    private static double reservedHeight(HologramLine line) {
        double height = objectHeight(line);
        if (line.kind() != HologramLine.Kind.ENTITY) {
            return height;
        }
        EntityType type = EntityTypes.getByName(line.value());
        return EntityLineBounds.forType(type).diagonal() * RawEntityDimensions.normalizedScale(type, line.scale());
    }

    private static DisplayEntity display(Gloss plugin, String hologramId, HologramLine line, Location anchor,
                                         double offsetY, IconDisplayStyle style) {
        Location position = anchor.clone().add(0.0D, offsetY, 0.0D);
        float scale = (float) line.scale();
        DisplayEntity display = switch (line.kind()) {
            case ITEM -> DisplayEntity.Builder.itemDisplay(item(plugin, hologramId, line.item()), anchor, scale);
            case HEAD -> DisplayEntity.Builder.itemDisplay(head(plugin, line.value()), anchor, scale,
                (byte) 0, HEAD_DISPLAY_CONTEXT);
            case BLOCK -> blockDisplay(hologramId, line.value(), anchor, scale);
            case ENTITY -> entityDisplay(hologramId, line.value(), position, line.scale());
            case TEXT -> null;
        };
        if (display != null && line.kind() != HologramLine.Kind.ENTITY) {
            configureObjectLayout(display, scale, offsetY, style);
        }
        return display;
    }

    static void configureObjectLayout(DisplayEntity display, float scale, double offsetY, IconDisplayStyle style) {
        Vector3f translation = display.translation();
        display.translation(new Vector3f(translation.getX(), translation.getY() + (float) offsetY,
            translation.getZ() + scale / 2F + 0.02F));
        display.billboard(style.billboard().metadataValue());
        display.brightness(style.packedBrightness());
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
        display.translation(new Vector3f(-scale / 2F, -scale / 2F, -scale / 2F));
        return display;
    }

    private static DisplayEntity entityDisplay(String hologramId, String key, Location position, double scale) {
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
        return DisplayEntity.Builder.entity(type, position.clone().subtract(0D,
            EntityLineBounds.forType(type).centerY() * RawEntityDimensions.normalizedScale(type, scale), 0D))
            .rawScale(scale);
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
