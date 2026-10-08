package art.arcane.gloss.chat;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import java.util.regex.Pattern;
import java.util.regex.Matcher;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.concurrent.atomic.AtomicLong;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

class ChatFiltersTest {
    @Test
    void commonHistoricalPatternsAndLiteralReplacementsKeepTheirResults() {
        for (String pattern : List.of("bad", "(?i)\\bbadword\\b", "[0-9]+", "cat|dog", "a{1,3}", "^hello$")) {
            for (String message : List.of("say BadWord now", "cat dog 123", "aaaa", "hello", "bad", "café badword")) {
                ChannelRuntime channel = channel("{}", new ChannelDoc.Filter(pattern, "$1\\tail"));
                String expected = Pattern.compile(pattern).matcher(message)
                    .replaceAll(Matcher.quoteReplacement("$1\\tail"));
                assertEquals(expected, ChatFilters.apply(channel, message, () -> 0L), pattern + " : " + message);
            }
        }
    }

    @Test
    void everyFilterRunsInDeclarationOrderAndEmptyResultsAreDropped() {
        assertEquals("fish", ChatFilters.apply(channel("{}", new ChannelDoc.Filter("cat", "dog"),
            new ChannelDoc.Filter("dog", "fish")), "cat", () -> 0L));
        assertNull(ChatFilters.apply(channel("{}", new ChannelDoc.Filter("bad", "")), "  bad  ", () -> 0L));
        assertEquals("hello", ChatFilters.apply(ChatTestChannels.plain(), "hello"));
    }

    @Test
    void elapsedBudgetDefaultsToDroppingAndCanKeepCompletedFilters() {
        for (String policy : List.of("drop", "keep-completed")) {
            ChannelRuntime channel = channel("{\"budgetMicros\":1,\"onLimit\":\"" + policy + "\"}",
                new ChannelDoc.Filter("cat", "dog"), new ChannelDoc.Filter("hello", "goodbye"));
            AtomicLong clock = new AtomicLong();
            assertEquals(policy.equals("drop") ? null : "dog says hello",
                ChatFilters.apply(channel, "cat says hello", () -> clock.getAndAdd(1000L)));
        }
    }

    @Test
    void aCompletedChainIsKeptEvenIfItsElapsedBudgetWasMissed() {
        AtomicLong clock = new AtomicLong();
        assertEquals("dog", ChatFilters.apply(channel("{\"budgetMicros\":1}", new ChannelDoc.Filter("cat", "dog")),
            "cat", () -> clock.getAndAdd(1000L)));
    }

    @Test
    void matchAndOutputLimitsNeverKeepAPartialFilter() {
        for (String limits : List.of("\"maxMatches\":1", "\"maxOutputCharacters\":4", "\"maxWorkUnits\":1")) {
            ChannelRuntime channel = channel("{" + limits + ",\"onLimit\":\"keep-completed\"}",
                new ChannelDoc.Filter("a", "long"));
            assertEquals("aa", ChatFilters.apply(channel, "aa", () -> 0L));
            assertNull(ChatFilters.apply(channel("{" + limits + "}", new ChannelDoc.Filter("a", "long")), "aa", () -> 0L));
        }
    }

    @Test
    void oversizedInputIsAlwaysDroppedAndNoFilterChannelsAreUnchanged() {
        assertNull(ChatFilters.apply(channel("{\"maxInputCharacters\":1,\"onLimit\":\"keep-completed\"}",
            new ChannelDoc.Filter("a", "b")), "aa", () -> 0L));
        assertEquals("a".repeat(40000), ChatFilters.apply(ChatTestChannels.plain(), "a".repeat(40000)));
    }

    @Test
    void emptyMatchesAreFiniteAndLiteralReplacementDoesNotExpandGroups() {
        assertEquals("$1a$1b$1", ChatFilters.apply(channel("{}", new ChannelDoc.Filter("(?:)", "$1")), "ab", () -> 0L));
    }

    @Test
    void rejectsUnsupportedSyntaxAndDangerousCompileExpansion() {
        for (String pattern : List.of("(?=a)a", "(a)\\1", "(?>a)", "a++", "(?x)a", "((a{1000}){1000}){1000}")) {
            assertThrows(IllegalArgumentException.class, () -> channel("{}", new ChannelDoc.Filter(pattern, "")), pattern);
        }
        assertThrows(IllegalArgumentException.class, () -> channel("{\"maxNestingDepth\":2}", new ChannelDoc.Filter("(((a)))", "")));
        assertThrows(IllegalArgumentException.class, () -> channel("{\"maxFilters\":0}", new ChannelDoc.Filter("a", "")));
        assertThrows(IllegalArgumentException.class, () -> channel("{\"maxPatternCharacters\":1}", new ChannelDoc.Filter("ab", "")));
        assertThrows(IllegalArgumentException.class, () -> channel("{\"maxReplacementCharacters\":0}", new ChannelDoc.Filter("a", "b")));
    }

    @Test
    void nestedQuantifiersHaveLinearMatchingAndEscapedSyntaxIsAccepted() {
        assertEquals("a".repeat(1000) + "!", ChatFilters.apply(channel("{}", new ChannelDoc.Filter("(a+)+$", "")),
            "a".repeat(1000) + "!", () -> 0L));
        assertEquals("ok", ChatFilters.apply(channel("{}", new ChannelDoc.Filter("\\Q({x})\\E", "ok")), "({x})", () -> 0L));
    }

    private static ChannelRuntime channel(String limits, ChannelDoc.Filter... filters) {
        JsonObject document = new JsonObject();
        document.addProperty("schemaVersion", 2);
        document.addProperty("revision", 1);
        JsonObject channel = new JsonObject();
        channel.addProperty("name", "global");
        document.add("channel", channel);
        document.addProperty("format", "{{ message }}");
        document.add("filtering", JsonParser.parseString(limits));
        JsonArray array = new JsonArray();
        for (ChannelDoc.Filter filter : filters) {
            JsonObject entry = new JsonObject();
            entry.addProperty("match", filter.match());
            entry.addProperty("replace", filter.replace());
            array.add(entry);
        }
        document.add("filters", array);
        return ChannelRuntime.of("global", ChannelDoc.parse("global.json", document.toString()));
    }
}
