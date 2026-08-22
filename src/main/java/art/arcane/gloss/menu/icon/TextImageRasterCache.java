package art.arcane.gloss.menu.icon;

import art.arcane.gloss.util.common.TextUtils;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.TextComponent;
import net.kyori.adventure.text.format.TextDecoration;

import java.awt.image.BufferedImage;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Shared rasters for the two image icons. Turning an image into text lines allocates styled
 * Components for color and transparency runs, and every session that opens the same menu used to
 * redo it; because Components are immutable the finished lines can simply be shared.
 *
 * <p>The cache key is the image's own pixels, so it needs no hot-reload hook: an edited file decodes
 * to different pixels and therefore misses. It is bounded and cleared whole when it overflows —
 * menus reference a handful of images, and a runaway key set is a bug, not a working set worth
 * keeping.
 */
final class TextImageRasterCache {

  static final int MAX_DIMENSION = 16;

  private static final int MAX_ENTRIES = 256;

  private static final Map<Raster, List<Component>> LINES = new ConcurrentHashMap<>();
  private static final Map<Integer, Component> BLANK_ROWS = new ConcurrentHashMap<>();

  private TextImageRasterCache() {
  }

  /**
   * The image as one text line per pixel row. {@code forceOpaque} drops the alpha test — JPEG has
   * no alpha channel, so its 0 bytes must not read as transparent.
   */
  static List<Component> lines(BufferedImage image, boolean forceOpaque) {
    int width = image.getWidth();
    int height = image.getHeight();
    if (width > MAX_DIMENSION || height > MAX_DIMENSION) {
      throw new IllegalArgumentException("Text images must be at most " + MAX_DIMENSION + " by "
          + MAX_DIMENSION + " pixels, but this image is " + width + " by " + height);
    }
    int[] pixels = image.getRGB(0, 0, width, height, null, 0, width);
    Raster key = new Raster(width, height, forceOpaque, pixels);
    List<Component> cached = LINES.get(key);
    if (cached != null) {
      return cached;
    }

    List<Component> built = build(pixels, width, height, forceOpaque);
    if (LINES.size() >= MAX_ENTRIES) {
      LINES.clear();
    }
    LINES.put(key, built);
    return built;
  }

  /** A fully transparent row of {@code width} pixels, used to pad short animation frames. */
  static Component blankRow(int width) {
    return BLANK_ROWS.computeIfAbsent(width, pixels -> transparentRun(pixels));
  }

  private static List<Component> build(int[] pixels, int width, int height, boolean forceOpaque) {
    List<Component> lines = new ArrayList<>(height);
    for (int y = 0; y < height; y++) {
      TextComponent.Builder line = Component.text();
      int row = y * width;
      int x = 0;
      while (x < width) {
        int colour = pixels[row + x];
        boolean transparent = !forceOpaque && ((colour >> 24) & 0x0000FF) < 255;
        int runEnd = x + 1;
        while (runEnd < width && sameRun(pixels[row + runEnd], colour, transparent, forceOpaque)) {
          runEnd++;
        }
        int length = runEnd - x;
        line.append(transparent
            ? transparentRun(length)
            : TextUtils.textColor("█".repeat(length), colour & 0x00FFFFFF));
        x = runEnd;
      }
      lines.add(line.build());
    }
    return List.copyOf(lines);
  }

  private static boolean sameRun(int candidate, int colour, boolean transparent, boolean forceOpaque) {
    boolean candidateTransparent = !forceOpaque && ((candidate >> 24) & 0x0000FF) < 255;
    return transparent ? candidateTransparent : !candidateTransparent && candidate == colour;
  }

  private static Component transparentRun(int length) {
    String spaces = " ".repeat(length);
    return Component.text().append(Component.text(spaces).decorate(TextDecoration.BOLD))
        .append(Component.text(spaces)).build();
  }

  /** Identity of a rendered raster: the pixels themselves plus how alpha was read. */
  private static final class Raster {
    private final int width;
    private final int height;
    private final boolean forceOpaque;
    private final int[] pixels;
    private final int hash;

    private Raster(int width, int height, boolean forceOpaque, int[] pixels) {
      this.width = width;
      this.height = height;
      this.forceOpaque = forceOpaque;
      this.pixels = pixels;
      this.hash = (Arrays.hashCode(pixels) * 31 + width) * 31 + (forceOpaque ? height : ~height);
    }

    @Override
    public boolean equals(Object other) {
      if (this == other) {
        return true;
      }
      if (!(other instanceof Raster raster)) {
        return false;
      }
      return hash == raster.hash
          && width == raster.width
          && height == raster.height
          && forceOpaque == raster.forceOpaque
          && Arrays.equals(pixels, raster.pixels);
    }

    @Override
    public int hashCode() {
      return hash;
    }
  }
}
