package art.arcane.gloss.integration.catalog;

/**
 * One catalog row. {@code id} is the provider's own id format, case preserved, and {@code material}
 * is the resolved stack's lowercase vanilla key so the editor can draw an approximate sprite.
 */
public record CustomItemEntry(String provider, String id, String name, String material) {
}
