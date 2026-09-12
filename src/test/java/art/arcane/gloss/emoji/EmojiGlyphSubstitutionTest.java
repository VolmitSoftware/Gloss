package art.arcane.gloss.emoji;

import art.arcane.gloss.condition.ShowCondition;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;

class EmojiGlyphSubstitutionTest {
    private static final List<EmojiEntry> ENTRIES = List.of(
        new EmojiEntry("heart", "<3", "❤", true, ShowCondition.ALWAYS),
        new EmojiEntry("star", "", "✳", true, ShowCondition.ALWAYS),
        new EmojiEntry("hidden", "", "❄", false, ShowCondition.ALWAYS));

    private static final Map<String, String> GLYPHS = Map.of("heart", "[HEART]", "hidden", "[HIDDEN]");

    private static GlyphSubstitution hook(boolean applies) {
        return new GlyphSubstitution() {
            @Override
            public boolean appliesTo(org.bukkit.entity.Player viewer) {
                return applies;
            }

            @Override
            public Optional<String> glyphFor(String emojiId) {
                return Optional.ofNullable(GLYPHS.get(emojiId));
            }
        };
    }

    @Test
    void aPackViewerGetsTheGlyphInsteadOfTheUnicode() {
        String out = GlyphSubstitution.apply(hook(true), null, "I :heart: this", ENTRIES, null,
            ShowCondition::isAlwaysVisible);

        assertEquals("I [HEART] this", out);
    }

    @Test
    void triggersSubstituteTheSameWayTokensDo() {
        String out = GlyphSubstitution.apply(hook(true), null, "I <3 this", ENTRIES, null,
            ShowCondition::isAlwaysVisible);

        assertEquals("I [HEART] this", out);
    }

    @Test
    void aViewerWithoutThePackIsLeftForTheUnicodeReplacer() {
        String out = GlyphSubstitution.apply(hook(false), null, "I :heart: this", ENTRIES, null,
            ShowCondition::isAlwaysVisible);

        assertEquals("I :heart: this", out);
    }

    @Test
    void anEmojiWithNoGlyphIsLeftAlone() {
        String out = GlyphSubstitution.apply(hook(true), null, "a :star: b", ENTRIES, null,
            ShowCondition::isAlwaysVisible);

        assertEquals("a :star: b", out);
    }

    @Test
    void disabledEntriesAndDeniedPermissionsNeverSubstitute() {
        assertEquals("a :hidden: b", GlyphSubstitution.apply(hook(true), null, "a :hidden: b", ENTRIES, null,
            ShowCondition::isAlwaysVisible));
        assertEquals("a :heart: b", GlyphSubstitution.apply(hook(true), null, "a :heart: b", ENTRIES,
            id -> false, ShowCondition::isAlwaysVisible));
        assertEquals("a :heart: b", GlyphSubstitution.apply(hook(true), null, "a :heart: b", ENTRIES, null,
            show -> false));
    }

    @Test
    void noHookLeavesTheMessageUntouched() {
        assertEquals("I :heart: this", GlyphSubstitution.apply(null, null, "I :heart: this", ENTRIES, null,
            ShowCondition::isAlwaysVisible));
    }

    @Test
    void anEmptyMessageIsReturnedAsIs() {
        assertEquals("", GlyphSubstitution.apply(hook(true), null, "", ENTRIES, null,
            ShowCondition::isAlwaysVisible));
    }
}
