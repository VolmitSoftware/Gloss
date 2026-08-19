package art.arcane.gloss.util.common;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.format.TextColor;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

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
}
