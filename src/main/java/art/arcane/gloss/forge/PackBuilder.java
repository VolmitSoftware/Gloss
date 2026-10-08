package art.arcane.gloss.forge;

import art.arcane.gloss.doc.AtomicFiles;
import art.arcane.gloss.GlossConfig;
import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import org.apache.commons.imaging.Imaging;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.StandardCopyOption;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.attribute.BasicFileAttributes;
import java.security.MessageDigest;
import java.security.DigestOutputStream;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.TreeMap;
import java.util.function.Supplier;
import java.util.zip.CRC32;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

/**
 * Compiles the glyph registry into a resource pack. The output is byte-for-byte reproducible from
 * the same inputs: entries are written in sorted order, stored rather than deflated, and carry no
 * timestamp, so the sha1 a client verifies only changes when the glyphs do. Everything lands in a
 * plain directory first, which is what a third-party pack merger consumes.
 */
public final class PackBuilder {
    public static final String PACK_DIRECTORY = "pack";
    public static final String ZIP_NAME = "gloss-pack.zip";
    public static final String SHA1_NAME = "gloss-pack.sha1";
    public static final String DESCRIPTION = "Gloss glyphs";

    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().disableHtmlEscaping().create();
    private static final byte[] PNG_MAGIC = {(byte) 0x89, 'P', 'N', 'G', '\r', '\n', 0x1A, '\n'};
    private static final int PNG_HEADER_LENGTH = 33;

    private final Path imagesRoot;
    private final Supplier<GlossConfig.Images> settings;
    private final Supplier<GlossConfig.PackLimits> limits;
    private final PackArtifactStore artifacts;
    private final Map<Path, PackArtifactStore> outputStores = new HashMap<>();

    public PackBuilder(Path imagesRoot) {
        this(imagesRoot, () -> GlossConfig.current().images());
    }

    public PackBuilder(Path imagesRoot, Supplier<GlossConfig.Images> settings) {
        this(new Options(imagesRoot, settings, () -> GlossConfig.PackLimits.DEFAULT, new PackArtifactStore()));
    }

    public PackBuilder(Options options) {
        this.imagesRoot = Objects.requireNonNull(options.imagesRoot(), "imagesRoot");
        this.settings = Objects.requireNonNull(options.images(), "images");
        this.limits = Objects.requireNonNull(options.limits(), "limits");
        this.artifacts = Objects.requireNonNull(options.artifacts(), "artifacts");
    }

    public record Options(Path imagesRoot, Supplier<GlossConfig.Images> images,
                          Supplier<GlossConfig.PackLimits> limits, PackArtifactStore artifacts) {
    }

    /** The image size reader the registry uses to derive advance widths from the same files. */
    public GlyphRegistry.ImageProbe probe() {
        return imagePath -> {
            try {
                return pngSize(source(imagePath));
            } catch (IOException | RuntimeException unreadable) {
                return null;
            }
        };
    }

