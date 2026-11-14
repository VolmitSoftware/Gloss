package art.arcane.gloss.util.common;

import art.arcane.gloss.text.ClientTextLines;
import art.arcane.gloss.text.ClientTextLines.Run;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.format.TextColor;
import net.kyori.adventure.text.format.TextDecoration;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;

class TextUtilsLegacyTest {
    private static final int WHITE = NamedTextColor.WHITE.value();

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
    void aColourCodeTurnsEveryFormatOffInsteadOfLeavingItToTheParent() {
        assertEquals(List.of(List.of(Run.plain("Info", NamedTextColor.GRAY.value()))),
            underEveryFormat(TextUtils.parseOverFormats("&7Info")));
        assertEquals(List.of(List.of(Run.plain("Warm", 0xFF8800))),
            underEveryFormat(TextUtils.parseOverFormats("§x§f§f§8§8§0§0Warm")));
        assertEquals(List.of(List.of(Run.italic("Title", NamedTextColor.GOLD.value()))),
            underItalic(TextUtils.parseOverFormats("<gold>Title")));
        assertEquals(List.of(List.of(Run.plain("Bob", NamedTextColor.RED.value()))),
            underEveryFormat(TextUtils.parseLegacyOverFormats("§cBob")));
        assertEquals(List.of(List.of(new Run("A", WHITE, true, true, true, true, true), Run.plain("B", 0x123456))),
            underEveryFormat(TextUtils.parseLegacyOverFormats("§lA§x§1§2§3§4§5§6B")));
    }

    @Test
    void aResetLeavesTheStyleToTheParent() {
        assertEquals(List.of(List.of(Run.plain("A", NamedTextColor.RED.value()),
                new Run("B", WHITE, true, true, true, true, true))),
            underEveryFormat(TextUtils.parseLegacyOverFormats("§cA§rB")));
        assertEquals(List.of(List.of(Run.plain("A", NamedTextColor.RED.value()),
                new Run("B", WHITE, true, true, true, true, true))),
            underEveryFormat(TextUtils.parseOverFormats("&cA&rB")));
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

    private static List<List<Run>> underEveryFormat(Component parsed) {
        return ClientTextLines.lines(Component.text().decorate(TextDecoration.values()).append(parsed).build());
    }

    private static List<List<Run>> underItalic(Component parsed) {
        return ClientTextLines.lines(Component.text().decorate(TextDecoration.ITALIC).append(parsed).build());
    }
}
