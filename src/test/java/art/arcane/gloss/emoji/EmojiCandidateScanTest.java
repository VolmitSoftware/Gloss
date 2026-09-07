package art.arcane.gloss.emoji;

import art.arcane.gloss.condition.ShowCondition;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * The replace loop used to touch every registered emoji on every rendered string that carried a
 * colon, so board text like {@code 12:30:15} paid two {@code String.contains} scans per entry.
 * These pin the candidate scan that replaced it: one forward pass over the message decides which
 * entries the loop may look at, and a message that cannot match yields none.
 */
class EmojiCandidateScanTest {
    private static final List<EmojiEntry> ENTRIES = List.of(
        new EmojiEntry("heart", "", "❤", true, ShowCondition.ALWAYS),
        new EmojiEntry("star", "", "✳", true, ShowCondition.ALWAYS),
        new EmojiEntry("shrug", "<3", "🤷", true, ShowCondition.ALWAYS));

    private final EmojiReplacer replacer = new EmojiReplacer(ENTRIES);

    @Test
    void aClockStringSpendsNothingOnTheRegistry() {
        assertEquals(0, replacer.candidates("&7Time: &f12:30:15", 0).length);
    }

    @Test
    void onlyTheReferencedEntryBecomesACandidate() {
        assertArrayEquals(new int[]{0}, replacer.candidates("&7Rank :heart: yes", 0));
        assertArrayEquals(new int[]{1}, replacer.candidates(":star:", 0));
    }

    @Test
    void aTriggerIsFoundWithoutScanningTokenEntries() {
        assertArrayEquals(new int[]{2}, replacer.candidates("I <3 you", 0));
    }

    @Test
    void adjacentTokensAreAllFoundEvenWhenTheyShareColons() {
        assertArrayEquals(new int[]{0, 1}, replacer.candidates(":heart:star:", 0));
    }

    @Test
    void theMinimumEntryBoundSkipsAlreadyProcessedEntries() {
        assertArrayEquals(new int[]{1}, replacer.candidates(":heart::star:", 1));
    }

    @Test
    void anIdCarryingAColonDoesNotMakeEveryStringACandidate() {
        EmojiReplacer awkward = new EmojiReplacer(List.of(
            new EmojiEntry("a:b", "", "AB", true, ShowCondition.ALWAYS)));

        assertEquals(0, awkward.candidates("no markers at all", 0).length);
        assertEquals(1, awkward.candidates("say :a:b: now", 0).length);
    }

    @Test
    void anUnknownTokenIsNotACandidate() {
        assertEquals(0, replacer.candidates(":nope: :also-nope:", 0).length);
    }

    @Test
    void everyCandidateIsAtLeastTheSetTheLoopWouldHaveMatched() {
        List<String> messages = List.of("", "a:b:c", "::", ":heart:", ":star::heart:", "<3<3",
            "12:30:15", ":heart:star:", "x:star:heart:y", "no markers at all");
        for (String message : messages) {
            List<Integer> candidates = new ArrayList<>();
            for (int candidate : replacer.candidates(message, 0)) {
                candidates.add(candidate);
            }
            for (int index = 0; index < ENTRIES.size(); index++) {
                EmojiEntry entry = ENTRIES.get(index);
                boolean matches = message.contains(entry.token())
                    || (entry.hasTrigger() && message.contains(entry.trigger()));
                if (matches) {
                    assertEquals(true, candidates.contains(index),
                        "entry " + entry.id() + " must be a candidate for " + message);
                }
            }
        }
    }
}