    /**
     * @param outDir the {@code forge/out} folder; the mergeable tree lands in {@code pack/} beneath it
     * @throws IllegalArgumentException when an image is missing or the client would refuse it
     */
    public synchronized PackArtifact build(GlyphRegistry registry, Path outDir, int packFormat) throws IOException {
        Objects.requireNonNull(registry, "registry");
        Objects.requireNonNull(outDir, "outDir");
        PackArtifactStore store = outputStores.computeIfAbsent(outDir.toAbsolutePath().normalize(),
            ignored -> outputStores.isEmpty() ? artifacts : new PackArtifactStore());
        GlossConfig.PackLimits buildLimits = limits.get();
        Budget budget = new Budget(buildLimits);
        if (registry.all().size() > buildLimits.maxBuildFiles()) {
            throw new IOException("Resource pack exceeds the generated file budget");
        }
        Files.createDirectories(outDir);
        Path staging = Files.createTempDirectory(outDir, ".build-");
        boolean retain = false;
        try {
            Path directory = staging.resolve(PACK_DIRECTORY);
            Map<String, Path> files = new TreeMap<>();
            write(files, directory, "pack.mcmeta", ascii(mcmeta(packFormat)), budget);
            Map<String, List<GlyphRegistry.ResolvedGlyph>> fonts = new TreeMap<>();
            fonts.put(registry.namespace() + "/font/" + registry.font(), new ArrayList<>());
            for (GlyphRegistry.ResolvedGlyph glyph : registry.all()) {
                fonts.computeIfAbsent(glyph.namespace() + "/font/" + glyph.font(), ignored -> new ArrayList<>()).add(glyph);
            }
            for (Map.Entry<String, List<GlyphRegistry.ResolvedGlyph>> font : fonts.entrySet()) {
                write(files, directory, "assets/" + font.getKey() + ".json", ascii(font(registry, font.getValue())), budget);
            }
            GlossConfig.Images limits = settings.get();
            for (GlyphRegistry.ResolvedGlyph glyph : registry.all()) {
                write(files, directory, "assets/" + glyph.namespace() + "/textures/font/" + glyph.id() + ".png",
                    texture(glyph, limits, budget), budget);
            }
            for (GlyphRegistry.WaypointAsset asset : new TreeMap<>(registry.waypointStyles()).values()) {
                JsonObject style = new JsonObject();
                style.addProperty("near_distance", asset.style().nearDistance());
                style.addProperty("far_distance", asset.style().farDistance());
                JsonArray sprites = new JsonArray();
                for (GlyphDoc.WaypointSprite sprite : asset.style().sprites()) {
                    sprites.add(asset.namespace() + ":" + sprite.id());
                    String target = "assets/" + asset.namespace() + "/textures/gui/sprites/hud/locator_bar_dot/"
                        + sprite.id() + ".png";
                    if (!files.containsKey(target)) {
                        write(files, directory, target, texture(sprite.image(), sprite.id(), limits, budget), budget);
                    }
                }
                style.add("sprites", sprites);
                write(files, directory, "assets/" + asset.namespace() + "/waypoint_style/" + asset.style().id()
                    + ".json", ascii(GSON.toJson(style) + "\n"), budget);
            }
            byte[] sha1 = zip(files, staging.resolve(ZIP_NAME), buildLimits.maxBuildBytes());
            String sha1Hex = hex(sha1);
            Files.writeString(staging.resolve(SHA1_NAME), sha1Hex + "\n", StandardCharsets.UTF_8);
            Path immutableZip = outDir.resolve("artifacts").resolve(sha1Hex + ".zip");
            PackArtifact artifact = new PackArtifact(outDir.resolve(PACK_DIRECTORY), immutableZip, sha1, sha1Hex,
                System.currentTimeMillis());
            try (PackArtifactStore.Lease retained = store.install(artifact, staging.resolve(ZIP_NAME), buildLimits)) {
                publish(staging, outDir);
                store.published(artifact);
            } catch (IOException failure) {
                retain = Files.exists(staging.resolve("previous"));
                throw failure;
            }
            return artifact;
        } finally {
            if (!retain) {
                deleteTree(staging);
            }
        }
    }

    private static void write(Map<String, Path> files, Path directory, String name, byte[] content, Budget budget) throws IOException {
        budget.file(content.length);
        Path target = directory.resolve(name);
        AtomicFiles.createParentDirectories(target);
        Files.write(target, content);
        files.put(name, target);
    }

    private static void publish(Path staging, Path output) throws IOException {
        List<String> names = List.of(PACK_DIRECTORY, ZIP_NAME, SHA1_NAME);
        Path backup = staging.resolve("previous");
        Files.createDirectories(backup);
        List<String> saved = new ArrayList<>();
        List<String> installed = new ArrayList<>();
        try {
            for (String name : names) {
                Path target = output.resolve(name);
                if (Files.exists(target)) {
                    move(target, backup.resolve(name));
                    saved.add(name);
                }
                move(staging.resolve(name), target);
                installed.add(name);
            }
        } catch (IOException failure) {
            boolean restored = true;
            for (String name : names.reversed()) {
                try {
                    if (installed.contains(name)) {
                        deleteTree(output.resolve(name));
                    }
                    if (saved.contains(name)) {
                        move(backup.resolve(name), output.resolve(name));
                    }
                } catch (IOException rollback) {
                    restored = false;
                    failure.addSuppressed(rollback);
                }
            }
            if (restored) {
                deleteTree(backup);
            } else {
                failure.addSuppressed(new IOException("Previous pack output retained at " + backup));
            }
            throw failure;
        }
        deleteTree(backup);
    }

