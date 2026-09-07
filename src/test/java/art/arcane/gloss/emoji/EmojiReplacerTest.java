package art.arcane.gloss.emoji;

import art.arcane.gloss.condition.ShowCondition;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

import java.util.ArrayList;
import java.util.List;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertEquals;

class EmojiReplacerTest {
    private final EmojiReplacer replacer = new EmojiReplacer(List.of(
        new EmojiEntry("heart", "<3", "❤", true, ShowCondition.ALWAYS),
        new EmojiEntry("star", "", "✳", true, ShowCondition.ALWAYS),
        new EmojiEntry("off", "", "X", false, ShowCondition.ALWAYS)
    ));

    static Stream<Arguments> messages() {
        return Stream.of(
            Arguments.of("tokenFormReplaces", ":heart:", "\u2764"),
            Arguments.of("triggerFormReplaces", "<3", "\u2764"),
            Arguments.of("tokenAndTriggerMixReplace", "I <3 :star:", "I \u2764 \u2733"),
            Arguments.of("repeatedTriggersAllReplace", "<3<3", "\u2764\u2764"),
            Arguments.of("disabledEmojiIsIgnored", ":off:", ":off:"),
            Arguments.of("textWithoutTokensIsUnchanged", "none", "none"),
            Arguments.of("nullMessageBecomesEmpty", null, "")
        );
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("messages")
    void applyReplacesEveryEnabledForm(String label, String message, String expected) {
        assertEquals(expected, replacer.apply(message), label);
    }

    @Test
    void predicateFiltersDisallowedIds() {
        assertEquals(":heart: ✳", replacer.apply(":heart: :star:", id -> id.equals("star")));
    }

    @Test
    void predicateAllowsPermittedTrigger() {
        assertEquals("❤", replacer.apply("<3", id -> id.equals("heart")));
    }

    @Test
    void predicateOnlyConsultedForEmojiPresentInMessage() {
        List<String> queried = new ArrayList<>();
        assertEquals("❤", replacer.apply(":heart:", id -> {
            queried.add(id);
            return true;
        }));
        assertEquals(List.of("heart"), queried);
    }
}
