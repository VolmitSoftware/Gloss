package art.arcane.gloss.image;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.File;
import java.io.FileNotFoundException;
import java.io.IOException;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ImageAssetsPathTest {

    @TempDir
    File temp;

    @Test
    void resolvesOnlyFilesInsideTheImageDirectory() throws IOException {
        File root = new File(temp, "images");
        File nested = new File(root, "icons/menu.png");
        assertTrue(nested.getParentFile().mkdirs());
        assertTrue(nested.createNewFile());

        assertEquals(nested.getCanonicalFile(), ImageAssets.resolve(root, "icons/menu.png"));
    }

    @Test
    void rejectsMissingBlankAndEscapingPaths() throws IOException {
        File base = new File(temp, "base");
        File root = new File(base, "images");
        File outside = new File(base, "outside.png");
        assertTrue(root.mkdirs());
        assertTrue(outside.createNewFile());

        assertThrows(FileNotFoundException.class, () -> ImageAssets.resolve(root, null));
        assertThrows(FileNotFoundException.class, () -> ImageAssets.resolve(root, "  "));
        assertThrows(FileNotFoundException.class, () -> ImageAssets.resolve(root, "missing.png"));
        assertThrows(FileNotFoundException.class, () -> ImageAssets.resolve(root, "../outside.png"));
        assertThrows(FileNotFoundException.class,
            () -> ImageAssets.resolve(root, outside.getAbsolutePath()));
        assertThrows(FileNotFoundException.class, () -> ImageAssets.resolve(null, "menu.png"));
    }
}
