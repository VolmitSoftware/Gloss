package art.arcane.gloss.chat;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ChatMentionsTest {
    private static final ChannelRuntime CHANNEL = ChatTestChannels.plain();

    @Test
    void onlyTheMentionedViewerSeesTheMentionRendered() {
        ChatBody.Body mentioned = ChatBody.render(CHANNEL, "hello @Steve", context("Steve"));
        ChatBody.Body bystander = ChatBody.render(CHANNEL, "hello @Steve", context("Alex"));

        assertTrue(mentioned.mentioned());
        assertFalse(bystander.mentioned());
        assertEquals("hello <reset><yellow>@Steve<reset>", mentioned.text());
        assertEquals("hello @Steve", bystander.text());
    }

    @Test
    void theMatchIsCaseInsensitiveButTheRenderedNameKeepsWhatWasTyped() {
        ChatBody.Body body = ChatBody.render(CHANNEL, "hi @steve", context("Steve"));

        assertTrue(body.mentioned());
        assertEquals("hi <reset><yellow>@steve<reset>", body.text());
    }

    @Test
    void aSenderWithoutTheMentionPermissionHighlightsNothing() {
        ChatBody.Body body = ChatBody.render(CHANNEL, "hello @Steve",
            new ChatBody.Context(null, "Steve", false, true, true, true, null));

        assertFalse(body.mentioned());
        assertEquals("hello @Steve", body.text());
    }

    @Test
    void aDisabledMentionsBlockHighlightsNothing() {
        ChannelRuntime channel = ChatTestChannels.runtime(
            new ChannelDoc.Mentions(false, null, null, null, null), null, null, List.of(), null);

        ChatBody.Body body = ChatBody.render(channel, "hello @Steve", context("Steve"));

        assertFalse(body.mentioned());
        assertEquals("hello @Steve", body.text());
    }

    @Test
    void playerTypedMarkupStaysLiteral() {
        ChatBody.Body body = ChatBody.render(CHANNEL, "<red>not a colour</red>", context("Steve"));

        assertEquals("\\<red>not a colour\\</red>", body.text());
    }

    @Test
    void anAuthoredMentionTemplateIsHonoured() {
        ChannelRuntime channel = ChatTestChannels.runtime(
            new ChannelDoc.Mentions(true, "%{name}", "&b>>{{ mention.name }}<<", null, null),
            null, null, List.of(), null);

        ChatBody.Body body = ChatBody.render(channel, "ping %Steve", context("Steve"));

        assertTrue(body.mentioned());
        assertEquals("ping <reset><aqua>>>Steve<<", body.text());
    }

    private static ChatBody.Context context(String viewerName) {
        return new ChatBody.Context(null, viewerName, true, true, true, true, null);
    }
}
