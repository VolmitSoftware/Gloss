package art.arcane.gloss.forge;

import art.arcane.gloss.doc.AtomicFiles;
import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.attribute.BasicFileAttributes;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.TreeMap;
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

    public PackBuilder(Path imagesRoot) {
        this.imagesRoot = Objects.requireNonNull(imagesRoot, "imagesRoot");
    }

    /** The image size reader the registry uses to derive advance widths from the same files. */
    public GlyphRegistry.ImageProbe probe() {
        return imagePath -> {
            try {
                return pngSize(imagesRoot.resolve(imagePath));
            } catch (IOException | RuntimeException unreadable) {
                return null;
            }
        };
    }

    /**
     * @param outDir the {@code forge/out} folder; the mergeable tree lands in {@code pack/} beneath it
     * @throws IllegalArgumentException when an image is missing or the client would refuse it
     */
    public PackArtifact build(GlyphRegistry registry, Path outDir, int packFormat) throws IOException {
        Objects.requireNonNull(registry, "registry");
        Objects.requireNonNull(outDir, "outDir");
        Path directory = outDir.resolve(PACK_DIRECTORY);
        Map<String, byte[]> files = new TreeMap<>();
        files.put("pack.mcmeta", ascii(mcmeta(packFormat)));
        files.put("assets/" + registry.namespace() + "/font/" + registry.font() + ".json", ascii(font(registry)));
        for (GlyphRegistry.ResolvedGlyph glyph : registry.all()) {
            files.put("assets/" + registry.namespace() + "/textures/font/" + glyph.id() + ".png",
                texture(glyph));
        }

        deleteTree(directory);
        for (Map.Entry<String, byte[]> file : files.entrySet()) {
            Path target = directory.resolve(file.getKey());
            AtomicFiles.createParentDirectories(target);
            Files.write(target, file.getValue());
        }

        byte[] zipped = zip(files);
        byte[] sha1 = sha1(zipped);
        String sha1Hex = hex(sha1);
        Path zip = outDir.resolve(ZIP_NAME);
        AtomicFiles.createParentDirectories(zip);
        AtomicFiles.replace(zip, zipped);
        AtomicFiles.replace(outDir.resolve(SHA1_NAME), (sha1Hex + "\n").getBytes(StandardCharsets.UTF_8));
        return new PackArtifact(directory, zip, sha1, sha1Hex, System.currentTimeMillis());
    }

    private byte[] texture(GlyphRegistry.ResolvedGlyph glyph) throws IOException {
        Path source = imagesRoot.resolve(glyph.image());
        if (!Files.isRegularFile(source)) {
            throw new IllegalArgumentException("glyph " + glyph.id() + ": images/" + glyph.image() + " is missing");
        }
        byte[] content = Files.readAllBytes(source);
        int[] size = pngSize(content);
        if (size == null) {
            throw new IllegalArgumentException("glyph " + glyph.id() + ": images/" + glyph.image() + " is not a PNG");
        }
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

    private static String mcmeta(int packFormat) {
        JsonObject pack = new JsonObject();
        pack.addProperty("pack_format", packFormat);
        pack.addProperty("description", DESCRIPTION);
        JsonObject root = new JsonObject();
        root.add("pack", pack);
        return GSON.toJson(root) + "\n";
    }

    private static String font(GlyphRegistry registry) {
        JsonArray providers = new JsonArray();
        for (GlyphRegistry.ResolvedGlyph glyph : registry.all()) {
            JsonObject provider = new JsonObject();
            provider.addProperty("type", "bitmap");
            provider.addProperty("file", registry.namespace() + ":font/" + glyph.id() + ".png");
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

    private static byte[] zip(Map<String, byte[]> files) throws IOException {
        ByteArrayOutputStream buffer = new ByteArrayOutputStream();
        try (ZipOutputStream zip = new ZipOutputStream(buffer, StandardCharsets.UTF_8)) {
            zip.setMethod(ZipOutputStream.STORED);
            List<String> names = new ArrayList<>(files.keySet());
            names.sort(Comparator.naturalOrder());
            CRC32 crc = new CRC32();
            for (String name : names) {
                byte[] content = files.get(name);
                crc.reset();
                crc.update(content);
                ZipEntry entry = new ZipEntry(name);
                entry.setMethod(ZipEntry.STORED);
                entry.setSize(content.length);
                entry.setCompressedSize(content.length);
                entry.setCrc(crc.getValue());
                entry.setTime(0L);
                zip.putNextEntry(entry);
                zip.write(content);
                zip.closeEntry();
            }
        }
        return buffer.toByteArray();
    }

    private static byte[] sha1(byte[] content) {
        try {
            return MessageDigest.getInstance("SHA-1").digest(content);
        } catch (NoSuchAlgorithmException impossible) {
            throw new IllegalStateException("SHA-1 is unavailable", impossible);
        }
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
}
