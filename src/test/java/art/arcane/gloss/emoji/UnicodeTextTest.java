package art.arcane.gloss.emoji;

import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertEquals;

class UnicodeTextTest {
    static Stream<Arguments> escapes() {
        return Stream.of(
            Arguments.of("singleCodepoint", "U+2764;", "❤"),
            Arguments.of("multipleCodepointsConcatenate", "U+2764;U+FE0F;", "❤️"),
            Arguments.of("supplementaryCodepointExpandsToSurrogatePair", "U+1F600;", "😀"),
            Arguments.of("codepointEmbeddedInText", "a U+2764; b", "a ❤ b"),
            Arguments.of("invalidHexBecomesQuestionMark", "U+ZZZZ;", "?"),
            Arguments.of("outOfRangeCodepointBecomesQuestionMark", "U+110000;", "?"),
            Arguments.of("unterminatedValidEscapeParsesAtEndOfInput", "U+2764", "❤"),
            Arguments.of("unterminatedValidEscapeAfterText", "a U+2764", "a ❤"),
            Arguments.of("unterminatedInvalidEscapeIsReEmittedLiterally", "a U+ZZZZ", "a U+ZZZZ"),
            Arguments.of("trailingEscapePrefixIsReEmittedLiterally", "a U+", "a U+"),
            Arguments.of("plainTextWithUppercaseUPassesThrough", "Universe", "Universe"),
            Arguments.of("nullParsesToEmpty", null, ""),
            Arguments.of("emptyParsesToEmpty", "", "")
        );
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("escapes")
    void parseResolvesEscapes(String label, String input, String expected) {
        assertEquals(expected, UnicodeText.parse(input), label);
    }
}
