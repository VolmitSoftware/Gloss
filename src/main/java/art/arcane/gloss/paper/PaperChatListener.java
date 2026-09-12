package art.arcane.gloss.paper;

import art.arcane.gloss.Gloss;
import art.arcane.gloss.chat.ChannelRuntime;
import art.arcane.gloss.chat.ChannelService;
import art.arcane.gloss.chat.ChatCapture;
import art.arcane.gloss.chat.ChatComponents;
import art.arcane.gloss.chat.ChatContext;
import art.arcane.gloss.chat.ChatDrop;
import art.arcane.gloss.chat.ChatMessageRenderer;
import art.arcane.gloss.chat.ChatSink;
import io.papermc.paper.event.player.AsyncChatEvent;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;

import java.lang.reflect.Method;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * Paper's per-viewer chat path. Every call whose signature carries an Adventure type goes through
 * a {@link Method} handle, because the shaded jar relocates the plugin's own copy of Adventure and
 * a direct call would link against a descriptor no server member matches.
 */
public final class PaperChatListener implements Listener {
    private static final Method MESSAGE = method("message");
    private static final Method SET_RENDERER = rendererSetter();

    private final Gloss plugin;

    public PaperChatListener(Gloss plugin) {
        this.plugin = plugin;
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onChat(AsyncChatEvent event) {
        Player sender = event.getPlayer();
        String plain = plainText(event);
        if (plain == null) {
            return;
        }
        if (ChatCapture.consume(sender.getUniqueId(), plain)) {
            event.setCancelled(true);
            return;
        }
        ChannelService channels = plugin.service(ChannelService.class);
        if (channels == null || !channels.active()) {
            return;
        }
        PaperChatSink sink = new PaperChatSink(channels, sender);
        if (!channels.dispatch(sender, plain, sink)) {
            event.setCancelled(sink.reason != ChatDrop.NO_CHANNEL);
            return;
        }
        retainAudience(event, sink.viewers);
        install(event, sink);
    }

    /**
     * Iterated by hand rather than with {@code removeIf}: a lambda over the viewer set would put an
     * Adventure type in a synthetic method descriptor, which the shaded jar's relocation rewrites
     * into a signature no server member matches.
     */
    private void retainAudience(AsyncChatEvent event, List<Player> viewers) {
        Set<UUID> allowed = new HashSet<>(viewers.size());
        for (Player viewer : viewers) {
            allowed.add(viewer.getUniqueId());
        }
        Set<?> audiences = event.viewers();
        Iterator<?> iterator = audiences.iterator();
        while (iterator.hasNext()) {
            Object audience = iterator.next();
            if (audience instanceof Player player && !allowed.contains(player.getUniqueId())) {
                iterator.remove();
            }
        }
    }

    private void install(AsyncChatEvent event, PaperChatSink sink) {
        try {
            SET_RENDERER.invoke(event, PaperChatRendererProxy.create(sink::renderFor));
        } catch (ReflectiveOperationException | RuntimeException failure) {
            Gloss.logExceptionStackThrottled(false, "paper-chat-renderer", failure,
                "Chat renderer could not be installed; the vanilla format was used.");
        }
    }

    /**
     * The server's component is handed over as JSON, the one shape both copies of Adventure agree
     * on, and the plugin's own serializer turns it back into readable text.
     */
    private String plainText(AsyncChatEvent event) {
        try {
            return ChatComponents.plainText(ServerAdventure.toJson(MESSAGE.invoke(event)));
        } catch (ReflectiveOperationException | RuntimeException failure) {
            Gloss.logExceptionStackThrottled(false, "paper-chat-message", failure,
                "Chat message could not be read; the channel engine skipped it.");
            return null;
        }
    }

    private static Method method(String name) {
        try {
            return AsyncChatEvent.class.getMethod(name);
        } catch (NoSuchMethodException missing) {
            throw new IllegalStateException("AsyncChatEvent has no " + name + "()", missing);
        }
    }

    private static Method rendererSetter() {
        try {
            return AsyncChatEvent.class.getMethod("renderer", PaperChatRendererProxy.rendererType());
        } catch (ReflectiveOperationException missing) {
            throw new IllegalStateException("AsyncChatEvent has no renderer setter", missing);
        }
    }

    /** Holds the dispatch outcome and renders lazily, once per viewer the server actually sends to. */
    private final class PaperChatSink implements ChatSink {
        private final ChannelService channels;
        private final Player sender;
        private final Map<UUID, String> rendered = new HashMap<>();
        private ChannelRuntime channel;
        private String message;
        private List<Player> viewers = List.of();
        private ChatDrop reason;

        private PaperChatSink(ChannelService channels, Player sender) {
            this.channels = channels;
            this.sender = sender;
        }

        @Override
        public void dropped(ChatDrop reason) {
            this.reason = reason;
        }

        @Override
        public void audience(ChannelRuntime channel, String message, List<Player> viewers) {
            this.channel = channel;
            this.message = message;
            this.viewers = viewers;
        }

        private String renderFor(Object audience) {
            if (!(audience instanceof Player viewer)) {
                return sharedRender();
            }
            return rendered.computeIfAbsent(viewer.getUniqueId(), id -> renderViewer(viewer));
        }

        private String renderViewer(Player viewer) {
            ChatMessageRenderer.Rendered result = channels.render(channel, sender, viewer, message,
                new ChatContext(ServerAdventure::escape, null));
            if (result.mentioned()) {
                channels.playMentionCue(channel, viewer);
            }
            return result.miniMessage();
        }

        /** A console or plugin audience reads the sender's own render. */
        private String sharedRender() {
            return channels.render(channel, sender, sender, message,
                new ChatContext(ServerAdventure::escape, null)).miniMessage();
        }
    }
}
