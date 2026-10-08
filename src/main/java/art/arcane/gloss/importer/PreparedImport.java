package art.arcane.gloss.importer;

import art.arcane.gloss.Gloss;
import art.arcane.gloss.GlossConfig;
import art.arcane.gloss.editor.sync.EditorSyncDocumentKind;
import art.arcane.gloss.doc.DocumentPresetCatalog;
import art.arcane.gloss.history.HistoryKinds;
import art.arcane.gloss.persistence.GlossPersistenceCoordinator;
import art.arcane.gloss.persistence.GlossProjectTransaction;
import art.arcane.gloss.persistence.TransactionPreparation;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.FileVisitResult;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.attribute.BasicFileAttributes;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;

final class PreparedImport {
    private final Path root;
    private final GlossConfig.Imports limits;
    private long retainedBytes;
    private final Map<Path, byte[]> observed = new LinkedHashMap<>();
    private final Map<Path, GlossProjectTransaction.Mutation> mutations = new LinkedHashMap<>();
    private final List<ApplyCheck> applyChecks = new ArrayList<>();
    private Set<Path> documentPaths;

    PreparedImport(Path root) {
        this(root, GlossConfig.current().imports());
    }

    PreparedImport(Path root, GlossConfig.Imports limits) {
        this.root = root.toAbsolutePath().normalize();
        this.limits = limits;
    }

    byte[] read(Path path) throws IOException {
        Path absolute = path.toAbsolutePath().normalize();
        if (!observed.containsKey(absolute)) {
            if (observed.size() >= limits.maxFiles()) {
                throw new IOException("Import preview exceeds the configured file-count limit");
            }
            byte[] loaded = readCurrent(absolute);
            reserve(loaded == null ? 0 : loaded.length);
            observed.put(absolute, loaded);
        }
        byte[] content = observed.get(absolute);
        return content == null ? null : content.clone();
    }

    void stage(Path target, byte[] content) throws IOException {
        Path absolute = target.toAbsolutePath().normalize();
        if (!absolute.startsWith(root) || absolute.equals(root)) {
            throw new IOException("Import target is outside the data directory: " + target);
        }
        HistoryKinds.DocumentPath document = HistoryKinds.resolve(root, root.relativize(absolute).toString());
        if (document != null) {
            requireDocumentDepth(absolute, document.kind());
        }
        requireFileSize(content.length, absolute);
        byte[] before = read(absolute);
        GlossProjectTransaction.Mutation previous = mutations.get(absolute);
        long previousBytes = previous == null ? 0 : previous.contentLength();
        if (!Arrays.equals(before, content)) {
            reserve(content.length - previousBytes);
            mutations.put(absolute, GlossProjectTransaction.Mutation.write(content));
        } else if (previous != null) {
            mutations.remove(absolute);
            retainedBytes -= previousBytes;
        }
    }

    void document(Path target, byte[] content) throws IOException {
        String relative = root.relativize(target.toAbsolutePath().normalize()).toString();
        HistoryKinds.DocumentPath document = HistoryKinds.resolve(root, relative);
        if (document == null) {
            throw new IOException("Unsupported import document: " + relative);
        }
        stage(target, content);
    }

    long retainedBytes() {
        return retainedBytes;
    }

    void retainBytes(long bytes) throws IOException {
        reserve(bytes);
    }

    PreparedImport excluding(Set<Path> excluded) {
        PreparedImport selected = new PreparedImport(root, limits);
        selected.observed.putAll(observed);
        selected.mutations.putAll(mutations);
        selected.applyChecks.addAll(applyChecks);
        selected.documentPaths = documentPaths;
        selected.retainedBytes = retainedBytes;
        for (Path path : excluded) {
            GlossProjectTransaction.Mutation removed = selected.mutations.remove(path.toAbsolutePath().normalize());
            if (removed != null) {
                selected.retainedBytes -= removed.contentLength();
            }
        }
        return selected;
    }

    List<String> targets() {
        return mutations.keySet().stream().map(root::relativize).map(Path::toString).toList();
    }

