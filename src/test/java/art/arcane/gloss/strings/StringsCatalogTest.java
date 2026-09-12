package art.arcane.gloss.strings;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

class StringsCatalogTest {
    private static final StringsCatalog CATALOG = StringsCatalog.of(List.of(
        doc("en_US", "", Map.of("shop.title", "Shop", "shop.buy", "Buy {item} for {price}", "hud.only.en", "English")),
        doc("de_DE", "fr_FR", Map.of("shop.title", "Laden")),
        doc("fr_FR", "", Map.of("shop.buy", "Achete {item} pour {price}"))));

    @Test
    void aLocaleOwnEntryWins() {
        assertEquals("Laden", CATALOG.resolve("de_DE", "shop.title"));
    }

    @Test
    void aMissingEntryFallsThroughTheDocumentFallbackThenEnglish() {
        assertEquals("Achete {item} pour {price}", CATALOG.resolve("de_DE", "shop.buy"));
        assertEquals("English", CATALOG.resolve("de_DE", "hud.only.en"));
    }

    @Test
    void anUnknownLocaleReadsEnglishAndAnUnknownKeyResolvesToNull() {
        assertEquals("Shop", CATALOG.resolve("ja_JP", "shop.title"));
        assertNull(CATALOG.resolve("de_DE", "shop.missing"));
    }

    @Test
    void aFallbackCycleTerminatesAtEnglish() {
        StringsCatalog cyclic = StringsCatalog.of(List.of(
            doc("en_US", "", Map.of("a", "A")),
            doc("de_DE", "fr_FR", Map.of()),
            doc("fr_FR", "de_DE", Map.of())));

        assertEquals("A", cyclic.resolve("de_DE", "a"));
        assertNull(cyclic.resolve("de_DE", "b"));
    }

    @Test
    void missingListsEnglishKeysTheLocaleDoesNotCarry() {
        assertEquals(List.of("hud.only.en", "shop.buy"), CATALOG.missing("de_DE"));
    }

    @Test
    void argumentsBindPositionallyOntoTheTemplatePlaceholders() {
        assertEquals("Buy Iron Ore for 12",
            StringsCatalog.bind("Buy {item} for {price}", List.of("shop.buy", "Iron Ore", 12.0D)));
    }

    @Test
    void boundArgumentsCannotSmuggleColourCodes() {
        assertEquals("Buy Iron Ore for 12",
            StringsCatalog.bind("Buy {item} for {price}", List.of("shop.buy", "&cIron Ore", "§a12")));
    }

    @Test
    void aTemplateWithNoArgumentsIsReturnedUnchanged() {
        assertEquals("Buy {item} for {price}",
            StringsCatalog.bind("Buy {item} for {price}", List.of("shop.buy")));
    }

    private static StringsDoc doc(String locale, String fallback, Map<String, String> entries) {
        return new StringsDoc(StringsDoc.CURRENT_SCHEMA_VERSION, 1L, locale, fallback, entries);
    }
}
