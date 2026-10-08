package art.arcane.gloss.importer;

/**
 * Per-file outcome of the one-shot {@code plugins/holoui} import, recorded in
 * {@code holoui-import.json} under the wire id.
 */
public enum HoloUiImportDisposition {
    COPIED("copied"),
    SKIPPED_SECRET("skipped-secret"),
    OVERLAID_CONFIG_KEY("overlaid-config-key"),
    APPROXIMATED("approximated"),
    UNCHANGED("unchanged"),
    CONFLICT("conflict"),
    UNSUPPORTED("unsupported"),
    NOT_APPLIED("not-applied"),
    ERROR("error");

    private final String id;

    HoloUiImportDisposition(String id) {
        this.id = id;
    }

    public String id() {
        return id;
    }
}
