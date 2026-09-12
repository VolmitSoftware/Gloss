package art.arcane.gloss.importer;

import java.util.List;
import java.util.Objects;

/** What importing one legacy source would produce, before anything is written. */
public record DocumentImportPlan(LegacyImportSource source, String sourcePath, boolean sourcePresent,
                                 List<DocumentImportEntry> entries, List<LegacyImportIssue> issues) {
    public DocumentImportPlan {
        source = Objects.requireNonNull(source, "source");
        sourcePath = Objects.requireNonNull(sourcePath, "sourcePath");
        entries = List.copyOf(Objects.requireNonNull(entries, "entries"));
        issues = List.copyOf(Objects.requireNonNull(issues, "issues"));
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
