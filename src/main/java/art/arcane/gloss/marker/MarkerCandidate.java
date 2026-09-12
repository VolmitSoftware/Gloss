package art.arcane.gloss.marker;

/** One marker with the viewer-relative facts the selection pass needs. */
public record MarkerCandidate(MarkerRuntime runtime, String world, double x, double y, double z,
                              double distance) {
    public String id() {
        return runtime.id();
    }

    public MarkerSpec spec() {
        return runtime.spec();
    }
}
