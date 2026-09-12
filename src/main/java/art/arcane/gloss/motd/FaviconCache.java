package art.arcane.gloss.motd;

import art.arcane.gloss.Gloss;
import art.arcane.gloss.image.ImageAssets;
import org.bukkit.Bukkit;
import org.bukkit.util.CachedServerIcon;

import java.awt.image.BufferedImage;
import java.io.IOException;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.BiConsumer;
import java.util.function.Function;

/**
 * Decodes a document's favicon once per document generation. Pings are unauthenticated, so nothing
 * here runs per ping: a refused file is remembered as refused until the document changes.
 */
public final class FaviconCache {
    public static final int REQUIRED_SIZE = 64;

    private final ImageSource images;
    private final Function<BufferedImage, CachedServerIcon> encoder;
    private final BiConsumer<String, String> onRefused;
    private final Map<String, Entry> cache = new ConcurrentHashMap<>();

    public FaviconCache(ImageAssets assets) {
        this(path -> assets.get(path).getValue(), FaviconCache::encode, FaviconCache::logRefusal);
    }

    FaviconCache(ImageSource images, Function<BufferedImage, CachedServerIcon> encoder,
                 BiConsumer<String, String> onRefused) {
        this.images = images;
        this.encoder = encoder;
        this.onRefused = onRefused;
    }

    public CachedServerIcon iconFor(String path, long docGeneration) {
        if (path == null || path.isBlank()) {
            return null;
        }
        Entry cached = cache.get(path);
        if (cached != null && cached.docGeneration() == docGeneration) {
            return cached.icon();
        }
        Entry built = decode(path);
        cache.put(path, new Entry(docGeneration, built.icon()));
        return built.icon();
    }

    public void clear() {
        cache.clear();
    }

    private Entry decode(String path) {
        BufferedImage image;
        try {
            image = images.load(path);
        } catch (IOException | RuntimeException failure) {
            onRefused.accept(path, failure.getMessage());
            return new Entry(0L, null);
        }
        if (image == null) {
            onRefused.accept(path, "the file could not be decoded");
            return new Entry(0L, null);
        }
        if (image.getWidth() != REQUIRED_SIZE || image.getHeight() != REQUIRED_SIZE) {
            onRefused.accept(path, "the server list icon must be " + REQUIRED_SIZE + "x" + REQUIRED_SIZE
                + ", this one is " + image.getWidth() + "x" + image.getHeight());
            return new Entry(0L, null);
        }
        try {
            return new Entry(0L, encoder.apply(image));
        } catch (RuntimeException failure) {
            onRefused.accept(path, failure.getMessage());
            return new Entry(0L, null);
        }
    }

    private static CachedServerIcon encode(BufferedImage image) {
        try {
            return Bukkit.loadServerIcon(image);
        } catch (Exception failure) {
            throw new IllegalStateException(failure.getMessage(), failure);
        }
    }

    private static void logRefusal(String path, String reason) {
        Gloss.warnThrottled("motd-favicon-" + path, "MOTD favicon \"%s\" was refused: %s.", path, reason);
    }

    @FunctionalInterface
    public interface ImageSource {
        BufferedImage load(String path) throws IOException;
    }

    private record Entry(long docGeneration, CachedServerIcon icon) {
    }
}
