package art.arcane.gloss.text;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class TextPipelineClassifyTest {
    @AfterEach
    void clearPublishedTriggers() {
        TextPipeline.publishEmojiTriggers(List.of());
    }

    @Test
    void plainTextClassifiesToZero() {
        assertEquals(0, TextPipeline.classify("Hello world"));
    }

    @Test
    void nullAndEmptyClassifyToZero() {
        assertEquals(0, TextPipeline.classify(null));
        assertEquals(0, TextPipeline.classify(""));
    }

    @Test
    void aPipeSetsTheFunctionFlag() {
        assertEquals(TextPipeline.HAS_FUNCTION, TextPipeline.classify("a|greet|b"));
        assertEquals(TextPipeline.HAS_FUNCTION, TextPipeline.classify("{{ time.seconds }}"));
    }

    @Test
    void aPercentSetsThePlaceholderFlag() {
        assertEquals(TextPipeline.HAS_PLACEHOLDER, TextPipeline.classify("%player_name%"));
    }

    @Test
    void everyColorMarkerSetsTheColorFlag() {
        assertEquals(TextPipeline.HAS_COLOR, TextPipeline.classify("&cDanger"));
        assertEquals(TextPipeline.HAS_COLOR, TextPipeline.classify("§cDanger"));
        assertEquals(TextPipeline.HAS_COLOR, TextPipeline.classify("[ff8800]Warm"));
    }

    @Test
    void twoColonsSetTheEmojiCandidateFlag() {
        assertEquals(TextPipeline.HAS_EMOJI_CANDIDATE, TextPipeline.classify(":smile:"));
    }

    @Test
    void aSingleColonIsNotAnEmojiCandidate() {
        assertEquals(0, TextPipeline.classify("time: now"));
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
    void allFlagsCombineOnAMixedLine() {
        int expected = TextPipeline.HAS_FUNCTION | TextPipeline.HAS_PLACEHOLDER
            | TextPipeline.HAS_EMOJI_CANDIDATE | TextPipeline.HAS_COLOR;

        assertEquals(expected, TextPipeline.classify("&a|fn| %ph% :smile:"));
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
