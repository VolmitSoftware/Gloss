package art.arcane.gloss.text;

import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertEquals;

class TextPipelineColorTest {
    private final TextPipeline pipeline = new TextPipeline(null);

    static Stream<Arguments> colorSpellings() {
        return Stream.of(
            Arguments.of("bracketHexTranslatesToSectionXSequence", "[FF00AA]Hi", "§x§f§f§0§0§a§aHi"),
            Arguments.of("bracketHexAcceptsLowercase", "[ff00aa]Hi", "§x§f§f§0§0§a§aHi"),
            Arguments.of("multipleBracketHexSequencesTranslate", "[FF0000]A[00FF00]B",
                "§x§f§f§0§0§0§0A§x§0§0§f§f§0§0B"),
            Arguments.of("shortBracketContentIsUntouched", "[FF00A]X", "[FF00A]X"),
            Arguments.of("nonHexBracketContentIsUntouched", "[GG0011]X", "[GG0011]X"),
            Arguments.of("unterminatedBracketIsUntouched", "[FF00AA", "[FF00AA"),
            Arguments.of("emptyBracketsAreUntouched", "[]", "[]"),
            Arguments.of("sevenDigitBracketIsUntouched", "[FFF00AA]", "[FFF00AA]"),
            Arguments.of("ampersandCodesTranslate", "&cHi", "§cHi"),
            Arguments.of("alreadyTranslatedSectionTextPassesThrough", "§bHi", "§bHi"),
            Arguments.of("bracketHexAndAmpersandCombine", "[FF00AA]&lBold", "§x§f§f§0§0§a§a§lBold"),
            Arguments.of("hashHexSpelling", "&#FF00AAHi", "§x§f§f§0§0§a§aHi"),
            Arguments.of("ampersandXSpelling", "&xFF00AAHi", "§x§f§f§0§0§a§aHi"),
            Arguments.of("perNibbleAmpersandXSpelling", "&x&F&F&0&0&A&AHi", "§x§f§f§0§0§a§aHi"),
            Arguments.of("plainTextIsUnchanged", "plain text", "plain text")
        );
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("colorSpellings")
    void renderStaticTranslatesColors(String label, String input, String expected) {
        assertEquals(expected, pipeline.renderStatic(input), label);
    }
}
