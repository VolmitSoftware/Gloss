package art.arcane.gloss.chat;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.concurrent.atomic.AtomicLong;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

class ChatFiltersTest {
    @Test
    void aMatchIsReplacedAndTheRestOfTheMessageSurvives() {
        ChannelRuntime channel = ChatTestChannels.runtime(null, null, null,
            List.of(new ChannelDoc.Filter("(?i)\\bbadword\\b", "***")), null);

        assertEquals("say *** now", ChatFilters.apply(channel, "say BadWord now"));
    }

    @Test
    void everyFilterRunsInDeclarationOrder() {
        ChannelRuntime channel = ChatTestChannels.runtime(null, null, null,
            List.of(new ChannelDoc.Filter("cat", "dog"), new ChannelDoc.Filter("dog", "fish")), null);

        assertEquals("fish", ChatFilters.apply(channel, "cat"));
    }

    @Test
    void aMessageTheFiltersEmptyIsDropped() {
        ChannelRuntime channel = ChatTestChannels.runtime(null, null, null,
            List.of(new ChannelDoc.Filter("(?i)badword", "")), null);

        assertNull(ChatFilters.apply(channel, "badword"));
        assertNull(ChatFilters.apply(channel, "  badword  "));
    }

    @Test
    void aChannelWithNoFiltersReturnsTheMessageUnchanged() {
        assertEquals("hello", ChatFilters.apply(ChatTestChannels.plain(), "hello"));
    }

    @Test
    void filtersStopAtTheBudgetAndKeepWhatTheyAlreadyProduced() {
        ChannelRuntime channel = ChatTestChannels.runtime(null, null, null,
            List.of(new ChannelDoc.Filter("cat", "dog"), new ChannelDoc.Filter("hello", "goodbye")), null);
        AtomicLong clock = new AtomicLong();

        String output = ChatFilters.apply(channel, "cat says hello",
            () -> clock.getAndAdd(ChatFilters.BUDGET_NANOS));

        assertEquals("dog says hello", output);
    }
}
