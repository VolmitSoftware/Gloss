package art.arcane.gloss.chat;

import art.arcane.gloss.emoji.EmojiEntry;
import art.arcane.gloss.emoji.EmojiReplacer;
import art.arcane.gloss.condition.ShowCondition;
import art.arcane.gloss.util.common.TextUtils;
import org.junit.jupiter.api.Test;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.event.ClickEvent;
import net.kyori.adventure.text.minimessage.MiniMessage;
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer;
import java.util.ArrayList;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ChatLinksItemsTest {
    private static final ChannelRuntime CHANNEL = ChatTestChannels.plain();
    private static final ChatBody.Item SWORD = new ChatBody.Item("minecraft:diamond_sword", "Cleaver", 1);

    @Test
    void emojisPreserveTrustedFormattingAndLiteralUserTagsAlongsideRichTokens() {
        EmojiReplacer emojis = new EmojiReplacer(List.of(
            new EmojiEntry("heart", "<3", "❤", true, null),
            new EmojiEntry("styled", null, "<aqua>star</aqua>", true, null),
            new EmojiEntry("gold", null, "&6gold", true, null)));
        ChatBody.Context context = new ChatBody.Context(null,
            raw -> emojis.apply(raw, null, ShowCondition::isAlwaysVisible, TextUtils::toMiniMessage), "Steve", "", true,
            true, true, true, SWORD);
        ChatBody.Body body = ChatBody.render(CHANNEL,
            "<red>literal</red> <3 :styled: @Steve [item] https://example.com/:heart:", context);
        assertTrue(body.text().startsWith("\\<red>literal\\</red> ❤ <aqua>star</aqua> "), body.text());
        assertTrue(body.mentioned());
        assertTrue(body.text().contains("<hover:show_item:'minecraft:diamond_sword':1>"), body.text());
        assertTrue(body.text().contains("https://example.com/:heart:"), body.text());
        assertEquals("a<3b ❤", ChatBody.render(CHANNEL, "a<3b <3", context).text());
        assertEquals("&cuser <reset><gold>gold", ChatBody.render(CHANNEL, "&cuser :gold:", context).text());
    }

    @Test
    void aLinkBecomesAClickableRenderedHost() {
        ChatBody.Body body = ChatBody.render(CHANNEL, "see https://volmit.com/docs now", context(null));

        Component rendered = MiniMessage.miniMessage().deserialize(body.text());
        assertEquals("see volmit.com now", PlainTextComponentSerializer.plainText().serialize(rendered));
        assertEquals(List.of("https://volmit.com/docs"), clickTargets(rendered));
    }

    @Test
    void aLinkIsPlainTextForAViewerWithNoClickSupport() {
        ChatBody.Body body = ChatBody.render(CHANNEL, "see https://volmit.com/docs now",
            new ChatBody.Context(null, null, "Steve", "", true, true, true, false, null));

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
            new ChatBody.Context(null, null, "Steve", "", true, true, false, true, SWORD)).text());
    }

    @Test
    void authoredItemFormatKeepsItemNamesLiteral() {
        ChannelRuntime channel = ChatTestChannels.runtime(null,
            new ChannelDoc.Items(true, null, null, "<gold>{{ item.amount }} of {{ item.name }}</gold>"),
            null, List.of(), null);
        ChatBody.Item item = new ChatBody.Item("minecraft:paper", "<red>Receipt</red>{{ item.amount }}", 3);
        String rendered = ChatBody.render(channel, "[item]", context(item)).text();

        assertTrue(rendered.contains("<gold>3 of \\<red>Receipt\\</red>{{ item.amount }}</gold>"), rendered);
    }

    @Test
    void aUrlInsideAMentionScanDoesNotProduceANestedMention() {
        ChatBody.Body body = ChatBody.render(CHANNEL, "https://volmit.com/@Steve", context(null));

        assertEquals(List.of("https://volmit.com/@Steve"),
            clickTargets(MiniMessage.miniMessage().deserialize(body.text())));
        assertEquals("volmit.com", PlainTextComponentSerializer.plainText()
            .serialize(MiniMessage.miniMessage().deserialize(body.text())));
    }

    @Test
    void legacyResetInsideLinkLabelDoesNotLeakClosingTagsOrClickScope() {
        ChannelRuntime channel = ChatTestChannels.runtime(null, null,
            new ChannelDoc.Links(true, "&9{{ link.host }}&r &aGuide"), List.of(), null);
        ChatBody.Body body = ChatBody.render(channel,
            "Inspect [item] and https://example.com/guide then <red>literal</red>", context(SWORD));
        Component rendered = MiniMessage.miniMessage().deserialize(body.text());
        assertEquals("Inspect [Cleaver] and example.com Guide then <red>literal</red>",
            PlainTextComponentSerializer.plainText().serialize(rendered));
        assertEquals(List.of("https://example.com/guide"), clickTargets(rendered));
        assertTrue(body.text().contains("<blue>"), body.text());
        assertTrue(body.text().contains("<green>"), body.text());
        assertTrue(rendered.children().getLast().clickEvent() == null);
    }

    @Test
    void quotedUrlRetainsExactClickTargetWithoutCreatingMarkup() {
        String url = "https://example.com/guide?q='garden'";
        ChatBody.Body body = ChatBody.render(CHANNEL, url + " done", context(null));
        Component rendered = MiniMessage.miniMessage().deserialize(body.text());
        assertEquals("example.com done", PlainTextComponentSerializer.plainText().serialize(rendered));
        assertEquals(List.of(url), clickTargets(rendered));
        assertTrue(rendered.children().getLast().clickEvent() == null);
    }

    private static List<String> clickTargets(Component component) {
        List<String> targets = new ArrayList<>();
        if (component.clickEvent() != null) {
            assertEquals(ClickEvent.Action.OPEN_URL, component.clickEvent().action());
            targets.add(component.clickEvent().value());
        }
        for (Component child : component.children()) {
            targets.addAll(clickTargets(child));
        }
        return targets;
    }

    private static ChatBody.Context context(ChatBody.Item item) {
        return new ChatBody.Context(null, null, "Steve", "", true, true, true, true, item);
    }
}
