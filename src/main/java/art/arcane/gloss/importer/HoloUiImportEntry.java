package art.arcane.gloss.importer;

import java.util.Objects;

/**
 * One receipt line of the HoloUi data import: the source-relative path (or
 * {@code settings.json:<key>} for config overlays), its category for the summary log, the
 * disposition, and an optional human detail.
 */
public record HoloUiImportEntry(String category, String path, HoloUiImportDisposition disposition, String detail) {
    public HoloUiImportEntry {
        category = Objects.requireNonNull(category, "category");
        path = Objects.requireNonNull(path, "path");
        disposition = Objects.requireNonNull(disposition, "disposition");
    }

    public static HoloUiImportEntry of(String category, String path, HoloUiImportDisposition disposition) {
        return new HoloUiImportEntry(category, path, disposition, null);
    }
}
