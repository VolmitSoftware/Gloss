package art.arcane.gloss.doc;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;

/**
 * The one atomic-write, fsync and storage-directory plumbing used by every Gloss document store.
 *
 * <p>Every folder Gloss owns is created here, on the first write that needs it. Nothing in this
 * class creates a directory speculatively, so a feature that never writes never leaves an empty
 * folder behind in the data directory.
 *
 * <p>The {@code noun} parameters carry the operator-facing collection name ({@code "menu"},
 * {@code "panel"}) into the {@link IOException} messages so a failure still reads like it came from
 * the store that raised it.
 */
public final class AtomicFiles {
    private static final String TEMPORARY_SUFFIX = ".tmp";

    private AtomicFiles() {
    }

    public static void createParentDirectories(Path target) throws IOException {
        Files.createDirectories(requireParent(target));
    }

    /**
     * Writes a temporary file beside the eventual target without forcing it to disk. The caller owns
     * the move and the cleanup; a failed write deletes the temporary before rethrowing.
     */
    public static Path writeTemporary(Path directory, String prefix, byte[] content) throws IOException {
        Path temporary = Files.createTempFile(directory, prefix, TEMPORARY_SUFFIX);
        try {
            Files.write(temporary, content);
            return temporary;
        } catch (IOException | RuntimeException failure) {
            Files.deleteIfExists(temporary);
            throw failure;
        }
    }

    /** Same as {@link #writeTemporary}, with the content forced to disk before the temporary is returned. */
    public static Path writeDurableTemporary(Path directory, String prefix, byte[] content) throws IOException {
        Path temporary = Files.createTempFile(directory, prefix, TEMPORARY_SUFFIX);
        try {
            if (Files.isSymbolicLink(temporary)) {
                throw new IOException("temporary file path is a symbolic link: " + temporary);
            }
            try (FileChannel channel = FileChannel.open(temporary, StandardOpenOption.WRITE,
                StandardOpenOption.TRUNCATE_EXISTING)) {
                ByteBuffer buffer = ByteBuffer.wrap(content);
                while (buffer.hasRemaining()) {
                    channel.write(buffer);
                }
                channel.force(true);
            }
            return temporary;
        } catch (IOException | RuntimeException failure) {
            Files.deleteIfExists(temporary);
            throw failure;
        }
    }

    /** Creates the parent chain, then replaces {@code target} with {@code content} through an atomic move. */
    public static void replace(Path target, byte[] content) throws IOException {
        Path parent = requireParent(target);
        Files.createDirectories(parent);
        Path temporary = writeTemporary(parent, "." + target.getFileName() + ".", content);
        try {
            Files.move(temporary, target, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
        } finally {
            Files.deleteIfExists(temporary);
        }
    }

    public static void forceDirectory(Path directory) throws IOException {
        if (directory == null) {
            return;
        }
        try (FileChannel channel = FileChannel.open(directory, StandardOpenOption.READ)) {
            channel.force(true);
        } catch (UnsupportedOperationException ignored) {
        }
    }

    public static void forceFile(Path file) throws IOException {
        try (FileChannel channel = FileChannel.open(file, StandardOpenOption.READ)) {
            channel.force(true);
        }
    }

    public static void requireRealDirectory(Path directory, String noun) throws IOException {
        if (Files.isSymbolicLink(directory) || !Files.isDirectory(directory, LinkOption.NOFOLLOW_LINKS)) {
            throw new IOException(noun + " storage path must be a real directory: " + directory);
        }
    }

    /** Creates the storage root if it is missing, and rejects a root that is not a real directory. */
    public static void ensureStorageDirectory(Path directory, String noun) throws IOException {
        Path parent = directory.getParent();
        if (parent != null) {
            Files.createDirectories(parent);
        }
        if (Files.exists(directory, LinkOption.NOFOLLOW_LINKS)) {
            requireRealDirectory(directory, noun);
            return;
        }
        Files.createDirectory(directory);
        forceDirectory(parent);
    }

    /** Read-side check: every path segment between the storage root and the target is a real directory. */
    public static void validateAncestors(Path root, Path target, String noun) throws IOException {
        Path parent = target.getParent();
        if (parent == null || !parent.startsWith(root)) {
            throw new IOException(noun + " path escapes the " + noun + " directory: " + target);
        }
        requireRealDirectory(root, noun);
        Path current = root;
        for (Path segment : root.relativize(parent)) {
            current = current.resolve(segment);
            requireDirectorySegment(current, noun);
        }
    }

    /** Write-side check: creates the missing directory segments below the storage root. */
    public static void prepareParent(Path root, Path target, String noun) throws IOException {
        ensureStorageDirectory(root, noun);
        Path parent = target.getParent();
        if (parent == null) {
            throw new IOException(noun + " file has no parent directory: " + target);
        }
        Path current = root;
        for (Path segment : root.relativize(parent)) {
            current = current.resolve(segment);
            if (Files.exists(current, LinkOption.NOFOLLOW_LINKS)) {
                requireDirectorySegment(current, noun);
            } else {
                Files.createDirectory(current);
                forceDirectory(current.getParent());
            }
        }
        if (Files.isSymbolicLink(target)) {
            throw new IOException(noun + " file must not be a symbolic link: " + target);
        }
    }

    private static void requireDirectorySegment(Path segment, String noun) throws IOException {
        if (Files.isSymbolicLink(segment) || !Files.isDirectory(segment, LinkOption.NOFOLLOW_LINKS)) {
            throw new IOException(noun + " path contains a non-directory or symbolic link: " + segment);
        }
    }

    private static Path requireParent(Path target) throws IOException {
        Path parent = target.getParent();
        if (parent == null) {
            throw new IOException("file has no parent directory: " + target);
        }
        return parent;
    }
}
