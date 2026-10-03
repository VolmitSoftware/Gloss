package art.arcane.gloss.chat;

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Per-player chat state: the channel they are talking in, who they last exchanged a private
 * message with.
 */
public final class ChatState {
    private final Map<UUID, String> channels = new ConcurrentHashMap<>();
    private final Map<UUID, UUID> partners = new ConcurrentHashMap<>();

    public String channelOf(UUID playerId) {
        return channels.get(playerId);
    }

    public void setChannel(UUID playerId, String channelId) {
        channels.put(playerId, channelId);
    }

    public UUID partnerOf(UUID playerId) {
        return partners.get(playerId);
    }

    /** Records both directions so either side may answer with {@code /r}. */
    public void pairConversation(UUID sender, UUID target) {
        partners.put(sender, target);
        partners.put(target, sender);
    }

    public void forget(UUID playerId) {
        channels.remove(playerId);
        partners.remove(playerId);
    }

    public void clear() {
        channels.clear();
        partners.clear();
    }
}
