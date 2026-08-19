package art.arcane.gloss.integration.catalog;

/**
 * Outcome of one export. {@code discarded} counts ids a provider advertised but could not actually
 * resolve, which is normal for packs that reference items they no longer ship.
 */
public record CustomItemCatalogResult(
    boolean success,
    int providerCount,
    int itemCount,
    int discarded,
    String path
) {
}
