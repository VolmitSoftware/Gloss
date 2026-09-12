package art.arcane.gloss.strings;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

class ContentLocaleResolutionTest {
    @Test
    void anExplicitPluginLanguageChoiceWins() {
        assertEquals("fr_FR", StringsService.contentLocale("fr_FR", "de_de", "en_US"));
    }

    @Test
    void theClientLocaleIsUsedWhenNoChoiceWasMadeAndIsNormalised() {
        assertEquals("de_DE", StringsService.contentLocale(null, "de_de", "en_US"));
        assertEquals("pt_BR", StringsService.contentLocale("", "pt-br", "en_US"));
    }

    @Test
    void theServerLanguageIsTheLastResort() {
        assertEquals("en_US", StringsService.contentLocale(null, null, "en_US"));
        assertEquals("en_US", StringsService.contentLocale(null, "nonsense", "en_US"));
        assertEquals(StringsCatalog.ROOT_LOCALE, StringsService.contentLocale(null, null, "also nonsense"));
    }

    @Test
    void normalisationRejectsAnythingThatIsNotLanguageCountry() {
        assertEquals("zh_CN", StringsService.normalizeLocale("ZH-cn"));
        assertNull(StringsService.normalizeLocale("en"));
        assertNull(StringsService.normalizeLocale(""));
        assertNull(StringsService.normalizeLocale(null));
    }
}
