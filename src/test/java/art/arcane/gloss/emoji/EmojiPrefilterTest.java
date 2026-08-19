package art.arcane.gloss.emoji;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Predicate;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;

/**
 * Pins the {@link EmojiReplacer} prefilter as exact: the gate may only skip work on messages that
 * provably cannot match, and every other message must come out byte-identical to the ungated
 * sequential-replace reference below.
 */
class EmojiPrefilterTest {
    private static final List<EmojiEntry> ENTRIES = List.of(
        new EmojiEntry("heart", "<3", "❤", true),
        new EmojiEntry("star", "", "✳", true),
        new EmojiEntry("shrug", "☹x", "🤷", true),
        new EmojiEntry("off", ":(", "X", false)
    );

    private static final List<String> CORPUS = List.of(
        "",
        "plain text with no markers",
        "time: now",
        "a:b:c",
        ":heart:",
        "I <3 :star:",
        "<3<3",
        ":off:",
        ":(",
        "☹x is a shrug",
        "☹ alone",
        "::",
        ":star::heart:",
        "trailing colon:",
        "<",
        "3",
        "mixed <3 :star: :off: ☹x tail",
        "&c:heart:&r",
        "%player% :heart:"
    );

    private final EmojiReplacer replacer = new EmojiReplacer(ENTRIES);

    @Test
    void everyCorpusMessageMatchesTheUngatedReference() {
        for (String message : CORPUS) {
            assertEquals(reference(message, null), replacer.apply(message), message);
        }
    }

    @Test
    void everyCorpusMessageMatchesTheUngatedReferenceUnderAPermissionPredicate() {
        Predicate<String> allowed = id -> !id.equals("star");
        for (String message : CORPUS) {
            assertEquals(reference(message, allowed), replacer.apply(message, allowed), message);
        }
    }

    @Test
    void aMessageWithNoColonAndNoTriggerCharIsReturnedUnchangedByIdentity() {
        String message = "nothing here can possibly match";

        assertSame(message, replacer.apply(message));
        assertSame(message, replacer.apply(message, id -> true));
    }

    @Test
    void aTriggerFirstCharAloneStillEntersTheReplaceLoop() {
        String message = "look: <";

        assertEquals(message, replacer.apply(message));
    }

    @Test
    void aReplacerWithoutTriggersGatesOnTheColonAlone() {
        EmojiReplacer tokensOnly = new EmojiReplacer(List.of(new EmojiEntry("star", "", "✳", true)));
        String noColon = "<3 is not a token here";

        assertSame(noColon, tokensOnly.apply(noColon));
        assertEquals("✳", tokensOnly.apply(":star:"));
    }

    @Test
    void aDisabledEntryTriggerDoesNotOpenTheGate() {
        EmojiReplacer heartOnly = new EmojiReplacer(List.of(
            new EmojiEntry("heart", "<3", "❤", true),
            new EmojiEntry("off", "~~", "X", false)
        ));
        String message = "~~ only";

        assertSame(message, heartOnly.apply(message));
    }

    @Test
    void anEmptyRegistryReturnsTheMessageUnchanged() {
        EmojiReplacer empty = new EmojiReplacer(List.of());
        String message = ":heart: <3";

        assertSame(message, empty.apply(message));
    }

    /** The replace loop exactly as it was before the prefilter landed. */
    private static String reference(String message, Predicate<String> idAllowed) {
        if (message == null || message.isEmpty()) {
            return message == null ? "" : message;
        }

        List<EmojiEntry> active = new ArrayList<>(ENTRIES.size());
        for (EmojiEntry entry : ENTRIES) {
            if (entry.enabled()) {
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
            if (idAllowed != null && !idAllowed.test(entry.id())) {
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