    void checkBeforeApply(ApplyCheck check) {
        applyChecks.add(check);
    }

    void validateDocuments() throws IOException {
        if (documentPaths == null) {
            documentPaths = discoverDocuments();
        }
        Path presetPath = root.resolve(DocumentPresetCatalog.FILE_NAME);
        GlossProjectTransaction.Mutation presetMutation = mutations.get(presetPath);
        byte[] presetBytes = presetMutation == null ? read(presetPath) : presetMutation.content();
        DocumentPresetCatalog presets = presetBytes == null ? DocumentPresetCatalog.empty()
            : DocumentPresetCatalog.parse(DocumentPresetCatalog.FILE_NAME, new String(presetBytes, StandardCharsets.UTF_8));
        Set<Path> resulting = new TreeSet<>(documentPaths);
        resulting.addAll(mutations.keySet());
        for (Path path : resulting) {
            HistoryKinds.DocumentPath document = HistoryKinds.resolve(root, root.relativize(path).toString());
            if (document == null) {
                continue;
            }
            requireDocumentDepth(path, document.kind());
            GlossProjectTransaction.Mutation staged = mutations.get(path);
            byte[] content = staged == null ? read(path) : staged.content();
            if (content != null) {
                document.kind().parse(document.id(), new String(content, StandardCharsets.UTF_8), presets);
            }
        }
    }

    String apply(String label, GlossProjectTransaction transaction,
                 GlossPersistenceCoordinator coordinator) throws IOException {
        return apply(label, transaction, coordinator, () -> { });
    }

    String apply(String label, GlossProjectTransaction transaction,
                 GlossPersistenceCoordinator coordinator, ApplyCheck beforeWrite) throws IOException {
        if (mutations.isEmpty()) {
            return null;
        }
        if (documentPaths == null) {
            validateDocuments();
        }
        return coordinator.writeExternally(() -> {
            TransactionPreparation preparation = new TransactionPreparation(new TransactionPreparation.Limits(
                limits.maxPreparationBytes(), limits.maxPreparationMillis()));
            for (ApplyCheck check : applyChecks) {
                preparation.check();
                check.check();
            }
            if (documentPaths != null && !documentPaths.equals(discoverDocuments())) {
                throw new IOException("Project documents changed after preview");
            }
            for (Map.Entry<Path, byte[]> source : observed.entrySet()) {
                preparation.check();
                if (!Arrays.equals(source.getValue(), readCurrent(source.getKey()))) {
                    throw new IOException("Import source or destination changed after preview: " + source.getKey());
                }
            }
            Map<Path, byte[]> expected = new LinkedHashMap<>();
            for (Path target : mutations.keySet()) {
                expected.put(target, observed.get(target));
            }
            beforeWrite.check();
            GlossProjectTransaction.Pending pending;
            try {
                pending = transaction.apply(label, mutations, expected, preparation);
            } catch (IOException | RuntimeException failure) {
                if (failure.getSuppressed().length > 0) {
                    coordinator.requireRestartRecovery();
                }
                throw failure;
            }
            try {
                transaction.commit(pending);
            } catch (GlossProjectTransaction.CommittedCleanupException failure) {
                Gloss.logExceptionStack(false, failure, "Import committed; backup cleanup requires recovery.");
                coordinator.requireRestartRecovery();
                return pending.transactionDirectory().resolve("backup").toString();
            } catch (IOException failure) {
                coordinator.requireRestartRecovery();
                throw failure;
            }
            return root.resolve("editor-sync-backups").resolve(pending.id()).resolve("backup").toString();
        });
    }

    private Set<Path> discoverDocuments() throws IOException {
        DocumentDiscovery discovery = new DocumentDiscovery();
        for (EditorSyncDocumentKind kind : EditorSyncDocumentKind.ORDERED) {
            discovery.scan(kind);
        }
        return Set.copyOf(discovery.paths);
    }

