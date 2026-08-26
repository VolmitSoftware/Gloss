package art.arcane.gloss.bubble;

import art.arcane.gloss.particle.ParticleText;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class BubbleTextBlockTest {
    @Test
    void wrapsDuplicateWordsInOrderInsideOneTextBlock() {
        assertEquals(List.of("hello world", "hello world"), BubbleTextBlock.wrap("", "hello world hello world", 11));
        assertEquals(List.of("aaa", "bbb", "aaa"), BubbleTextBlock.wrap("", "aaa bbb aaa", 3));
    }

    @Test
    void keepsLegacyFormattingAndCarriesItAcrossWrappedLines() {
        List<String> lines = BubbleTextBlock.wrap("§7", "§1Hello wonderful world", 8);

        assertEquals(List.of("§7§1Hello", "§1wonderfu", "§1l world"), lines);
    }

    @Test
    void keepsCompleteRgbRunsAcrossWrappedLines() {
        String rgb = "§x§1§2§3§4§5§6";
        List<String> lines = BubbleTextBlock.wrap("", rgb + "abcdef", 3);

        assertEquals(List.of(rgb + "abc", rgb + "def"), lines);
    }

    @Test
    void carriesDecorationsUntilAColorOrResetClearsThem() {
        List<String> decorated = BubbleTextBlock.wrap("", "§a§lbold words", 4);
        List<String> reset = BubbleTextBlock.wrap("", "§a§lbold §rplain", 4);

        assertEquals(List.of("§a§lbold", "§a§lword", "§a§ls"), decorated);
        assertEquals(List.of("§a§lbold", "§a§l§rplai", "n"), reset);
    }

    @Test
    void rawAmpersandCodesRemainVisibleLiteralText() {
        assertEquals(List.of("&1Hello!!!"), BubbleTextBlock.wrap("", "&1Hello!!!", 32));
    }

    @Test
    void prefixFormattingAppliesWithoutConsumingVisibleWidth() {
        assertEquals(List.of("§bhello", "§bworld"), BubbleTextBlock.wrap("§b", "hello world", 5));
    }

    @Test
    void hardWrapNeverSplitsSurrogatePairs() {
        List<String> lines = BubbleTextBlock.wrap("", "A😀BC", 2);

        assertEquals(List.of("A😀", "BC"), lines);
        assertTrue(lines.stream().noneMatch(line -> line.contains("�")));
    }

    @Test
    void usesTheSharedSpaceTabAndUnicodeNewlineRules() {
        assertEquals(List.of("one", "two", "three"), BubbleTextBlock.wrap("", "one\ttwo three", 5));
        assertEquals(List.of("a", "b", "c", "d"), BubbleTextBlock.wrap("", "a\r\nb\rc\u2028d\u2029", 8));
        assertEquals(List.of("a\u00A0b"), BubbleTextBlock.wrap("", "a\u00A0b", 8));
        assertEquals(List.of("first", "", "second"), BubbleTextBlock.wrap("", "first\n\nsecond", 32));
    }

    @Test
    void formattingOnlyAndNullMessagesYieldNoLines() {
        assertEquals(List.of(), BubbleTextBlock.wrap("§7§l", "", 32));
        assertEquals(List.of(), BubbleTextBlock.wrap("§7", null, 32));
    }

    @Test
    void dynamicTrustedPrefixIsRerenderedWithoutReprocessingChatText() {
        AtomicInteger frame = new AtomicInteger();
        String message = "|animation.rainbow| &1literal";

        List<String> first = ChatBubblesService.renderTextBlock("|animation.prefix|", message, 64,
            prefix -> frame.getAndIncrement() == 0 ? "§c" : "§b");
        List<String> second = ChatBubblesService.renderTextBlock("|animation.prefix|", message, 64,
            prefix -> frame.getAndIncrement() == 1 ? "§b" : "§a");

        assertEquals(List.of("§c" + message), first);
        assertEquals(List.of("§b" + message), second);
        assertEquals(2, frame.get());
    }

    @Test
    void configuredParticleSpansSurviveWrappingWithoutParsingPlayerText() {
        ParticleText.Rendered rendered = ChatBubblesService.renderParticleTextBlock(
            "<particles:rank>&4VIP</particles> ",
            "<particles:injected>hello</particles>", 64,
            prefix -> prefix.replace("&4", "§4"));

        assertEquals("§4VIP <particles:injected>hello</particles>", rendered.text());
        assertEquals(1, rendered.spans().size());
        assertEquals("rank", rendered.spans().getFirst().name());
        assertEquals("§4VIP", rendered.text().substring(
            rendered.spans().getFirst().start(), rendered.spans().getFirst().end()));
    }
}
