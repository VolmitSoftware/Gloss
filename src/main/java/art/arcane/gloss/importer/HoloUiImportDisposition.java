package art.arcane.gloss.importer;

/**
 * Per-file outcome of the one-shot {@code plugins/holoui} import, recorded in
 * {@code holoui-import.json} under the wire id.
 */
public enum HoloUiImportDisposition {
    COPIED("copied"),
    SKIPPED_SHIPPED_IDENTICAL("skipped-shipped-identical"),
    SKIPPED_EXISTING("skipped-existing"),
    SKIPPED_SECRET("skipped-secret"),
    SKIPPED_RETIRED_ENDPOINT("skipped-retired-endpoint"),
    OVERLAID_CONFIG_KEY("overlaid-config-key"),
    ERROR("error");

    private final String id;

    HoloUiImportDisposition(String id) {
        this.id = id;
    }

    public String id() {
        return id;
    }
}
