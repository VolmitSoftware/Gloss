package art.arcane.gloss.doc;

import art.arcane.gloss.Gloss;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.Iterator;
import java.util.List;
import java.util.Locale;
import java.util.stream.Stream;

/**
 * Path rules for the document kinds that are stored as a directory tree rather than a flat folder,
 * where a document id is its slash-separated path below the storage root minus the extension.
 *
 * <p>Everything here judges a candidate against the storage root, never against its own parent, so
 * the boot scan and the watcher pass share one predicate and one id rule. Hidden segments, symbolic
 * links anywhere on the path, and anything that is not a real regular file are never documents: a
 * link inside the root would otherwise let an operator publish a file the root does not contain.
 * The checks tolerate a path that no longer exists, so a deleted file still resolves to the id that
 * has to be unregistered.
 */
public final class DocumentTree {
    private static final String EXTENSION = ".json";

    private DocumentTree() {
    }

    /** True when {@code file} is a document of the tree rooted at {@code root}. */
    public static boolean isDocument(File root, File file) {
        if (root == null || file == null) {
            return false;
        }
        if (!file.getName().toLowerCase(Locale.ROOT).endsWith(EXTENSION)) {
            return false;
        }
        Path rootPath = root.toPath().toAbsolutePath().normalize();
        Path filePath = file.toPath().toAbsolutePath().normalize();
        if (!filePath.startsWith(rootPath) || filePath.equals(rootPath)
            || Files.isSymbolicLink(rootPath)) {
            return false;
        }
        Path relative = rootPath.relativize(filePath);
        Path current = rootPath;
        int segmentIndex = 0;
        for (Path segment : relative) {
            if (segment.toString().startsWith(".")) {
                return false;
            }
            current = current.resolve(segment);
            segmentIndex++;
            if (!Files.exists(current, LinkOption.NOFOLLOW_LINKS)) {
                continue;
            }
            if (Files.isSymbolicLink(current)) {
                return false;
            }
            boolean target = segmentIndex == relative.getNameCount();
            if ((target && !Files.isRegularFile(current, LinkOption.NOFOLLOW_LINKS))
                || (!target && !Files.isDirectory(current, LinkOption.NOFOLLOW_LINKS))) {
                return false;
            }
        }
        return true;
    }

    /** The slash-separated id of a document below {@code root}. */
    public static String idOf(File root, File file) {
        Path rootPath = root.toPath().toAbsolutePath().normalize();
        Path filePath = file.toPath().toAbsolutePath().normalize();
        if (!filePath.startsWith(rootPath) || filePath.equals(rootPath)) {
            throw new IllegalArgumentException("document file is not resolvable inside its storage root");
        }
        String path = relativePath(rootPath.relativize(filePath));
        return path.toLowerCase(Locale.ROOT).endsWith(EXTENSION)
            ? path.substring(0, path.length() - EXTENSION.length())
            : path;
    }

    /** Every document of the tree rooted at {@code root}, ordered by id. */
    public static List<File> discover(File root) {
        return discover(root, root);
    }

    /**
     * Every document at or below {@code start} that belongs to the tree rooted at {@code root}. A
     * watcher reports a new subdirectory as one creation, so the caller hands that directory here
     * to reach the files it arrived with.
     */
    public static List<File> discover(File root, File start) {
        if (root == null || start == null || !start.exists()) {
            return List.of();
        }
        if (start.isFile()) {
            return isDocument(root, start) ? List.of(start) : List.of();
        }

        List<File> documents = new ArrayList<>();
        try (Stream<Path> candidates = Files.walk(start.toPath())) {
            Iterator<Path> iterator = candidates.iterator();
            while (iterator.hasNext()) {
                File candidate = iterator.next().toFile();
                if (isDocument(root, candidate)) {
                    documents.add(candidate);
                }
            }
        } catch (IOException failure) {
            Gloss.logExceptionStack(true, failure, "Unable to scan document files below %s.", start);
        }
        documents.sort(Comparator.comparing(file -> idOf(root, file)));
        return List.copyOf(documents);
    }

    /**
     * The id prefix a directory below {@code root} contributes, or {@code null} when the path is not
     * a directory inside the root. A deleted directory takes every document below it with it, and
     * the directory is already gone by the time the watcher reports it, so this is resolved from the
     * path alone.
     */
    public static String prefixOf(File root, File directory) {
        String prefix;
        try {
            Path relative = root.getCanonicalFile().toPath()
                .relativize(directory.getCanonicalFile().toPath());
            prefix = relativePath(relative);
        } catch (IOException | IllegalArgumentException failure) {
            return null;
        }
        if (prefix.isBlank() || prefix.startsWith("..")) {
            return null;
        }
        return prefix;
    }

    private static String relativePath(Path relative) {
        return relative.toString().replace(File.separatorChar, '/');
    }
}
