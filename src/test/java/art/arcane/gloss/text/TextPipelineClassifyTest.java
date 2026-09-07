package art.arcane.gloss.text;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

import java.util.ArrayList;
import java.util.List;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class TextPipelineClassifyTest {
    @AfterEach
    void clearPublishedTriggers() {
        TextPipeline.publishEmojiTriggers(List.of());
    }

    static Stream<Arguments> lines() {
        return Stream.of(
            Arguments.of("plainText", "Hello world", 0),
            Arguments.of("nullLine", null, 0),
            Arguments.of("emptyLine", "", 0),
            Arguments.of("aPipeSetsTheFunctionFlag", "a|greet|b", TextPipeline.HAS_FUNCTION),
            Arguments.of("anExpressionSetsTheFunctionFlag", "{{ time.seconds }}", TextPipeline.HAS_FUNCTION),
            Arguments.of("aPercentSetsThePlaceholderFlag", "%player_name%", TextPipeline.HAS_PLACEHOLDER),
            Arguments.of("anAmpersandCodeSetsTheColorFlag", "&cDanger", TextPipeline.HAS_COLOR),
            Arguments.of("aSectionCodeSetsTheColorFlag", "\u00a7cDanger", TextPipeline.HAS_COLOR),
            Arguments.of("aBracketHexSetsTheColorFlag", "[ff8800]Warm", TextPipeline.HAS_COLOR),
            Arguments.of("twoColonsSetTheEmojiCandidateFlag", ":smile:", TextPipeline.HAS_EMOJI_CANDIDATE),
            Arguments.of("aSingleColonIsNotAnEmojiCandidate", "time: now", 0),
            Arguments.of("allFlagsCombineOnAMixedLine", "&a|fn| %ph% :smile:",
                TextPipeline.HAS_FUNCTION | TextPipeline.HAS_PLACEHOLDER
                    | TextPipeline.HAS_EMOJI_CANDIDATE | TextPipeline.HAS_COLOR)
        );
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("lines")
    void classifyFlagsEveryMarker(String label, String line, int expected) {
        assertEquals(expected, TextPipeline.classify(line), label);
    }

    @Test
    void aPublishedTriggerFirstCharSetsTheEmojiCandidateFlag() {
        TextPipeline.publishEmojiTriggers(List.of(":)", "<3"));

        assertEquals(TextPipeline.HAS_EMOJI_CANDIDATE, TextPipeline.classify("ok :)"));
        assertEquals(TextPipeline.HAS_EMOJI_CANDIDATE, TextPipeline.classify("hi <3"));
    }

    @Test
    void aNonAsciiTriggerFirstCharIsHonored() {
        TextPipeline.publishEmojiTriggers(List.of("☺x"));

        assertEquals(TextPipeline.HAS_EMOJI_CANDIDATE, TextPipeline.classify("smile ☺"));
    }

    @Test
    void republishingReplacesTheOldTriggerSet() {
        TextPipeline.publishEmojiTriggers(List.of("<3"));
        TextPipeline.publishEmojiTriggers(List.of());

        assertEquals(0, TextPipeline.classify("hi <3"));
    }

    @Test
    void nullAndEmptyTriggersAreIgnored() {
        List<String> triggers = new ArrayList<>();
        triggers.add(null);
        triggers.add("");
        triggers.add("<3");
        TextPipeline.publishEmojiTriggers(triggers);

        assertEquals(TextPipeline.HAS_EMOJI_CANDIDATE, TextPipeline.classify("hi <3"));
        assertEquals(0, TextPipeline.classify("plain"));
    }

    @Test
    void hasFunctionTracksRegistrationAndRemoval() {
        TextPipeline pipeline = new TextPipeline(null);

        assertFalse(pipeline.hasFunction("greet"));
        assertFalse(pipeline.hasFunction(null));

        pipeline.registerFunction("greet", player -> "hi");
        assertTrue(pipeline.hasFunction("greet"));

        pipeline.unregisterFunction("greet");
        assertFalse(pipeline.hasFunction("greet"));
    }
}
