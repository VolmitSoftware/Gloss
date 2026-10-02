package art.arcane.gloss.chat;

import org.bukkit.entity.Player;

import java.util.function.UnaryOperator;

/**
 * What a render needs that the channel document does not carry: the escaper belonging to whichever
 * MiniMessage parser will read the result, and the sender's held item behind the {@code [item]}
 * token, captured on a region thread before the async render begins.
 */
public record ChatContext(UnaryOperator<String> escape, ChatBody.Item item, Player recipient) {
    public static final ChatContext PLAIN = new ChatContext(null, null, null);

    public ChatContext withItem(ChatBody.Item item) {
        return new ChatContext(escape, item, recipient);
    }
}
