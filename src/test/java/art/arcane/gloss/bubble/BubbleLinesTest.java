package art.arcane.gloss.bubble;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;

class BubbleLinesTest {
    @Test
    void preservesDuplicateLinesInOrder() {
        assertEquals(List.of("hello world", "hello world"), BubbleLines.split("hello world hello world", 11));
    }

    @Test
    void preservesOrderingAcrossRepeatedWords() {
        assertEquals(List.of("aaa", "bbb", "aaa"), BubbleLines.split("aaa bbb aaa", 3));
    }

    @Test
    void stripsSectionColorCodesBeforeWrapping() {
        assertEquals(List.of("hello world"), BubbleLines.split("§7hello §aworld", 32));
    }

    @Test
    void colorOnlyMessagesYieldNoLines() {
        assertEquals(List.of(), BubbleLines.split("§7§l", 32));
    }

    @Test
    void nullMessageYieldsNoLines() {
        assertEquals(List.of(), BubbleLines.split(null, 32));
    }
}
