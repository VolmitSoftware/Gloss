package art.arcane.gloss.util.common;

import com.github.retrooper.packetevents.protocol.entity.type.EntityType;
import com.github.retrooper.packetevents.protocol.entity.type.EntityTypes;
import org.bukkit.Bukkit;

import java.lang.reflect.Method;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

public final class RawEntityDimensions {
  private static final Map<EntityType, Dimensions> DIMENSIONS = new ConcurrentHashMap<>();

  private RawEntityDimensions() {
  }

  public static boolean supportsScale(EntityType type) {
    return type.isInstanceOf(EntityTypes.LIVINGENTITY);
  }

  public static double normalizedScale(EntityType type, double scale) {
    if (!Double.isFinite(scale) || scale <= 0D) {
      throw new IllegalArgumentException("Entity scale must be finite and positive");
    }
    if (!supportsScale(type)) {
      if (scale != 1D) {
        throw new IllegalArgumentException("Entity type " + type.getName() + " does not support native scaling");
      }
      return 1D;
    }
    return Math.max(0.0625D, Math.min(16D, scale));
  }

  public static double renderedHeight(EntityType type, double scale) {
    return dimensions(type).height() * normalizedScale(type, scale);
  }

  public static double renderedWidth(EntityType type, double scale) {
    return dimensions(type).width() * normalizedScale(type, scale);
  }

  public static Dimensions dimensions(EntityType type) {
    return DIMENSIONS.computeIfAbsent(type, RawEntityDimensions::resolve);
  }

  private static Dimensions resolve(EntityType type) {
    try {
      if (Bukkit.getServer() == null) {
        throw new IllegalStateException("Native entity dimensions require an initialized Bukkit server");
      }
      Class<?> craftType = Class.forName(Bukkit.getServer().getClass().getPackageName() + ".entity.CraftEntityType");
      Method findType = craftType.getMethod("stringToBukkit", String.class);
      Object bukkitType = findType.invoke(null, type.getName().toString());
      if (bukkitType == null) {
        throw new IllegalStateException("Unknown Bukkit entity type " + type.getName());
      }
      Object nativeType = craftType.getMethod("bukkitToMinecraft", findType.getReturnType()).invoke(null, bukkitType);
      Object nativeDimensions = nativeType.getClass().getMethod("getDimensions").invoke(nativeType);
      float width = ((Number) nativeDimensions.getClass().getMethod("width").invoke(nativeDimensions)).floatValue();
      float height = ((Number) nativeDimensions.getClass().getMethod("height").invoke(nativeDimensions)).floatValue();
      return new Dimensions(width, height);
    } catch (ReflectiveOperationException failure) {
      throw new IllegalStateException("Cannot resolve native dimensions for entity " + type.getName(), failure);
    }
  }

  public record Dimensions(float width, float height) {
    public Dimensions {
      if (!Float.isFinite(width) || !Float.isFinite(height) || width < 0F || height < 0F) {
        throw new IllegalArgumentException("Native entity dimensions must be finite and nonnegative");
      }
    }
  }
}