    private static void move(Path source, Path target) throws IOException {
        try {
            Files.move(source, target, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
        } catch (AtomicMoveNotSupportedException unsupported) {
            Files.move(source, target, StandardCopyOption.REPLACE_EXISTING);
        }
    }

    private Path source(String relative) throws IOException {
        Path root = imagesRoot.toRealPath();
        Path source = root.resolve(relative).toRealPath();
        if (!source.startsWith(root) || !Files.isRegularFile(source)) {
            throw new IOException("Image must be a regular file inside images/: " + relative);
        }
        return source;
    }

    private byte[] texture(GlyphRegistry.ResolvedGlyph glyph, GlossConfig.Images limits, Budget budget) throws IOException {
        byte[] content = texture(glyph.image(), glyph.id(), limits, budget);
        int[] size = pngSize(content);
        if (size[1] > GlyphDoc.MAX_HEIGHT) {
            throw new IllegalArgumentException("glyph " + glyph.id() + ": images/" + glyph.image() + " is "
                + size[0] + "x" + size[1] + "; a font texture is at most " + GlyphDoc.MAX_HEIGHT + " pixels tall");
        }
        if (glyph.frames() > 1 && size[0] % glyph.frames() != 0) {
            throw new IllegalArgumentException("glyph " + glyph.id() + ": images/" + glyph.image() + " is "
                + size[0] + " pixels wide, which is not a multiple of its " + glyph.frames() + " frames");
        }
        return content;
    }

    private byte[] texture(String image, String id, GlossConfig.Images limits, Budget budget) throws IOException {
        Path source;
        try {
            source = source(image);
        } catch (IOException failure) {
            throw new IllegalArgumentException("asset " + id + ": images/" + image + " is unavailable", failure);
        }
        byte[] content;
        try (InputStream input = Files.newInputStream(source)) {
            content = input.readNBytes((int) Math.min(limits.maxFileBytes(), budget.remainingBytes()) + 1);
        }
        if (content.length > limits.maxFileBytes()) {
            throw new IllegalArgumentException("asset " + id + ": image exceeds configured byte limit");
        }
        if (content.length > budget.remainingBytes()) {
            throw new IOException("Resource pack exceeds the generated byte budget");
        }
        int[] size = pngSize(content);
        if (size == null) {
            throw new IllegalArgumentException("asset " + id + ": images/" + image + " is not a PNG");
        }
        if (size[0] < 1 || size[1] < 1 || size[0] > limits.maxDimension() || size[1] > limits.maxDimension()
            || (long) size[0] * size[1] > limits.maxPixels()) {
            throw new IllegalArgumentException("asset " + id + ": image exceeds configured dimension limits");
        }
        budget.pixels((long) size[0] * size[1]);
        Imaging.getBufferedImage(content);
        return content;
    }

    private static String mcmeta(int packFormat) {
        JsonObject pack = new JsonObject();
        if (packFormat >= 65) {
            pack.addProperty("min_format", packFormat);
            pack.addProperty("max_format", packFormat);
        } else {
            pack.addProperty("pack_format", packFormat);
        }
        pack.addProperty("description", DESCRIPTION);
        JsonObject root = new JsonObject();
        root.add("pack", pack);
        return GSON.toJson(root) + "\n";
    }

    private static String font(GlyphRegistry registry, List<GlyphRegistry.ResolvedGlyph> glyphs) {
        JsonArray providers = new JsonArray();
        for (GlyphRegistry.ResolvedGlyph glyph : glyphs) {
            JsonObject provider = new JsonObject();
            provider.addProperty("type", "bitmap");
            provider.addProperty("file", glyph.namespace() + ":font/" + glyph.id() + ".png");
            provider.addProperty("height", glyph.height());
            provider.addProperty("ascent", glyph.ascent());
            JsonArray chars = new JsonArray();
            StringBuilder row = new StringBuilder();
            for (int frame = 0; frame < glyph.frames(); frame++) {
                row.append(glyph.character(frame));
            }
            chars.add(row.toString());
            provider.add("chars", chars);
            providers.add(provider);
        }
        if (registry.spaces().enabled()) {
            JsonObject advances = new JsonObject();
            for (Map.Entry<Integer, Integer> entry : new TreeMap<>(registry.spaces().codepoints()).entrySet()) {
                advances.addProperty(new String(Character.toChars(entry.getValue())), entry.getKey());
            }
            JsonObject provider = new JsonObject();
            provider.addProperty("type", "space");
            provider.add("advances", advances);
            providers.add(provider);
        }
        JsonObject root = new JsonObject();
        root.add("providers", providers);
        return GSON.toJson(root) + "\n";
    }

    /**
     * Font JSON travels through pack mergers, text editors and diff tools, so the private-use
     * codepoints go out as escapes rather than raw bytes.
     */
    private static byte[] ascii(String json) {
        StringBuilder out = new StringBuilder(json.length());
        for (int index = 0; index < json.length(); index++) {
            char character = json.charAt(index);
            if (character > 0x7E) {
                out.append(String.format(Locale.ROOT, "\\u%04X", (int) character));
            } else {
                out.append(character);
            }
        }
        return out.toString().getBytes(StandardCharsets.US_ASCII);
    }

    private static byte[] zip(Map<String, Path> files, Path destination, long maxBytes) throws IOException {
        MessageDigest digest;
        try {
            digest = MessageDigest.getInstance("SHA-1");
        } catch (NoSuchAlgorithmException impossible) {
            throw new IllegalStateException("SHA-1 is unavailable", impossible);
        }
        byte[] buffer = new byte[65536];
        try (OutputStream output = Files.newOutputStream(destination);
             OutputStream bounded = new BoundedOutput(output, maxBytes);
             DigestOutputStream hashed = new DigestOutputStream(bounded, digest);
             ZipOutputStream zip = new ZipOutputStream(hashed, StandardCharsets.UTF_8)) {
            zip.setMethod(ZipOutputStream.STORED);
            List<String> names = new ArrayList<>(files.keySet());
            names.sort(Comparator.naturalOrder());
            for (String name : names) {
                Path source = files.get(name);
                CRC32 crc = new CRC32();
                try (InputStream input = Files.newInputStream(source)) {
                    int read;
                    while ((read = input.read(buffer)) >= 0) {
                        crc.update(buffer, 0, read);
                    }
                }
                long size = Files.size(source);
                ZipEntry entry = new ZipEntry(name);
                entry.setMethod(ZipEntry.STORED);
                entry.setSize(size);
                entry.setCompressedSize(size);
                entry.setCrc(crc.getValue());
                entry.setTime(0L);
                zip.putNextEntry(entry);
                Files.copy(source, zip);
                zip.closeEntry();
            }
        }
        return digest.digest();
    }

    private static String hex(byte[] bytes) {
        StringBuilder out = new StringBuilder(bytes.length * 2);
        for (byte value : bytes) {
            out.append(Character.forDigit((value >> 4) & 0xF, 16)).append(Character.forDigit(value & 0xF, 16));
        }
        return out.toString();
    }

    private static void deleteTree(Path directory) throws IOException {
        if (!Files.exists(directory)) {
            return;
        }
        Files.walkFileTree(directory, new SimpleFileVisitor<>() {
            @Override
            public FileVisitResult visitFile(Path file, BasicFileAttributes attributes) throws IOException {
                Files.delete(file);
                return FileVisitResult.CONTINUE;
            }

            @Override
            public FileVisitResult postVisitDirectory(Path visited, IOException failure) throws IOException {
                if (failure != null) {
                    throw failure;
                }
                Files.delete(visited);
                return FileVisitResult.CONTINUE;
            }
        });
    }

    private static int[] pngSize(Path file) throws IOException {
        if (!Files.isRegularFile(file)) {
            return null;
        }
        byte[] header = new byte[PNG_HEADER_LENGTH];
        try (InputStream stream = Files.newInputStream(file)) {
            int read = stream.readNBytes(header, 0, header.length);
            if (read < header.length) {
                return null;
            }
        } catch (UncheckedIOException failure) {
            throw new IOException(failure);
        }
        return pngSize(header);
    }

    /** Width and height straight out of the IHDR chunk; null when the bytes are not a PNG. */
    private static int[] pngSize(byte[] content) {
        if (content.length < PNG_HEADER_LENGTH) {
            return null;
        }
        for (int index = 0; index < PNG_MAGIC.length; index++) {
            if (content[index] != PNG_MAGIC[index]) {
                return null;
            }
        }
        if (content[12] != 'I' || content[13] != 'H' || content[14] != 'D' || content[15] != 'R') {
            return null;
        }
        return new int[]{readInt(content, 16), readInt(content, 20)};
    }

    private static int readInt(byte[] content, int offset) {
        return ((content[offset] & 0xFF) << 24) | ((content[offset + 1] & 0xFF) << 16)
            | ((content[offset + 2] & 0xFF) << 8) | (content[offset + 3] & 0xFF);
    }

    private static final class Budget {
        private final GlossConfig.PackLimits limits;
        private long bytes;
        private long pixels;
        private int files;

        private Budget(GlossConfig.PackLimits limits) {
            this.limits = limits;
        }

        private long remainingBytes() {
            return limits.maxBuildBytes() - bytes;
        }

        private void file(int length) throws IOException {
            if (files >= limits.maxBuildFiles() || length > remainingBytes()) {
                throw new IOException("Resource pack exceeds the generated file or byte budget");
            }
            files++;
            bytes += length;
        }

        private void pixels(long count) throws IOException {
            if (count > limits.maxBuildPixels() - pixels) {
                throw new IOException("Resource pack exceeds the aggregate decoded pixel budget");
            }
            pixels += count;
        }
    }

    private static final class BoundedOutput extends OutputStream {
        private final OutputStream output;
        private final long limit;
        private long written;

        private BoundedOutput(OutputStream output, long limit) {
            this.output = output;
            this.limit = limit;
        }

        @Override
        public void write(int value) throws IOException {
            require(1);
            output.write(value);
        }

        @Override
        public void write(byte[] bytes, int offset, int length) throws IOException {
            require(length);
            output.write(bytes, offset, length);
        }

        private void require(int length) throws IOException {
            if (length > limit - written) {
                throw new IOException("Resource pack ZIP exceeds the generated byte budget");
            }
            written += length;
        }
    }
}
