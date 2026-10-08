package art.arcane.gloss.importer;

import java.util.List;
import java.util.Objects;

/** What importing one legacy source would produce, before anything is written. */
public final class DocumentImportPlan {
    private final LegacyImportSource source;
    private final String sourcePath;
    private final boolean sourcePresent;
    private final List<DocumentImportEntry> entries;
    private final List<LegacyImportIssue> issues;
    private final DocumentImportService owner;
    private final PreparedImport preparation;

    public DocumentImportPlan(LegacyImportSource source, String sourcePath, boolean sourcePresent,
                              List<DocumentImportEntry> entries, List<LegacyImportIssue> issues) {
        this(source, sourcePath, sourcePresent, entries, issues, null, null);
    }

    DocumentImportPlan(LegacyImportSource source, String sourcePath, boolean sourcePresent,
                       List<DocumentImportEntry> entries, List<LegacyImportIssue> issues,
                       DocumentImportService owner, PreparedImport preparation) {
        this.source = Objects.requireNonNull(source, "source");
        this.sourcePath = Objects.requireNonNull(sourcePath, "sourcePath");
        this.sourcePresent = sourcePresent;
        this.entries = List.copyOf(Objects.requireNonNull(entries, "entries"));
        this.issues = List.copyOf(Objects.requireNonNull(issues, "issues"));
        this.owner = owner;
        this.preparation = preparation;
    }

    public LegacyImportSource source() {
        return source;
    }

    public String sourcePath() {
        return sourcePath;
    }

    public boolean sourcePresent() {
        return sourcePresent;
    }

    public List<DocumentImportEntry> entries() {
        return entries;
    }

    public List<LegacyImportIssue> issues() {
        return issues;
    }

    public long retainedBytes() {
        return preparation == null ? 0 : preparation.retainedBytes();
    }

    PreparedImport preparation(DocumentImportService importer) {
        if (owner != importer || preparation == null) {
            throw new IllegalArgumentException("Apply requires a plan previewed by this importer");
        }
        return preparation;
    }

    public long readyCount() {
        return entries.stream()
                .filter(entry -> entry.disposition() == LegacyImportDisposition.READY)
                .count();
    }

    public long conflictCount() {
        return entries.stream()
                .filter(entry -> entry.disposition() == LegacyImportDisposition.CONFLICT)
                .count();
    }

    public long warningCount() {
        return entries.stream().mapToLong(entry -> entry.warnings().size()).sum()
                + issues.stream()
                .filter(issue -> issue.severity() == LegacyImportIssue.Severity.WARNING)
                .count();
    }
}
