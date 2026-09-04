package art.arcane.gloss.emoji;

import art.arcane.gloss.condition.ShowCondition;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;

class EmojiReplacerTest {
    private final EmojiReplacer replacer = new EmojiReplacer(List.of(
        new EmojiEntry("heart", "<3", "❤", true, ShowCondition.ALWAYS),
        new EmojiEntry("star", "", "✳", true, ShowCondition.ALWAYS),
        new EmojiEntry("off", "", "X", false, ShowCondition.ALWAYS)
    ));

    @Test
    void tokenFormReplaces() {
        assertEquals("❤", replacer.apply(":heart:"));
    }

    @Test
    void triggerFormReplaces() {
        assertEquals("❤", replacer.apply("<3"));
    }

    @Test
    void tokenAndTriggerMixReplace() {
        assertEquals("I ❤ ✳", replacer.apply("I <3 :star:"));
    }

    @Test
    void repeatedTriggersAllReplace() {
        assertEquals("❤❤", replacer.apply("<3<3"));
    }

    @Test
    void disabledEmojiIsIgnored() {
        assertEquals(":off:", replacer.apply(":off:"));
    }

    @Test
    void textWithoutTokensIsUnchanged() {
        assertEquals("none", replacer.apply("none"));
    }

    @Test
    void nullMessageBecomesEmpty() {
        assertEquals("", replacer.apply(null));
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
