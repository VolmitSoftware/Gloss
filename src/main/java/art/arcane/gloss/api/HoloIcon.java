package art.arcane.gloss.api;

import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.entity.EntityType;
import org.bukkit.inventory.ItemStack;

import java.util.List;
import java.util.Objects;

public sealed interface HoloIcon permits HoloIcon.Text, HoloIcon.Item, HoloIcon.Block, HoloIcon.Image, HoloIcon.AnimatedImage, HoloIcon.Entity {

  record Text(String miniMessage, IconDisplayStyle style, HologramBox box, Integer refreshTicks) implements HoloIcon {
    public Text {
      miniMessage = HoloText.sanitizeMarkup(miniMessage);
      if (refreshTicks != null && (refreshTicks < 0 || refreshTicks > 1200)) {
        throw new IllegalArgumentException("refreshTicks must be between 0 and 1200");
      }
    }

    public Text withStyle(IconDisplayStyle value) {
      return new Text(miniMessage, value, box, refreshTicks);
    }

    public Text withBox(HologramBox value) {
      return new Text(miniMessage, style, value, refreshTicks);
    }

    public Text withRefreshTicks(int value) {
      return new Text(miniMessage, style, box, value);
    }
  }

  record Item(ItemStack stack, IconDisplayStyle style) implements HoloIcon {
    public Item {
      Objects.requireNonNull(stack, "stack");
      stack = stack.clone();
    }

    public Item withStyle(IconDisplayStyle value) {
      return new Item(stack, value);
    }

    @Override
    public ItemStack stack() {
      return stack.clone();
    }
  }

  record Block(Material material, IconDisplayStyle style) implements HoloIcon {
    public Block {
      Objects.requireNonNull(material, "material");
      if (Bukkit.getServer() != null && !material.isBlock()) {
        throw new IllegalArgumentException("material must be a block");
      }
    }
    public Block withStyle(IconDisplayStyle value) {
      return new Block(material, value);
    }
  }

  record Image(String relativePath, IconDisplayStyle style) implements HoloIcon {
    public Image {
      relativePath = HoloText.sanitizePath(relativePath);
    }

    public Image withStyle(IconDisplayStyle value) {
      return new Image(relativePath, value);
    }
  }

  record AnimatedImage(List<String> relativePaths, int tickSpeed, IconDisplayStyle style) implements HoloIcon {
    public AnimatedImage {
      relativePaths = HoloText.sanitizePaths(relativePaths);
      if (tickSpeed < 2 || tickSpeed > 1200) {
        throw new IllegalArgumentException("tickSpeed must be between 2 and 1200");
      }
    }
    public AnimatedImage withStyle(IconDisplayStyle value) {
      return new AnimatedImage(relativePaths, tickSpeed, value);
    }
  }

  record Entity(EntityType entityType, float width, float height) implements HoloIcon {
    public Entity {
      Objects.requireNonNull(entityType, "entityType");
      if (!entityType.isSpawnable() || !entityType.isAlive()) {
        throw new IllegalArgumentException("entityType must be a spawnable living entity");
      }
      if (!Float.isFinite(width) || width <= 0F || width > 64F) {
        throw new IllegalArgumentException("width must be finite, greater than 0, and at most 64");
      }
      if (!Float.isFinite(height) || height <= 0F || height > 64F) {
        throw new IllegalArgumentException("height must be finite, greater than 0, and at most 64");
      }
    }
  }

  static Text text(String miniMessage) {
    return new Text(miniMessage, null, null, null);
  }

  static Item item(ItemStack stack) {
    return new Item(stack, null);
  }

  static Block block(Material material) {
    return new Block(material, null);
  }

  static Image image(String relativePath) {
    return new Image(relativePath, null);
  }

  static AnimatedImage animatedImage(List<String> relativePaths, int tickSpeed) {
    return new AnimatedImage(relativePaths, tickSpeed, null);
  }

  static Entity entity(EntityType entityType, float width, float height) {
    return new Entity(entityType, width, height);
  }
}
