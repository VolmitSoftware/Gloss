package art.arcane.gloss.util.common;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.format.TextColor;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;

class TextUtilsLegacyTest {
    @Test
    void legacyColourCodesBecomeMiniMessageTags() {
        Component parsed = TextUtils.parse("&cDanger");

        assertEquals("Danger", TextUtils.content(parsed));
        assertEquals(NamedTextColor.RED, parsed.color());
    }

    @Test
    void legacyHexSequencesSurviveTheMenuColourStage() {
        Component parsed = TextUtils.parse("§x§f§f§8§8§0§0Warm");

        assertEquals("Warm", TextUtils.content(parsed));
        assertEquals(TextColor.fromHexString("#ff8800"), parsed.color());
    }

    @Test
    void anIncompleteHexSequenceIsNotTreatedAsAColour() {
        assertEquals("§x", TextUtils.content(TextUtils.parse("§x§f§f")));
    }

    @Test
    void emojiCharactersPassThroughUnchanged() {
        assertEquals("Shop ☺", TextUtils.content(TextUtils.parse("Shop ☺")));
    }

    @Test
    void textWithoutLegacyMarkersReturnsTheSameInstance() {
        String plain = "Shop <bold>menu</bold> [ff8800] % | :smile:";

        assertSame(plain, TextUtils.translateLegacy(plain));
    }

    @Test
    void nullAndEmptyTranslateToEmpty() {
        assertEquals("", TextUtils.translateLegacy(null));
        assertEquals("", TextUtils.translateLegacy(""));
    }

    @Test
    void markedTextStillTranslates() {
        assertEquals("<reset><red>Danger", TextUtils.translateLegacy("&cDanger"));
        assertEquals("<reset><#ff8800>Warm", TextUtils.translateLegacy("§x§f§f§8§8§0§0Warm"));
        assertEquals("&z stays", TextUtils.translateLegacy("&z stays"));
    }
}
