package art.arcane.gloss.motd;

import org.bukkit.util.CachedServerIcon;
import org.junit.jupiter.api.Test;

import java.awt.image.BufferedImage;
import java.io.IOException;
import java.lang.reflect.Proxy;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;

/**
 * A favicon is decoded once per document generation, never per ping, and a file that is not the
 * 64x64 the protocol requires is refused with the path named.
 */
class FaviconCacheTest {
    private final List<BufferedImage> encoded = new ArrayList<>();
    private final List<String> refusals = new ArrayList<>();

    @Test
    void aSquare64IconIsEncodedOnceAndServedFromTheMemo() {
        FaviconCache cache = cache(Map.of("icons/season4.png", image(64, 64)));

        CachedServerIcon first = cache.iconFor("icons/season4.png", 1L);
        CachedServerIcon second = cache.iconFor("icons/season4.png", 1L);

        assertNotNull(first);
        assertSame(first, second);
        assertEquals(1, encoded.size());
        assertEquals(List.of(), refusals);
    }

    @Test
    void aNewDocumentGenerationDecodesAgain() {
        FaviconCache cache = cache(Map.of("icons/season4.png", image(64, 64)));

        cache.iconFor("icons/season4.png", 1L);
        cache.iconFor("icons/season4.png", 2L);

        assertEquals(2, encoded.size());
    }

    @Test
    void anIconThatIsNotSixtyFourSquareIsRefusedWithItsPath() {
        FaviconCache cache = cache(Map.of("icons/small.png", image(32, 32)));

        assertNull(cache.iconFor("icons/small.png", 1L));
        assertEquals(List.of("icons/small.png"), refusals);
        assertEquals(List.of(), encoded);
    }

    @Test
    void aRefusedIconIsNotRetriedUntilTheDocumentChanges() {
        FaviconCache cache = cache(Map.of("icons/small.png", image(32, 16)));

        cache.iconFor("icons/small.png", 1L);
        cache.iconFor("icons/small.png", 1L);

        assertEquals(1, refusals.size());
    }

    @Test
    void aMissingFileIsRefusedInsteadOfThrowingIntoThePingPath() {
        FaviconCache cache = cache(Map.of());

        assertNull(cache.iconFor("icons/gone.png", 1L));
        assertEquals(List.of("icons/gone.png"), refusals);
    }

    @Test
    void anAbsentPathAsksForNothing() {
        FaviconCache cache = cache(Map.of());

        assertNull(cache.iconFor(null, 1L));
        assertNull(cache.iconFor("  ", 1L));
        assertEquals(List.of(), refusals);
    }

    private FaviconCache cache(Map<String, BufferedImage> files) {
        return new FaviconCache(path -> {
            BufferedImage image = files.get(path);
            if (image == null) {
                throw new IOException("no such image " + path);
            }
            return image;
        }, image -> {
            encoded.add(image);
            return icon();
        }, (path, reason) -> refusals.add(path));
    }

    private static BufferedImage image(int width, int height) {
        return new BufferedImage(width, height, BufferedImage.TYPE_INT_ARGB);
    }

    private static CachedServerIcon icon() {
        return (CachedServerIcon) Proxy.newProxyInstance(CachedServerIcon.class.getClassLoader(),
            new Class<?>[]{CachedServerIcon.class}, (proxy, method, args) -> switch (method.getName()) {
                case "hashCode" -> System.identityHashCode(proxy);
                case "equals" -> proxy == args[0];
                case "toString" -> "CachedServerIcon";
                default -> null;
            });
    }
}
