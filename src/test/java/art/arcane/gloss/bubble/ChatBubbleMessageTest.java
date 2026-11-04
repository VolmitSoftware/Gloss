package art.arcane.gloss.bubble;

import art.arcane.gloss.Gloss;
import art.arcane.gloss.menu.CharacterizationSupport;
import art.arcane.gloss.text.TextPipeline;
import org.bukkit.entity.Player;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.File;
import java.lang.reflect.Proxy;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;

class ChatBubbleMessageTest {
    @TempDir
    File dataFolder;

    @Test
    void aRawChannelLineGetsTheSendersEmoji() throws ReflectiveOperationException {
        ChatBubblesService bubbles = bubbles();

        assertEquals("❇ hello", bubbles.bubbleMessage(player(true), ":sparkle: hello"));
    }

    @Test
    void aLineTheChatPathAlreadyRenderedStaysTheSame() throws ReflectiveOperationException {
        ChatBubblesService bubbles = bubbles();

        assertEquals("❇ hello", bubbles.bubbleMessage(player(true), "❇ hello"));
    }

    @Test
    void aSenderWithoutEmojiPermissionKeepsTheToken() throws ReflectiveOperationException {
        ChatBubblesService bubbles = bubbles();

        assertEquals(":sparkle: hello", bubbles.bubbleMessage(player(false), ":sparkle: hello"));
    }

    private ChatBubblesService bubbles() throws ReflectiveOperationException {
        Gloss gloss = CharacterizationSupport.bareGloss(CharacterizationSupport.server(Map.of()));
        CharacterizationSupport.setField(gloss, "dataFolder", dataFolder);
        TextPipeline text = new TextPipeline(gloss);
        text.setViewerEmojiFilter((sender, message) -> message.replace(":sparkle:", "❇"));
        CharacterizationSupport.setField(gloss, "text", text);
        return new ChatBubblesService(gloss);
    }

    private static Player player(boolean allowed) {
        return (Player) Proxy.newProxyInstance(ChatBubbleMessageTest.class.getClassLoader(),
            new Class<?>[]{Player.class}, (proxy, method, args) -> switch (method.getName()) {
                case "hasPermission" -> allowed;
                case "hashCode" -> System.identityHashCode(proxy);
                case "equals" -> proxy == args[0];
                case "toString" -> "Player";
                default -> throw new UnsupportedOperationException(method.getName());
            });
    }
}
