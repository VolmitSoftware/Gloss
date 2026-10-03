package art.arcane.gloss.chat;

import art.arcane.gloss.Gloss;
import art.arcane.volmlib.util.scheduling.FoliaScheduler;
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
 * The Bukkit chat path, used on servers without Paper's per-viewer chat event. Channel messages
 * render for each recipient after the audience is narrowed to the channel's scope.
 */
@SuppressWarnings("deprecation")
final class ChatListener implements Listener {
    private final Gloss plugin;

    ChatListener(Gloss plugin) {
        this.plugin = plugin;
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
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
        SpigotChatSink sink = new SpigotChatSink(channels, event.getPlayer());
        if (channels.needsItemSnapshot(event.getPlayer(), event.getMessage())
            && !FoliaScheduler.isOwnedByCurrentRegion(event.getPlayer())) {
            sink.deferTo(event.getRecipients());
            event.setCancelled(true);
            channels.dispatchDeferred(event.getPlayer(), event.getMessage(), sink);
            return;
        }
        if (!channels.dispatch(event.getPlayer(), event.getMessage(), sink)) {
            event.setCancelled(true);
            return;
        }
        event.setMessage(sink.message);
        retainRecipients(event, sink.viewers);
        event.setCancelled(true);
        for (Player viewer : event.getRecipients()) {
            channels.deliver(sink.channel, event.getPlayer(), viewer, sink.message, sink.context);
        }
        channels.deliverConsole(sink.channel, event.getPlayer(), sink.message, sink.context);
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
        private final ChannelService channels;
        private final Player sender;
        private Set<UUID> allowed;
        private ChannelRuntime channel;
        private String message;
        private List<Player> viewers = List.of();
        private ChatContext context = ChatContext.PLAIN;

        private SpigotChatSink(ChannelService channels, Player sender) {
            this.channels = channels;
            this.sender = sender;
        }

        private void deferTo(Set<Player> recipients) {
            allowed = new HashSet<>(recipients.size());
            for (Player recipient : recipients) {
                allowed.add(recipient.getUniqueId());
            }
        }

        @Override
        public void dropped(ChatDrop reason) {
        }

        @Override
        public void audience(ChatDispatch dispatch) {
            this.channel = dispatch.channel();
            this.message = dispatch.message();
            this.viewers = dispatch.viewers();
            this.context = dispatch.context();
            if (allowed != null) {
                for (Player viewer : viewers) {
                    if (allowed.contains(viewer.getUniqueId())) {
                        channels.deliverDeferred(dispatch, sender, viewer);
                    }
                }
                channels.deliverConsole(channel, sender, message, context);
            }
        }
    }
}
