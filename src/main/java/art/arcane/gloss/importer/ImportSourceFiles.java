package art.arcane.gloss.importer;

import art.arcane.gloss.GlossConfig;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.function.Predicate;

final class ImportSourceFiles {
    private final GlossConfig.Imports limits;
    private int visited;
    private int selected;
    private long bytes;

    ImportSourceFiles(GlossConfig.Imports limits) {
        this.limits = limits;
    }

    List<Path> collect(Path directory, boolean recursive, Predicate<Path> include) throws IOException {
        if (!Files.exists(directory, LinkOption.NOFOLLOW_LINKS)) {
            return List.of();
        }
        if (!Files.isDirectory(directory, LinkOption.NOFOLLOW_LINKS)) {
            throw new IOException("Import collection must be a directory: " + directory);
        }
        List<Path> result = new ArrayList<>();
        Files.walkFileTree(directory, Set.of(), recursive ? limits.maxDirectoryDepth() + 1 : 1,
            new SimpleFileVisitor<>() {
                @Override
                public FileVisitResult preVisitDirectory(Path path, BasicFileAttributes attributes) throws IOException {
                    visit();
                    return FileVisitResult.CONTINUE;
                }

                @Override
                public FileVisitResult visitFile(Path path, BasicFileAttributes attributes) throws IOException {
                    visit();
                    if (recursive && attributes.isDirectory()) {
                        throw new IOException("Import source exceeds the configured directory-depth limit: " + path);
                    }
                    if (include.test(path)) {
                        if (!attributes.isRegularFile()) {
                            throw new IOException("Import source must be a regular file: " + path);
                        }
                        select();
                        result.add(path);
                    }
                    return FileVisitResult.CONTINUE;
                }
            });
        result.sort(Path::compareTo);
        return List.copyOf(result);
    }

    List<Path> single(Path path) throws IOException {
        if (!Files.exists(path, LinkOption.NOFOLLOW_LINKS)) {
            return List.of();
        }
        visit();
        if (!Files.isRegularFile(path, LinkOption.NOFOLLOW_LINKS)) {
            throw new IOException("Import source must be a regular file: " + path);
        }
        select();
        return List.of(path);
    }

    byte[] read(Path path) throws IOException {
        if (!Files.isRegularFile(path, LinkOption.NOFOLLOW_LINKS)) {
            throw new IOException("Import source must be a regular file: " + path);
        }
        long remaining = Math.min(Integer.MAX_VALUE - 1L,
            Math.min(limits.maxFileBytes(), limits.maxPreviewBytes() - bytes));
        if (remaining < 0 || Files.size(path) > remaining) {
            throw new IOException("Import source exceeds the configured byte limits: " + path);
        }
        try (InputStream input = Files.newInputStream(path, LinkOption.NOFOLLOW_LINKS)) {
            byte[] content = input.readNBytes((int) remaining + 1);
            if (content.length > remaining) {
                throw new IOException("Import source exceeds the configured byte limits: " + path);
            }
            bytes += content.length;
            return content;
        }
    }

    private void visit() throws IOException {
        if (++visited > limits.maxVisitedEntries()) {
            throw new IOException("Import source scan exceeds the configured visited-entry limit");
        }
    }

    private void select() throws IOException {
        if (++selected > limits.maxFiles()) {
            throw new IOException("Import source exceeds the configured file-count limit");
        }
    }
}
