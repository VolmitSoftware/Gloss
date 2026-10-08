package art.arcane.gloss.motd;

import art.arcane.gloss.Gloss;
import art.arcane.gloss.image.ImageAssets;
import org.apache.commons.imaging.ImageFormat;
import org.apache.commons.imaging.ImageFormats;
import org.apache.commons.lang3.tuple.Pair;
import org.bukkit.Bukkit;
import org.bukkit.util.CachedServerIcon;

import java.awt.image.BufferedImage;
import java.io.IOException;
import java.lang.ref.WeakReference;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.BiConsumer;
import java.util.function.Function;

/**
 * Decodes a document's favicon once per document generation. Pings are unauthenticated, so nothing
 * here runs per ping: a refused file is remembered as refused until the document changes.
 */
public final class FaviconCache {
    public static final int REQUIRED_SIZE = 64;

    private final ImageAssets assets;
    private final ImageSource images;
    private final Function<BufferedImage, CachedServerIcon> encoder;
    private final BiConsumer<String, String> onRefused;
    private final Map<String, Entry> cache = new ConcurrentHashMap<>();

    public FaviconCache(ImageAssets assets) {
        this.assets = assets;
        this.images = null;
        this.encoder = FaviconCache::encode;
        this.onRefused = FaviconCache::logRefusal;
    }

    FaviconCache(ImageSource images, Function<BufferedImage, CachedServerIcon> encoder,
                 BiConsumer<String, String> onRefused) {
        this.assets = null;
        this.images = images;
        this.encoder = encoder;
        this.onRefused = onRefused;
    }

    public CachedServerIcon iconFor(String path, long docGeneration) {
        if (path == null || path.isBlank()) {
            return null;
        }
        if (assets != null) {
            return preparedIcon(path, docGeneration);
        }
        Entry cached = cache.get(path);
        if (cached != null && cached.docGeneration() == docGeneration) {
            return cached.icon();
        }
        Entry built = decode(path);
        cache.put(path, new Entry(docGeneration, built.icon(), null));
        return built.icon();
    }

    public void clear() {
        cache.clear();
    }

    private CachedServerIcon preparedIcon(String path, long generation) {
        ImageAssets.PreparedImage prepared;
        try {
            Optional<ImageAssets.PreparedImage> ready = assets.prepared(path);
            if (ready.isEmpty()) {
                return null;
            }
            prepared = ready.get();
        } catch (IOException failure) {
            return null;
        }
        Entry cached = cache.get(path);
        if (cached != null && cached.docGeneration() == generation && cached.source() != null && cached.source().get() == prepared) {
            return cached.icon();
        }
        CachedServerIcon icon = null;
        if (prepared.favicon() == null) {
            onRefused.accept(path, "the server list icon must be a 64x64 PNG file, this one is "
                + prepared.width() + "x" + prepared.height() + " " + prepared.format().getName());
        } else {
            try {
                icon = encoder.apply(prepared.favicon().copyImage());
            } catch (RuntimeException failure) {
                onRefused.accept(path, failure.getMessage());
            }
        }
        cache.put(path, new Entry(generation, icon, new WeakReference<>(prepared)));
        return icon;
    }

    private Entry decode(String path) {
        Pair<ImageFormat, BufferedImage> loaded;
        try {
            loaded = images.load(path);
        } catch (IOException | RuntimeException failure) {
            onRefused.accept(path, failure.getMessage());
            return new Entry(0L, null, null);
        }
        if (loaded == null || loaded.getKey() != ImageFormats.PNG) {
            onRefused.accept(path, "the server list icon must be a PNG file, this one is "
                + (loaded == null || loaded.getKey() == null ? "not a recognised image" : loaded.getKey().getName()));
            return new Entry(0L, null, null);
        }
        BufferedImage image = loaded.getValue();
        if (image == null) {
            onRefused.accept(path, "the file could not be decoded");
            return new Entry(0L, null, null);
        }
        if (image.getWidth() != REQUIRED_SIZE || image.getHeight() != REQUIRED_SIZE) {
            onRefused.accept(path, "the server list icon must be " + REQUIRED_SIZE + "x" + REQUIRED_SIZE
                + ", this one is " + image.getWidth() + "x" + image.getHeight());
            return new Entry(0L, null, null);
        }
        try {
            return new Entry(0L, encoder.apply(image), null);
        } catch (RuntimeException failure) {
            onRefused.accept(path, failure.getMessage());
            return new Entry(0L, null, null);
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
        Pair<ImageFormat, BufferedImage> load(String path) throws IOException;
    }

    private record Entry(long docGeneration, CachedServerIcon icon, WeakReference<ImageAssets.PreparedImage> source) {
    }
}
