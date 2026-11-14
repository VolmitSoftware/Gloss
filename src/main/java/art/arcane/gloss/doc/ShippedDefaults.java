package art.arcane.gloss.doc;

import art.arcane.gloss.Gloss;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.logging.Level;

public final class ShippedDefaults {
    public static final String ALL = "*";

    private static final String EXTENSION = ".json";
    private static final String RESOURCE_ROOT = "/defaults/";

    private final String kind;
    private final File folder;
    private final List<String> names;

    public ShippedDefaults(String kind, File folder, List<String> names) {
        this.kind = Objects.requireNonNull(kind, "kind");
        this.folder = Objects.requireNonNull(folder, "folder");
        this.names = List.copyOf(names);
    }

    public List<String> names() {
        return names;
    }

    public String resourcePath(String name) {
        return resourcePath(kind, name);
    }

    /** Where the jar holds the shipped copy of {@code kind/name}. */
    public static String resourcePath(String kind, String name) {
        return RESOURCE_ROOT + kind + "/" + name + EXTENSION;
    }

    /**
     * The bytes Gloss extracts to {@code relativePath} under the data folder ({@code boards/default.json}, or
     * {@code tablist.json} for a single-file kind), or empty when it ships nothing there.
     */
    public static Optional<byte[]> shippedCopy(String relativePath) {
        if (relativePath == null || !relativePath.endsWith(EXTENSION)) {
            return Optional.empty();
        }
        String stem = relativePath.substring(0, relativePath.length() - EXTENSION.length());
        int slash = stem.indexOf('/');
        String kind = slash < 0 ? stem : stem.substring(0, slash);
        String name = slash < 0 ? stem : stem.substring(slash + 1);
        for (ShippedDocumentCatalog.Entry<?> entry : ShippedDocumentCatalog.all()) {
            if (!entry.kind().equals(kind) || !entry.names().contains(name)) {
                continue;
            }
            try (InputStream stream = ShippedDefaults.class.getResourceAsStream(resourcePath(kind, name))) {
                return stream == null ? Optional.empty() : Optional.of(stream.readAllBytes());
            } catch (IOException failure) {
                throw new UncheckedIOException("cannot read the shipped " + relativePath, failure);
            }
        }
        return Optional.empty();
    }

    public List<String> extractMissing() {
        return extract(ALL, false);
    }

    public List<String> resetToDefault(String nameOrStar) {
        return extract(normalize(nameOrStar), true);
    }

    public static String normalize(String nameOrStar) {
        if (nameOrStar == null) {
            return "";
        }
        return nameOrStar.endsWith(EXTENSION)
            ? nameOrStar.substring(0, nameOrStar.length() - EXTENSION.length())
            : nameOrStar;
    }

    /**
     * The folder is created by the first document that is actually written into it, so a feature
     * whose defaults are all present — or that ships none at all — never materialises an empty one.
     */
    private List<String> extract(String wanted, boolean overwrite) {
        List<String> written = new ArrayList<>();
        for (String name : names) {
            if (!ALL.equals(wanted) && !name.equals(wanted)) {
                continue;
            }
            File file = new File(folder, name + EXTENSION);
            if (!overwrite && file.exists()) {
                continue;
            }
            try (InputStream stream = ShippedDefaults.class.getResourceAsStream(resourcePath(name))) {
                if (stream == null) {
                    Gloss.log(Level.WARNING, "%s/%s%s: missing from the jar, not extracted.", kind, name, EXTENSION);
                    continue;
                }
                byte[] content = stream.readAllBytes();
                AtomicFiles.createParentDirectories(file.toPath());
                Files.write(file.toPath(), content);
                written.add(name);
            } catch (IOException failure) {
                Gloss.logExceptionStack(false, failure, "%s/%s%s could not be extracted.",
                    kind, name, EXTENSION);
            }
        }
        return written;
    }

}
