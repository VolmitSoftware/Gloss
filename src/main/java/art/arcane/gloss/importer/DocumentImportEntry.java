package art.arcane.gloss.importer;

import java.util.List;
import java.util.Objects;

/** One document a legacy source converts into, and what could not be carried across. */
public record DocumentImportEntry(String kind, String id, String json,
                                  LegacyImportDisposition disposition, String dispositionReason,
                                  List<String> warnings) {
    public DocumentImportEntry {
        kind = Objects.requireNonNull(kind, "kind");
        id = Objects.requireNonNull(id, "id");
        json = Objects.requireNonNull(json, "json");
        disposition = Objects.requireNonNull(disposition, "disposition");
        dispositionReason = dispositionReason == null ? "" : dispositionReason;
        warnings = List.copyOf(Objects.requireNonNull(warnings, "warnings"));
    }

    public DocumentImportEntry withDisposition(LegacyImportDisposition next, String reason) {
        return new DocumentImportEntry(kind, id, json, next, reason, warnings);
    }

    public String path() {
        return kind + "/" + id + ".json";
    }
}
