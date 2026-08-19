package art.arcane.gloss.integration.catalog;

import java.util.List;

/**
 * The exported custom item catalog. Component names are the JSON keys the web editor reads, so
 * renaming one breaks every editor build that already shipped.
 */
public record CustomItemCatalog(
    int version,
    long generated,
    List<String> providers,
    List<CustomItemEntry> items
) {
  public static final int VERSION = 1;
}
