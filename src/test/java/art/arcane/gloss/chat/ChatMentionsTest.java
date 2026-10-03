package art.arcane.gloss.chat;

import org.junit.jupiter.api.Test;
import art.arcane.gloss.util.common.TextUtils;

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
        assertEquals("hello <gold><bold>@Steve</bold></gold>", mentioned.text());
        assertEquals("hello @Steve", bystander.text());
    }

    @Test
    void theMatchIsCaseInsensitiveButTheRenderedNameKeepsWhatWasTyped() {
        ChatBody.Body body = ChatBody.render(CHANNEL, "hi @steve", context("Steve"));

        assertTrue(body.mentioned());
        assertEquals("hi <gold><bold>@steve</bold></gold>", body.text());
    }

    @Test
    void aSenderWithoutTheMentionPermissionHighlightsNothing() {
        ChatBody.Body body = ChatBody.render(CHANNEL, "hello @Steve",
            new ChatBody.Context(null, null, "Steve", "", false, true, true, true, null));

        assertFalse(body.mentioned());
        assertEquals("hello @Steve", body.text());
    }

    @Test
    void aDisabledMentionsBlockHighlightsNothing() {
        ChannelRuntime channel = ChatTestChannels.runtime(
            new ChannelDoc.Mentions(false, null, null, null, null, null), null, null, List.of(), null);

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
            new ChannelDoc.Mentions(true, "%{name}", "&b>>{{ mention.name }}<<", null, null, null),
            null, null, List.of(), null);

        ChatBody.Body body = ChatBody.render(channel, "ping %Steve", context("Steve"));

        assertTrue(body.mentioned());
        assertEquals("ping <reset><aqua>>>Steve<<", body.text());
    }

    @Test
    void usernamesMustBeWholeTokens() {
        for (String message : List.of("name@Steve", "@@Steve", "@SteveExtra", "mail@Steve.example",
            "@abcdefghijklmnopq")) {
            String viewer = message.endsWith("q") ? "abcdefghijklmnop" : "Steve";
            assertFalse(ChatBody.render(CHANNEL, message, context(viewer)).mentioned(), message);
        }
        assertTrue(ChatBody.render(CHANNEL, "(@Steve), hello!", context("Steve")).mentioned());
    }

    @Test
    void aMentionDoesNotLeakItsStyleIntoTheRemainingMessage() {
        ChatBody.Body body = ChatBody.render(CHANNEL, "@Steve hello", context("Steve"));
        assertTrue(TextUtils.renderLegacy("<yellow>" + body.text() + "</yellow>")
            .endsWith("§e hello"));
    }

    @Test
    void decoratedPlayerNamesPreserveTheTaggedMessageColor() {
        ChatBody.Context context = new ChatBody.Context(null, null, "Steve", "§r§c[Staff] §fSteve§r",
            true, true, true, true, null);
        ChatBody.Body body = ChatBody.render(CHANNEL, "hello @Steve there", context);
        String sender = ChatComponents.scopedMarkup("§r§b[Member] Alex§r");
        String rendered = TextUtils.renderLegacy("<yellow>" + sender + ": " + body.text() + "</yellow>");

        assertTrue(body.mentioned());
        assertTrue(rendered.contains("§e: hello "), rendered);
        assertTrue(rendered.endsWith("§e there"), rendered);
    }

    private static ChatBody.Context context(String viewerName) {
        return new ChatBody.Context(null, null, viewerName, "", true, true, true, true, null);
    }
}
