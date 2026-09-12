package art.arcane.gloss.marker;

/** The marker whose expressions are being evaluated on this thread right now. */
public record MarkerContext(String id, double x, double y, double z, double distance) {
}
