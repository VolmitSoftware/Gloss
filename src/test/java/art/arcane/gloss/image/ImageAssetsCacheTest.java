package art.arcane.gloss.image;

import art.arcane.gloss.Gloss;
import art.arcane.gloss.GlossConfig;
import art.arcane.gloss.doc.DataWatchdog;
import art.arcane.gloss.menu.CharacterizationSupport;
import art.arcane.volmlib.util.collection.KList;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import javax.imageio.ImageIO;
import java.awt.Color;
import java.awt.image.BufferedImage;
import java.io.File;
import java.lang.reflect.Method;
import java.nio.file.Files;
import java.util.Map;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.atomic.AtomicReference;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ImageAssetsCacheTest {
  @TempDir File root;

  @Test
  void preparedLookupsShareImmutableRowsAndNeverRevalidateTheFilesystem() throws Exception {
    File file = write("icon.png", Color.RED, 2);
    try (ImageAssets assets = assets(defaults())) {
      ImageAssets.PreparedImage first = assets.request("icon.png").get(5, TimeUnit.SECONDS);
      Files.delete(file.toPath());
      assertSame(first, assets.prepared("icon.png").orElseThrow());
      assertSame(first, assets.request("./icon.png").get(5, TimeUnit.SECONDS));
      assertThrows(UnsupportedOperationException.class, () -> first.rows().clear());
      assertEquals(1, assets.snapshot().completed());
    }
  }

  @Test
  void invalidatingOneAssetKeepsUnchangedImagesAndRepreparesChangedPixels() throws Exception {
    write("icon.png", Color.RED, 2);
    write("other.png", Color.GREEN, 2);
    try (ImageAssets assets = assets(defaults())) {
      ImageAssets.PreparedImage first = assets.request("icon.png").get(5, TimeUnit.SECONDS);
      ImageAssets.PreparedImage other = assets.request("other.png").get(5, TimeUnit.SECONDS);
      write("icon.png", Color.BLUE, 2);
      assets.invalidate("icon.png");
      ImageAssets.PreparedImage second = assets.request("icon.png").get(5, TimeUnit.SECONDS);
      assertNotSame(first, second);
      assertFalse(first.rows().equals(second.rows()));
      assertSame(other, assets.prepared("other.png").orElseThrow());
    }
  }

  @Test
  void cacheEvictsOnlyTheLeastRecentlyUsedAssetAtItsConfiguredEntryLimit() throws Exception {
    GlossConfig.Images defaults = defaults();
    GlossConfig.Images limits = new GlossConfig.Images(defaults.maxFileBytes(), defaults.maxPixels(),
        defaults.maxDimension(), defaults.rasterMaxDimension(), defaults.cacheBytes(), 2, 8, 1);
    write("a.png", Color.RED, 2);
    write("b.png", Color.BLUE, 2);
    write("c.png", Color.GREEN, 2);
    try (ImageAssets assets = assets(limits)) {
      ImageAssets.PreparedImage a = assets.request("a.png").get(5, TimeUnit.SECONDS);
      assets.request("b.png").get(5, TimeUnit.SECONDS);
      assertSame(a, assets.prepared("a.png").orElseThrow());
      assets.request("c.png").get(5, TimeUnit.SECONDS);
      assertEquals(2, assets.snapshot().cachedEntries());
      assertSame(a, assets.prepared("a.png").orElseThrow());
      assertTrue(assets.snapshot().retainedWeight() <= limits.cacheBytes());
    }
  }

  @Test
  void sourceLimitsRejectOversizedHeadersAndByteCountsBeforeRasterDecoding() throws Exception {
    write("wide.png", Color.RED, 32);
    Files.write(root.toPath().resolve("images/large.png"), new byte[1025]);
    GlossConfig.Images limits = new GlossConfig.Images(1024, 256, 16, 16, 1048576, 8, 8, 1);
    try (ImageAssets assets = assets(limits)) {
      assertThrows(ExecutionException.class, () -> assets.request("wide.png").get(5, TimeUnit.SECONDS));
      assertThrows(ExecutionException.class, () -> assets.request("large.png").get(5, TimeUnit.SECONDS));
      assertEquals(2, assets.snapshot().completed());
    }
  }

  @Test
  void largePackImagesKeepDimensionsWithoutRetainingRasterRows() throws Exception {
    write("glyph.png", Color.RED, 32);
    try (ImageAssets assets = assets(defaults())) {
      ImageAssets.PreparedImage image = assets.request("glyph.png").get(5, TimeUnit.SECONDS);
      assertEquals(32, image.width());
      assertTrue(image.rows().isEmpty());
      assertTrue(image.weight() < 1024);
    }
  }

  @Test
  void configurableRasterDimensionPreparesMoreRowsWithoutChangingDefaultLimits() throws Exception {
    write("wide.png", Color.RED, 24);
    GlossConfig.Images defaults = defaults();
    GlossConfig.Images limits = new GlossConfig.Images(defaults.maxFileBytes(), defaults.maxPixels(),
        defaults.maxDimension(), 32, defaults.cacheBytes(), 8, 8, 1);
    try (ImageAssets assets = assets(limits)) {
      assertEquals(24, assets.request("wide.png").get(5, TimeUnit.SECONDS).rows().size());
    }
  }

  @Test
  void sourceLimitReloadInvalidatesPreviouslyAcceptedSnapshots() throws Exception {
    write("wide.png", Color.RED, 32);
    AtomicReference<GlossConfig.Images> limits = new AtomicReference<>(defaults());
    try (ImageAssets assets = new ImageAssets(root, limits::get)) {
      assets.request("wide.png").get(5, TimeUnit.SECONDS);
      limits.set(new GlossConfig.Images(1024, 256, 16, 16, 1048576, 8, 8, 1));
      assertThrows(ExecutionException.class, () -> assets.request("wide.png").get(5, TimeUnit.SECONDS));
    }
  }

  @Test
  void preparedFaviconReturnsCopiesWithoutChangingStoredPixels() throws Exception {
    write("favicon.png", Color.RED, 64);
    try (ImageAssets assets = assets(defaults())) {
      ImageAssets.PreparedImage prepared = assets.request("favicon.png").get(5, TimeUnit.SECONDS);
      BufferedImage first = prepared.favicon().copyImage();
      first.setRGB(0, 0, Color.BLUE.getRGB());
      assertEquals(Color.RED.getRGB(), prepared.favicon().copyImage().getRGB(0, 0));
      assertTrue(prepared.weight() >= 64 * 64 * 4);
    }
  }

  @Test
  void closingRejectsNewWorkAndReleasesCachedWeight() throws Exception {
    write("icon.png", Color.RED, 2);
    ImageAssets assets = assets(defaults());
    assets.request("icon.png").get(5, TimeUnit.SECONDS);
    assets.close();
    assertEquals(0, assets.snapshot().retainedWeight());
    assertThrows(ExecutionException.class, () -> assets.request("icon.png").get(5, TimeUnit.SECONDS));
  }

  @Test
  void directoryEventsPrepareOnlyNestedFilesAndDeletionStillInvalidatesThem() throws Exception {
    File directory = new File(root, "images/icons");
    assertTrue(directory.mkdirs());
    File image = write("icons/icon.png", Color.RED, 2);
    Gloss previous = Gloss.instance;
    Gloss plugin = CharacterizationSupport.bareGloss(CharacterizationSupport.server(Map.of()));
    CharacterizationSupport.setField(plugin, "watchdog", new DataWatchdog(plugin));
    Gloss.instance = plugin;
    Method apply = ImageAssets.class.getDeclaredMethod("applyChanges", KList.class, KList.class, KList.class);
    apply.setAccessible(true);
    try (ImageAssets assets = assets(defaults())) {
      apply.invoke(assets, new KList<>(directory, image), new KList<>(), new KList<>());
      assertEquals(2, assets.request("icons/icon.png").get(5, TimeUnit.SECONDS).width());
      assertEquals(1, assets.snapshot().completed());
      assertEquals(1, assets.snapshot().cachedEntries());
      Files.delete(image.toPath());
      apply.invoke(assets, new KList<>(), new KList<>(), new KList<>(image));
      assertEquals(0, assets.snapshot().cachedEntries());
    } finally {
      Gloss.instance = previous;
    }
  }

  private ImageAssets assets(GlossConfig.Images limits) {
    return new ImageAssets(root, () -> limits);
  }

  private static GlossConfig.Images defaults() {
    return GlossConfig.current().images();
  }

  private File write(String name, Color color, int size) throws Exception {
    File images = new File(root, ImageAssets.KIND);
    assertTrue(images.isDirectory() || images.mkdirs());
    BufferedImage image = new BufferedImage(size, size, BufferedImage.TYPE_INT_ARGB);
    for (int x = 0; x < size; x++) {
      for (int y = 0; y < size; y++) {
        image.setRGB(x, y, color.getRGB());
      }
    }
    File file = new File(images, name);
    ImageIO.write(image, "png", file);
    return file;
  }
}
