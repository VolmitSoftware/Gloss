package art.arcane.gloss.emoji;

import art.arcane.gloss.condition.ShowCondition;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Random;
import java.util.function.Predicate;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * The tokenizing replacer must be output-identical to the entry-by-entry loop it replaced,
 * including the order effects of sequential {@code String.replace} passes: a replacement can
 * destroy a later entry's match or create one. The loop below is that reference, kept here as a
 * test-only oracle and compared against randomized registries and messages.
 */
class EmojiReplacerOracleTest {
    private static final int CASES = 200;
    private static final long SEED = 0x51055EED12345678L;
    private static final char[] MESSAGE_ALPHABET =
        {':', ':', ':', 'a', 'b', 'c', 'x', '<', '3', '~', ' ', '&', '%', '1', '☹'};
    private static final String[] IDS = {"a", "b", "ab", "ba", "abc", "x", "c1", "heart", ""};
    private static final String[] TRIGGERS = {"", "", "", "<3", "~~", "☹x", ":(", "ab"};
    private static final String[] VALUES = {"❤", "✳", "Z", "ab", ":", "::", "<3"};

    @Test
    void randomizedRegistriesAndMessagesMatchTheSequentialLoop() {
        Random random = new Random(SEED);
        for (int testCase = 0; testCase < CASES; testCase++) {
            List<EmojiEntry> entries = randomEntries(random);
            String message = randomMessage(random);
            Predicate<String> idAllowed = random.nextInt(4) == 0 ? id -> id.length() != 1 : null;
            EmojiReplacer replacer = new EmojiReplacer(entries);

            assertEquals(oracle(entries, message, idAllowed), replacer.apply(message, idAllowed),
                "case " + testCase + " message=" + message + " entries=" + describe(entries));
        }
    }

    @Test
    void aReplacementValueThatIntroducesALaterTokenStillCascades() {
        List<EmojiEntry> entries = List.of(
            new EmojiEntry("a", "", ":b:", true, ShowCondition.ALWAYS),
            new EmojiEntry("b", "", "B", true, ShowCondition.ALWAYS));

        assertEquals(oracle(entries, ":a:", null), new EmojiReplacer(entries).apply(":a:"));
    }

    @Test
    void overlappingTokensResolveInEntryOrder() {
        List<EmojiEntry> entries = List.of(
            new EmojiEntry("b", "", "B", true, ShowCondition.ALWAYS),
            new EmojiEntry("a", "", "A", true, ShowCondition.ALWAYS));

        assertEquals(oracle(entries, ":a:b:", null), new EmojiReplacer(entries).apply(":a:b:"));
        assertEquals(oracle(entries, ":b:a:", null), new EmojiReplacer(entries).apply(":b:a:"));
    }

    private static List<EmojiEntry> randomEntries(Random random) {
        int count = 1 + random.nextInt(6);
        List<EmojiEntry> entries = new ArrayList<>(count);
        for (int i = 0; i < count; i++) {
            String id = IDS[random.nextInt(IDS.length)];
            String trigger = TRIGGERS[random.nextInt(TRIGGERS.length)];
            String value = VALUES[random.nextInt(VALUES.length)];
            boolean enabled = random.nextInt(8) != 0;
            ShowCondition show = switch (random.nextInt(10)) {
                case 0 -> ShowCondition.NEVER;
                case 1 -> ShowCondition.of("viewer.world == 'arena'");
                default -> ShowCondition.ALWAYS;
            };
            entries.add(new EmojiEntry(id, trigger, value, enabled, show));
        }
        return entries;
    }

    private static String randomMessage(Random random) {
        int length = random.nextInt(24);
        StringBuilder message = new StringBuilder(length + 8);
        for (int i = 0; i < length; i++) {
            message.append(MESSAGE_ALPHABET[random.nextInt(MESSAGE_ALPHABET.length)]);
        }
        if (random.nextInt(3) == 0) {
            message.append(':').append(IDS[random.nextInt(IDS.length)]).append(':');
        }
        return message.toString();
    }

    private static String describe(List<EmojiEntry> entries) {
        StringBuilder text = new StringBuilder();
        for (EmojiEntry entry : entries) {
            text.append('[').append(entry.id()).append('|').append(entry.trigger()).append("->")
                .append(entry.emoji()).append('|').append(entry.enabled()).append(']');
        }
        return text.toString();
    }

    /** The entry-by-entry replace loop exactly as {@code EmojiReplacer.apply} ran it. */
    private static String oracle(List<EmojiEntry> entries, String message, Predicate<String> idAllowed) {
        if (message == null || message.isEmpty()) {
            return message == null ? "" : message;
        }
        List<EmojiEntry> active = new ArrayList<>(entries.size());
        for (EmojiEntry entry : entries) {
            if (entry.enabled() && (entry.show().isAlwaysVisible() || entry.show().isDynamic())) {
                active.add(entry);
            }
        }
        if (active.isEmpty()) {
            return message;
        }

        String out = message;
        for (EmojiEntry entry : active) {
            String trigger = entry.hasTrigger() ? entry.trigger() : null;
            boolean hasToken = out.contains(entry.token());
            boolean hasTrigger = trigger != null && out.contains(trigger);
            if (!hasToken && !hasTrigger) {
                continue;
            }
            if ((idAllowed != null && !idAllowed.test(entry.id())) || !entry.show().isAlwaysVisible()) {
                continue;
            }
            if (hasTrigger) {
                out = out.replace(trigger, entry.emoji());
            }
            if (hasToken) {
                out = out.replace(entry.token(), entry.emoji());
            }
        }
        return out;
    }
}
