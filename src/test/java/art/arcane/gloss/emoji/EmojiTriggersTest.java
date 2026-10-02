package art.arcane.gloss.emoji;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class EmojiTriggersTest {
    @Test
    void wordTriggersOnlyReplaceWholeWords() {
        assertEquals("lollipop [laugh] [laugh]! unlol lol2 lol_name",
            EmojiTriggers.replace("lollipop lol lol! unlol lol2 lol_name", "lol", "[laugh]"));
    }

    @Test
    void punctuationTriggersCanRepeatWithoutSpaces() {
        assertEquals("[heart][heart] <3eyes", EmojiTriggers.replace("<3<3 <3eyes", "<3", "[heart]"));
        assertEquals("hello[smile][smile]", EmojiTriggers.replace("hello:):)", ":)", "[smile]"));
    }

    @Test
    void unicodeLettersAndCombiningMarksStayInsideWords() {
        assertEquals("élol lolé lol\u0301 [laugh]", EmojiTriggers.replace("élol lolé lol\u0301 lol", "lol", "[laugh]"));
    }
}
