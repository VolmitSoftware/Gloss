package art.arcane.gloss.importer;

import art.arcane.gloss.doc.DocumentHashes;
import art.arcane.gloss.editor.sync.EditorSyncDocumentKind;
import art.arcane.gloss.history.HistoryKinds;
import art.arcane.gloss.persistence.GlossPersistenceCoordinator;
import art.arcane.gloss.persistence.GlossProjectTransaction;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/**
 * The whole-document legacy sources: FeatherBoard, AnimatedScoreboard and TAB's header and footer.
 *
 * <p>Previewing is the default and writes nothing. Applying refuses to overwrite a document that
 * already exists unless the caller passes the overwrite flag, and the copy it replaces is handed to
 * the history before the transaction commits.
 */
public final class DocumentImportService {
    private final Path serverDirectory;
    private final Path dataDirectory;
    private final GlossProjectTransaction transaction;
    private final GlossPersistenceCoordinator coordinator;
    private final HistoryRecorder history;

    public DocumentImportService(Path serverDirectory, Path dataDirectory,
                                 GlossProjectTransaction transaction,
                                 GlossPersistenceCoordinator coordinator, HistoryRecorder history) {
        this.serverDirectory = Objects.requireNonNull(serverDirectory, "serverDirectory")
                .toAbsolutePath().normalize();
        this.dataDirectory = Objects.requireNonNull(dataDirectory, "dataDirectory")
                .toAbsolutePath().normalize();
        this.transaction = Objects.requireNonNull(transaction, "transaction");
        this.coordinator = Objects.requireNonNull(coordinator, "coordinator");
        this.history = Objects.requireNonNull(history, "history");
    }

    public static boolean handles(LegacyImportSource source) {
        return source == LegacyImportSource.FEATHERBOARD
                || source == LegacyImportSource.ANIMATED_SCOREBOARD
                || source == LegacyImportSource.TAB_HEADER_FOOTER;
    }

    public DocumentImportPlan preview(LegacyImportSource source) throws IOException {
        List<DocumentImportEntry> entries = new ArrayList<>();
        List<LegacyImportIssue> issues = new ArrayList<>();
        Path sourcePath;
        boolean present;
        switch (source) {
            case FEATHERBOARD -> {
                FeatherBoardScanner scanner = new FeatherBoardScanner();
                sourcePath = scanner.root(serverDirectory);
                present = Files.isDirectory(sourcePath, LinkOption.NOFOLLOW_LINKS);
                FeatherBoardConverter converter = new FeatherBoardConverter();
                for (LegacyBoardDraft draft : scanner.scan(sourcePath)) {
                    entries.addAll(converter.convert(draft));
                }
            }
            case ANIMATED_SCOREBOARD -> {
                AnimatedScoreboardScanner scanner = new AnimatedScoreboardScanner();
                sourcePath = scanner.root(serverDirectory);
                present = Files.isDirectory(sourcePath, LinkOption.NOFOLLOW_LINKS);
                AnimatedScoreboardConverter converter = new AnimatedScoreboardConverter();
                for (LegacyBoardDraft draft : scanner.scan(sourcePath)) {
                    entries.addAll(converter.convert(draft));
                }
            }
            case TAB_HEADER_FOOTER -> {
                TabHeaderFooterScanner scanner = new TabHeaderFooterScanner();
                sourcePath = scanner.file(serverDirectory);
                present = Files.isRegularFile(sourcePath, LinkOption.NOFOLLOW_LINKS);
                TabHeaderFooterScanner.Draft draft = scanner.scan(sourcePath);
                if (draft == null) {
                    if (present) {
                        issues.add(new LegacyImportIssue(LegacyImportIssue.Severity.WARNING, "-",
                                "config.yml has no header-footer block"));
                    }
                } else {
                    entries.addAll(new TabHeaderFooterConverter().convert(draft));
                }
            }
            default -> throw new IllegalArgumentException(
                    "source is not a document import: " + source.id());
        }
        return new DocumentImportPlan(source, sourcePath.toString(), present,
                withDispositions(entries), List.copyOf(issues));
    }

    /**
     * Writes the ready entries under the persistence write permit, so the hot-reload watchers and
     * a concurrent editor publication cannot see half of them. Existing documents are left alone
     * unless {@code overwrite}.
     */
    public List<DocumentImportEntry> apply(DocumentImportPlan plan, boolean overwrite)
            throws IOException {
        Map<Path, GlossProjectTransaction.Mutation> mutations = new LinkedHashMap<>();
        Map<Path, byte[]> expected = new LinkedHashMap<>();
        Map<Path, DocumentImportEntry> written = new LinkedHashMap<>();
        List<DocumentImportEntry> applied = new ArrayList<>();
        for (DocumentImportEntry entry : plan.entries()) {
            if (entry.disposition() == LegacyImportDisposition.CONFLICT && !overwrite) {
                continue;
            }
            Path target = target(entry);
            byte[] content = entry.json().getBytes(StandardCharsets.UTF_8);
            byte[] current = currentBytes(target);
            if (current != null && DocumentHashes.sha256(current)
                    .equals(DocumentHashes.sha256(content))) {
                continue;
            }
            mutations.put(target, GlossProjectTransaction.Mutation.write(content));
            if (current != null) {
                expected.put(target, current);
            }
            written.put(target, entry);
            applied.add(entry);
        }
        if (mutations.isEmpty()) {
            return List.of();
        }
        return coordinator.writeExternally(() -> {
            for (Map.Entry<Path, byte[]> replaced : expected.entrySet()) {
                DocumentImportEntry entry = written.get(replaced.getKey());
                history.record(entry.kind(), entry.id(), replaced.getValue(),
                        "import:" + plan.source().id());
            }
            GlossProjectTransaction.Pending pending = transaction.apply(
                    "import-" + plan.source().id(), mutations, expected);
            transaction.commit(pending);
            return List.copyOf(applied);
        });
    }

    /** The kinds an applied plan touched, so the caller can reload exactly those runtimes. */
    public static Set<EditorSyncDocumentKind> kindsOf(List<DocumentImportEntry> entries) {
        Set<EditorSyncDocumentKind> kinds = new LinkedHashSet<>();
        for (DocumentImportEntry entry : entries) {
            EditorSyncDocumentKind kind = HistoryKinds.byCollection(entry.kind());
            if (kind != null) {
                kinds.add(kind);
            }
        }
        return Set.copyOf(kinds);
    }

    private List<DocumentImportEntry> withDispositions(List<DocumentImportEntry> entries) {
        List<DocumentImportEntry> resolved = new ArrayList<>(entries.size());
        for (DocumentImportEntry entry : entries) {
            Path target = target(entry);
            resolved.add(Files.exists(target, LinkOption.NOFOLLOW_LINKS)
                    ? entry.withDisposition(LegacyImportDisposition.CONFLICT,
                    "a document already exists at " + entry.path())
                    : entry);
        }
        return List.copyOf(resolved);
    }

    private Path target(DocumentImportEntry entry) {
        EditorSyncDocumentKind kind = HistoryKinds.requireCollection(entry.kind());
        return kind.path(dataDirectory, entry.id());
    }

    private static byte[] currentBytes(Path target) throws IOException {
        return Files.isRegularFile(target, LinkOption.NOFOLLOW_LINKS)
                ? Files.readAllBytes(target)
                : null;
    }

    /** Where a replaced document's previous bytes go. */
    @FunctionalInterface
    public interface HistoryRecorder {
        void record(String kind, String id, byte[] content, String source);
    }
}
