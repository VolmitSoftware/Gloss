package art.arcane.gloss.importer;

import art.arcane.gloss.GlossConfig;
import art.arcane.gloss.doc.DocumentEnvelope;
import art.arcane.gloss.doc.DocumentPresetCatalog;
import art.arcane.gloss.editor.sync.EditorSyncDocumentKind;
import art.arcane.gloss.history.HistoryKinds;
import art.arcane.gloss.persistence.GlossPersistenceCoordinator;
import art.arcane.gloss.persistence.GlossProjectTransaction;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.function.BiFunction;
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
        return preview(source, GlossConfig.current().imports());
    }

    DocumentImportPlan preview(LegacyImportSource source, GlossConfig.Imports limits) throws IOException {
        PreparedImport preparation = new PreparedImport(dataDirectory, limits);
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
                for (LegacyBoardDraft draft : scanBoards(sourcePath, preparation, scanner::read, limits)) {
                    entries.addAll(converter.convert(draft));
                }
            }
            case ANIMATED_SCOREBOARD -> {
                AnimatedScoreboardScanner scanner = new AnimatedScoreboardScanner();
                sourcePath = scanner.root(serverDirectory);
                present = Files.isDirectory(sourcePath, LinkOption.NOFOLLOW_LINKS);
                AnimatedScoreboardConverter converter = new AnimatedScoreboardConverter();
                for (LegacyBoardDraft draft : scanBoards(sourcePath, preparation, scanner::read, limits)) {
                    entries.addAll(converter.convert(draft));
                }
            }
            case TAB_HEADER_FOOTER -> {
                TabHeaderFooterScanner scanner = new TabHeaderFooterScanner();
                sourcePath = scanner.file(serverDirectory);
                present = Files.isRegularFile(sourcePath, LinkOption.NOFOLLOW_LINKS);
                byte[] bytes = preparation.read(sourcePath);
                TabHeaderFooterScanner.Draft draft = bytes == null ? null
                    : scanner.read(new String(bytes, StandardCharsets.UTF_8));
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
        List<DocumentImportEntry> resolved = withDispositions(entries, preparation);
        Set<Path> targets = new LinkedHashSet<>();
        for (DocumentImportEntry entry : resolved) {
            Path target = target(entry);
            if (!targets.add(target)) {
                throw new IOException("Multiple import entries use the same destination: " + entry.path());
            }
            preparation.document(target, entry.json().getBytes(StandardCharsets.UTF_8));
            preparation.retainBytes(entry.json().length() * 2L);
        }
        preparation.validateDocuments();
        return new DocumentImportPlan(source, sourcePath.toString(), present,
                resolved, List.copyOf(issues), this, preparation);
    }

    /**
     * Writes the ready entries under the persistence write permit, so the hot-reload watchers and
     * a concurrent editor publication cannot see half of them. Existing documents are left alone
     * unless {@code overwrite}.
     */
    public List<DocumentImportEntry> apply(DocumentImportPlan plan, boolean overwrite)
            throws IOException {
        PreparedImport preparation = plan.preparation(this);
        Set<Path> excluded = new LinkedHashSet<>();
        Map<Path, byte[]> replaced = new LinkedHashMap<>();
        Map<Path, DocumentImportEntry> written = new LinkedHashMap<>();
        List<DocumentImportEntry> applied = new ArrayList<>();
        for (DocumentImportEntry entry : plan.entries()) {
            Path target = target(entry);
            if (entry.disposition() == LegacyImportDisposition.CONFLICT && !overwrite) {
                excluded.add(target);
                continue;
            }
            byte[] current = preparation.read(target);
            if (Arrays.equals(current, entry.json().getBytes(StandardCharsets.UTF_8))) {
                continue;
            }
            if (current != null) {
                replaced.put(target, current);
            }
            written.put(target, entry);
            applied.add(entry);
        }
        PreparedImport selected = preparation.excluding(excluded);
        selected.validateDocuments();
        selected.apply("import-" + plan.source().id(), transaction, coordinator, () -> {
            for (Map.Entry<Path, byte[]> previous : replaced.entrySet()) {
                DocumentImportEntry entry = written.get(previous.getKey());
                history.record(entry.kind(), entry.id(), previous.getValue(), "import:" + plan.source().id());
            }
        });
        return List.copyOf(applied);
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

    private List<DocumentImportEntry> withDispositions(List<DocumentImportEntry> entries,
                                                       PreparedImport preparation) throws IOException {
        List<DocumentImportEntry> resolved = new ArrayList<>(entries.size());
        byte[] presetBytes = preparation.read(dataDirectory.resolve(DocumentPresetCatalog.FILE_NAME));
        DocumentPresetCatalog presets = presetBytes == null ? DocumentPresetCatalog.empty()
            : DocumentPresetCatalog.parse(DocumentPresetCatalog.FILE_NAME,
                new String(presetBytes, StandardCharsets.UTF_8));
        for (DocumentImportEntry entry : entries) {
            Path target = target(entry);
            byte[] existing = preparation.read(target);
            if (existing == null) {
                resolved.add(entry);
                continue;
            }
            DocumentImportEntry replacement = revisionAfter(entry, existing, presets);
            resolved.add(replacement.withDisposition(LegacyImportDisposition.CONFLICT,
                "a document already exists at " + entry.path()));
        }
        return List.copyOf(resolved);
    }

    private DocumentImportEntry revisionAfter(DocumentImportEntry entry, byte[] existing,
                                               DocumentPresetCatalog presets) throws IOException {
        EditorSyncDocumentKind.ParsedDocument parsed;
        try {
            parsed = HistoryKinds.requireCollection(entry.kind()).parse(entry.id(),
                new String(existing, StandardCharsets.UTF_8), presets);
        } catch (IllegalArgumentException invalid) {
            return entry;
        }
        if (parsed.revision() == null) {
            return entry;
        }
        if (parsed.revision() == DocumentEnvelope.MAX_SAFE_REVISION) {
            throw new IOException("Import destination has exhausted its document revisions: " + entry.path());
        }
        JsonObject document = JsonParser.parseString(entry.json()).getAsJsonObject();
        document.addProperty("revision", parsed.revision() + 1);
        return new DocumentImportEntry(entry.kind(), entry.id(), LegacyBoardConverter.pretty(document),
            entry.disposition(), entry.dispositionReason(), entry.warnings());
    }

    private Path target(DocumentImportEntry entry) {
        EditorSyncDocumentKind kind = HistoryKinds.requireCollection(entry.kind());
        return kind.path(dataDirectory, entry.id());
    }

    private List<LegacyBoardDraft> scanBoards(Path root, PreparedImport preparation,
                                             BiFunction<String, String, LegacyBoardDraft> reader,
                                             GlossConfig.Imports limits) throws IOException {
        List<Path> sources = boardFiles(root, limits);
        preparation.checkBeforeApply(() -> {
            if (!sources.equals(boardFiles(root, limits))) {
                throw new IOException("Import source files changed after preview: " + root);
            }
        });
        List<LegacyBoardDraft> drafts = new ArrayList<>(sources.size());
        for (Path source : sources) {
            byte[] content = preparation.read(source);
            if (content == null) {
                throw new IOException("Import source disappeared while preparing: " + source);
            }
            String name = source.getFileName().toString();
            drafts.add(reader.apply(name.substring(0, name.lastIndexOf('.')),
                new String(content, StandardCharsets.UTF_8)));
        }
        return List.copyOf(drafts);
    }

    private List<Path> boardFiles(Path root, GlossConfig.Imports limits) throws IOException {
        if (!Files.exists(root, LinkOption.NOFOLLOW_LINKS)) {
            return List.of();
        }
        return new ImportSourceFiles(limits).collect(root, false, file -> {
            String name = file.getFileName().toString();
            return name.endsWith(".yml") || name.endsWith(".yaml");
        });
    }

    /** Where a replaced document's previous bytes go. */
    @FunctionalInterface
    public interface HistoryRecorder {
        void record(String kind, String id, byte[] content, String source);
    }
}
