package art.arcane.gloss.chat;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ChatLinksItemsTest {
    private static final ChannelRuntime CHANNEL = ChatTestChannels.plain();
    private static final ChatBody.Item SWORD = new ChatBody.Item("minecraft:diamond_sword", "Cleaver", 1);

    @Test
    void aLinkBecomesAClickableRenderedHost() {
        ChatBody.Body body = ChatBody.render(CHANNEL, "see https://volmit.com/docs now", context(null));

        assertEquals("see <click:open_url:'https://volmit.com/docs'>"
            + "<reset><blue><underlined>volmit.com</click> now", body.text());
    }

    @Test
    void aLinkIsPlainTextForAViewerWithNoClickSupport() {
        ChatBody.Body body = ChatBody.render(CHANNEL, "see https://volmit.com/docs now",
            new ChatBody.Context(null, "Steve", true, true, true, false, null));

        assertEquals("see <reset><blue><underlined>volmit.com now", body.text());
    }

    @Test
    void aDisabledLinksBlockLeavesTheUrlAlone() {
        ChannelRuntime channel = ChatTestChannels.runtime(null, null,
            new ChannelDoc.Links(false, null), List.of(), null);

        ChatBody.Body body = ChatBody.render(channel, "see https://volmit.com/docs now", context(null));

        assertEquals("see https://volmit.com/docs now", body.text());
    }

    @Test
    void theItemTokenBecomesAHoverCardOfTheHeldItem() {
        ChatBody.Body body = ChatBody.render(CHANNEL, "look at [item]!", context(SWORD));

        assertTrue(body.text().startsWith("look at <hover:show_item:'minecraft:diamond_sword':1>"),
            body.text());
        assertTrue(body.text().endsWith("</hover>!"), body.text());
        assertTrue(body.text().contains("Cleaver"), body.text());
    }

    @Test
    void theItemTokenStaysLiteralWithoutAHeldItemOrPermission() {
        assertEquals("look at [item]!", ChatBody.render(CHANNEL, "look at [item]!", context(null)).text());
        assertEquals("look at [item]!", ChatBody.render(CHANNEL, "look at [item]!",
            new ChatBody.Context(null, "Steve", true, true, false, true, SWORD)).text());
    }

    @Test
    void aUrlInsideAMentionScanDoesNotProduceANestedMention() {
        ChatBody.Body body = ChatBody.render(CHANNEL, "https://volmit.com/@Steve", context(null));

        assertTrue(body.text().startsWith("<click:open_url:"), body.text());
        assertTrue(body.text().endsWith("</click>"), body.text());
    }

    private static ChatBody.Context context(ChatBody.Item item) {
        return new ChatBody.Context(null, "Steve", true, true, true, true, item);
    }
}
