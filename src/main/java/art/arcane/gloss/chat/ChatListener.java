package art.arcane.gloss.chat;

import art.arcane.gloss.Gloss;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.player.AsyncPlayerChatEvent;

import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;

/**
 * The Bukkit chat path, used on servers without Paper's per-viewer chat event. One format reaches
 * the whole audience, so viewer-dependent tokens resolve as the sender; the recipient set is still
 * narrowed to the channel's scope.
 */
@SuppressWarnings("deprecation")
final class ChatListener implements Listener {
    private final Gloss plugin;

    ChatListener(Gloss plugin) {
        this.plugin = plugin;
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onFormat(AsyncPlayerChatEvent event) {
        if (ChatCapture.consume(event.getPlayer().getUniqueId(), event.getMessage())) {
            event.setCancelled(true);
            return;
        }
        ChannelService channels = plugin.service(ChannelService.class);
        if (channels == null || !channels.active()) {
            event.setMessage(plugin.text().chat(event.getPlayer(), event.getMessage()));
            return;
        }
        SpigotChatSink sink = new SpigotChatSink();
        if (!channels.dispatch(event.getPlayer(), event.getMessage(), sink)) {
            event.setCancelled(true);
            return;
        }
        event.setMessage(sink.message);
        event.setFormat(channels.spigotFormat(sink.channel, event.getPlayer(), sink.message));
        retainRecipients(event, sink.viewers);
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onMonitor(AsyncPlayerChatEvent event) {
        ChannelService channels = plugin.service(ChannelService.class);
        if (channels != null && channels.active()) {
            return;
        }
        plugin.chat().dispatchHooks(event.getPlayer(), event.getMessage());
    }

    private static void retainRecipients(AsyncPlayerChatEvent event, List<Player> viewers) {
        Set<UUID> allowed = new HashSet<>(viewers.size());
        for (Player viewer : viewers) {
            allowed.add(viewer.getUniqueId());
        }
        event.getRecipients().removeIf(recipient -> !allowed.contains(recipient.getUniqueId()));
    }

    private static final class SpigotChatSink implements ChatSink {
        private ChannelRuntime channel;
        private String message;
        private List<Player> viewers = List.of();

        @Override
        public void dropped(ChatDrop reason) {
        }

        @Override
        public void audience(ChannelRuntime channel, String message, List<Player> viewers) {
            this.channel = channel;
            this.message = message;
            this.viewers = viewers;
        }
    }
}
