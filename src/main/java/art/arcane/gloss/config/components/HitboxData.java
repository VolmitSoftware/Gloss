package art.arcane.gloss.config.components;

import org.bukkit.util.Vector;

public record HitboxData(Float width, Float height, Vector offset, HitboxAnchor anchor) {

  public HitboxData {
    if ((width == null) != (height == null))
      throw new IllegalArgumentException("Button hitbox width and height must be supplied together.");
    if (width != null && (!Float.isFinite(width) || width <= 0F))
      throw new IllegalArgumentException("Button hitbox width must be finite and greater than zero.");
    if (height != null && (!Float.isFinite(height) || height <= 0F))
      throw new IllegalArgumentException("Button hitbox height must be finite and greater than zero.");
    if (offset != null && (!Double.isFinite(offset.getX()) || !Double.isFinite(offset.getY()) || !Double.isFinite(offset.getZ())))
      throw new IllegalArgumentException("Button hitbox offset must contain only finite values.");
  }

  public boolean hasCustomSize() {
    return width != null;
  }

  public HitboxAnchor anchorOrDefault() {
    return anchor == null ? HitboxAnchor.BUTTON : anchor;
  }

  public float scaledWidth(float scale) {
    return width * scale;
  }

  public float scaledHeight(float scale) {
    return height * scale;
  }

  public Vector scaledOffset(float scale) {
    return offset == null ? new Vector() : offset.clone().multiply(scale);
  }
}
