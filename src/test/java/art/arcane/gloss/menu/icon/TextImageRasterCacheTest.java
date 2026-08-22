package art.arcane.gloss.menu.icon;

import net.kyori.adventure.text.Component;
import org.junit.Test;

import java.awt.image.BufferedImage;
import java.util.List;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotEquals;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertThrows;

/**
 * Pins the shared image raster (plan item C8): two icons built from the same picture share one
 * immutable line list instead of each allocating two Components per pixel, and the cache is keyed
 * on the pixels themselves so an edited image cannot be served stale.
 */
public class TextImageRasterCacheTest {

  @Test
  public void identicalImagesShareTheSameLines() {
    List<Component> first = TextImageRasterCache.lines(checker(4, 3, 0xFF204060), false);
    List<Component> second = TextImageRasterCache.lines(checker(4, 3, 0xFF204060), false);

    assertSame("two icons built from the same picture must share one raster", first, second);
    assertEquals(3, first.size());
  }

  @Test
  public void aChangedPixelIsADifferentRaster() {
    List<Component> original = TextImageRasterCache.lines(checker(4, 3, 0xFF204060), false);
    List<Component> edited = TextImageRasterCache.lines(checker(4, 3, 0xFF204061), false);

    assertNotEquals("an edited image must miss the cache — this is what replaces a hotload hook",
        original, edited);
  }

  @Test
  public void forcingOpaqueIsPartOfTheIdentity() {
    BufferedImage transparent = solid(2, 1, 0x00112233);

    List<Component> alphaAware = TextImageRasterCache.lines(transparent, false);
    List<Component> forcedOpaque = TextImageRasterCache.lines(transparent, true);

    assertNotEquals("a JPEG's zero alpha bytes must not be read as transparency",
        alphaAware, forcedOpaque);
    assertSame(alphaAware, TextImageRasterCache.lines(solid(2, 1, 0x00112233), false));
  }

  @Test
  public void transparentPixelsShareOneBoldAndPlainRun() {
    List<Component> lines = TextImageRasterCache.lines(solid(2, 1, 0x00000000), false);

    assertEquals(1, lines.getFirst().children().size());
    assertEquals(2, lines.getFirst().children().getFirst().children().size());
  }

  @Test
  public void sameColourPixelsShareOneTextRun() {
    List<Component> lines = TextImageRasterCache.lines(solid(8, 1, 0xFF204060), false);

    assertEquals(1, lines.getFirst().children().size());
  }

  @Test
  public void imagesLargerThanTheRuntimeBudgetAreRejected() {
    IllegalArgumentException error = assertThrows(IllegalArgumentException.class,
        () -> TextImageRasterCache.lines(solid(17, 16, 0xFF204060), false));

    assertEquals("Text images must be at most 16 by 16 pixels, but this image is 17 by 16",
        error.getMessage());
  }

  @Test
  public void blankRowsOfTheSameWidthAreShared() {
    assertSame(TextImageRasterCache.blankRow(6), TextImageRasterCache.blankRow(6));
    assertNotEquals(TextImageRasterCache.blankRow(6), TextImageRasterCache.blankRow(7));
  }

  private static BufferedImage solid(int width, int height, int argb) {
    BufferedImage image = new BufferedImage(width, height, BufferedImage.TYPE_INT_ARGB);
    for (int y = 0; y < height; y++) {
      for (int x = 0; x < width; x++) {
        image.setRGB(x, y, argb);
      }
    }
    return image;
  }

  private static BufferedImage checker(int width, int height, int argb) {
    BufferedImage image = new BufferedImage(width, height, BufferedImage.TYPE_INT_ARGB);
    for (int y = 0; y < height; y++) {
      for (int x = 0; x < width; x++) {
        image.setRGB(x, y, (x + y) % 2 == 0 ? argb : 0xFF000000);
      }
    }
    return image;
  }
}