    private void requireDocumentDepth(Path path, EditorSyncDocumentKind kind) throws IOException {
        if (kind.layout() == EditorSyncDocumentKind.Layout.TREE
            && root.resolve(kind.storageName()).relativize(path).getNameCount() - 1 > limits.maxDirectoryDepth()) {
            throw new IOException("Project document directory exceeds the configured import depth limit: " + path);
        }
    }

    private void reserve(long additional) throws IOException {
        if (additional > limits.maxPreviewBytes() - retainedBytes) {
            throw new IOException("Import preview exceeds the configured retained-byte limit");
        }
        retainedBytes += additional;
    }

    private void requireFileSize(long bytes, Path path) throws IOException {
        if (bytes > limits.maxFileBytes()) {
            throw new IOException("Import file exceeds the configured file-byte limit: " + path);
        }
    }

    private byte[] readCurrent(Path path) throws IOException {
        if (!Files.exists(path, LinkOption.NOFOLLOW_LINKS)) {
            return null;
        }
        if (!Files.isRegularFile(path, LinkOption.NOFOLLOW_LINKS)) {
            throw new IOException("Import path must be a regular file: " + path);
        }
        requireFileSize(Files.size(path), path);
        try (InputStream input = Files.newInputStream(path)) {
            byte[] bytes = input.readNBytes(limits.maxFileBytes() + 1);
            requireFileSize(bytes.length, path);
            return bytes;
        }
    }

    private final class DocumentDiscovery extends SimpleFileVisitor<Path> {
        private final Set<Path> paths = new TreeSet<>();
        private int visitedEntries;
        private Path collection;
        private boolean recursive;

        private void scan(EditorSyncDocumentKind kind) throws IOException {
            collection = root.resolve(kind.storageName());
            if (!Files.exists(collection, LinkOption.NOFOLLOW_LINKS)) {
                return;
            }
            if (kind.layout() == EditorSyncDocumentKind.Layout.SINGLE) {
                visitEntry();
                if (!Files.isRegularFile(collection, LinkOption.NOFOLLOW_LINKS)) {
                    throw new IOException("Import path must be a regular file: " + collection);
                }
                addDocument(collection);
                return;
            }
            if (!Files.isDirectory(collection, LinkOption.NOFOLLOW_LINKS)) {
                throw new IOException("Document collection must be a directory: " + collection);
            }
            recursive = kind.layout() == EditorSyncDocumentKind.Layout.TREE;
            Files.walkFileTree(collection, Set.of(), recursive ? limits.maxDirectoryDepth() + 1 : 1, this);
        }

        @Override
        public FileVisitResult preVisitDirectory(Path directory, BasicFileAttributes attributes) throws IOException {
            visitEntry();
            if (!directory.equals(collection)) {
                includeDocument(directory, attributes);
            }
            return FileVisitResult.CONTINUE;
        }

        @Override
        public FileVisitResult visitFile(Path file, BasicFileAttributes attributes) throws IOException {
            visitEntry();
            if (recursive && attributes.isDirectory()) {
                throw new IOException("Project document directory exceeds the configured import depth limit: " + file);
            }
            includeDocument(file, attributes);
            return FileVisitResult.CONTINUE;
        }

        private void includeDocument(Path file, BasicFileAttributes attributes) throws IOException {
            String name = file.getFileName().toString();
            if (name.endsWith(".json") && !name.startsWith(".") && !name.startsWith("~")
                && !name.startsWith("#")) {
                if (!attributes.isRegularFile()) {
                    throw new IOException("Import path must be a regular file: " + file);
                }
                addDocument(file);
            }
        }

        private void visitEntry() throws IOException {
            if (++visitedEntries > limits.maxVisitedEntries()) {
                throw new IOException("Project document scan exceeds the configured import visited-entry limit");
            }
        }

        private void addDocument(Path file) throws IOException {
            paths.add(file);
            if (paths.size() > limits.maxFiles()) {
                throw new IOException("Project documents exceed the configured import file-count limit");
            }
        }
    }

    @FunctionalInterface
    interface ApplyCheck {
        void check() throws IOException;
    }
}
