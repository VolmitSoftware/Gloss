package art.arcane.gloss.doc;

import art.arcane.gloss.Gloss;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Objects;
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
        return RESOURCE_ROOT + kind + "/" + name + EXTENSION;
    }

    public List<String> extractMissing() {
        return extract(ALL, false);
    }

    public List<String> resetToDefault(String nameOrStar) {
        return extract(normalize(nameOrStar), true);
    }

    public boolean replaceIfExact(String name, byte[] expected) {
        Objects.requireNonNull(expected, "expected");
        if (!names.contains(name)) {
            return false;
        }
        Path file = new File(folder, name + EXTENSION).toPath();
        if (!Files.isRegularFile(file)) {
            return false;
        }
        try {
            if (!matchesExact(file, expected)) {
                return false;
            }
            byte[] replacement = readResource(name);
            if (replacement == null) {
                Gloss.log(Level.WARNING, "%s/%s%s: missing from the jar, not upgraded.", kind, name, EXTENSION);
                return false;
            }
            return replaceAtomically(file, expected, replacement);
        } catch (IOException failure) {
            Gloss.log(Level.WARNING, "%s/%s%s: %s", kind, name, EXTENSION,
                failure.getMessage() == null ? failure.getClass().getSimpleName() : failure.getMessage());
            return false;
        }
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
                Gloss.log(Level.WARNING, "%s/%s%s: %s", kind, name, EXTENSION,
                    failure.getMessage() == null ? failure.getClass().getSimpleName() : failure.getMessage());
            }
        }
        return written;
    }

    private byte[] readResource(String name) throws IOException {
        try (InputStream stream = ShippedDefaults.class.getResourceAsStream(resourcePath(name))) {
            return stream == null ? null : stream.readAllBytes();
        }
    }

    /**
     * Reports whether the file still holds the untouched shipped form, ignoring line-ending style.
     *
     * <p>The expected forms are Java string literals, so they always carry a bare line feed, while
     * the file on disk carries whatever the checkout produced: a carriage return plus line feed on
     * a Windows clone. A byte-exact comparison reads every such file as operator-edited and skips
     * the upgrade. Only a carriage return that immediately precedes a line feed is folded away, so
     * a genuine operator edit still counts as one.
     */
    private boolean matchesExact(Path file, byte[] expected) throws IOException {
        return Arrays.equals(foldLineEndings(Files.readAllBytes(file)), foldLineEndings(expected));
    }

    private static byte[] foldLineEndings(byte[] content) {
        byte[] folded = new byte[content.length];
        int length = 0;
        for (int index = 0; index < content.length; index++) {
            if (content[index] == '\r' && index + 1 < content.length && content[index + 1] == '\n') {
                continue;
            }
            folded[length++] = content[index];
        }
        return Arrays.copyOf(folded, length);
    }

    private boolean replaceAtomically(Path file, byte[] expected, byte[] content) throws IOException {
        Path temporary = AtomicFiles.writeTemporary(file.getParent(), "." + file.getFileName(), content);
        try {
            if (!matchesExact(file, expected)) {
                return false;
            }
            try {
                Files.move(temporary, file, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
            } catch (AtomicMoveNotSupportedException unsupported) {
                Files.move(temporary, file, StandardCopyOption.REPLACE_EXISTING);
            }
            return true;
        } finally {
            Files.deleteIfExists(temporary);
        }
    }
}
