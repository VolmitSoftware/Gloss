package art.arcane.gloss.image;

import org.apache.commons.imaging.ImageFormat;
import org.apache.commons.lang3.tuple.Pair;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.awt.Color;
import java.awt.image.BufferedImage;
import java.io.File;
import java.io.IOException;

import javax.imageio.ImageIO;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Icon construction used to read and fully decode its picture from disk every time — once per
 * image icon per menu open per player, and once per frame for an animated one, all on the main
 * thread. The decode is now cached and the bytes on disk are what invalidates it, so an operator
 * editing a file still sees the edit without a reload.
 */
class ImageAssetsCacheTest {

    @TempDir
    File temp;

    @Test
    void theSameImageIsDecodedOnceAndSharedByEveryIcon() throws IOException {
        ImageAssets assets = new ImageAssets(temp);
        write(image(Color.RED), "icon.png", 1_700_000_000_000L);

        Pair<ImageFormat, BufferedImage> first = assets.get("icon.png");
        Pair<ImageFormat, BufferedImage> second = assets.get("icon.png");

        assertSame(first, second, "a second icon over the same file must not decode it again");
        assertEquals(Color.RED.getRGB(), first.getRight().getRGB(0, 0));
    }

    @Test
    void anEditedFileIsDecodedAgain() throws IOException {
        ImageAssets assets = new ImageAssets(temp);
        write(image(Color.RED), "icon.png", 1_700_000_000_000L);
        Pair<ImageFormat, BufferedImage> before = assets.get("icon.png");

        write(image(Color.BLUE), "icon.png", 1_700_000_060_000L);
        Pair<ImageFormat, BufferedImage> after = assets.get("icon.png");

        assertNotSame(before, after, "a changed modification time must invalidate the decode");
        assertEquals(Color.BLUE.getRGB(), after.getRight().getRGB(0, 0));
    }

    @Test
    void twoImagesAreCachedIndependently() throws IOException {
        ImageAssets assets = new ImageAssets(temp);
        write(image(Color.RED), "red.png", 1_700_000_000_000L);
        write(image(Color.GREEN), "green.png", 1_700_000_000_000L);

        assertEquals(Color.RED.getRGB(), assets.get("red.png").getRight().getRGB(0, 0));
        assertEquals(Color.GREEN.getRGB(), assets.get("green.png").getRight().getRGB(0, 0));
        assertSame(assets.get("red.png"), assets.get("red.png"));
        assertEquals(Color.RED.getRGB(), assets.get("red.png").getRight().getRGB(0, 0));
    }

    private static BufferedImage image(Color colour) {
        BufferedImage image = new BufferedImage(2, 2, BufferedImage.TYPE_INT_ARGB);
        for (int x = 0; x < 2; x++) {
            for (int y = 0; y < 2; y++) {
                image.setRGB(x, y, colour.getRGB());
            }
        }
        return image;
    }

    private void write(BufferedImage image, String name, long modified) throws IOException {
        File images = new File(temp, ImageAssets.KIND);
        assertTrue(images.isDirectory() || images.mkdirs());
        File file = new File(images, name);
        ImageIO.write(image, "png", file);
        assertTrue(file.setLastModified(modified));
    }
}
