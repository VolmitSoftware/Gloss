package art.arcane.gloss.chat;

import art.arcane.gloss.Gloss;
import art.arcane.gloss.service.PaperBridges;
import org.bukkit.entity.Player;
import org.bukkit.event.HandlerList;
import org.bukkit.event.Listener;

import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.BiConsumer;

/**
 * Chat listener ownership. Exactly one path is registered: Paper's per-viewer
 * {@code AsyncChatEvent} bridge when the channel engine is on and the server has it, otherwise the
 * Bukkit listener. Both drive {@link ChannelService#dispatch} and both fan out to the hooks that
 * chat bubbles and other consumers install here.
 */
public final class ChatService {
    private static final String CHAT_EVENT = "io.papermc.paper.event.player.AsyncChatEvent";
    private static final String CHAT_LISTENER = "art.arcane.gloss.paper.PaperChatListener";
    private static final String TAB_COMPLETE_EVENT = "com.destroystokyo.paper.event.server.AsyncTabCompleteEvent";
    private static final String TAB_COMPLETE_LISTENER = "art.arcane.gloss.paper.PaperTabCompleteListener";

    private final Gloss plugin;
    private final List<BiConsumer<Player, String>> hooks = new CopyOnWriteArrayList<>();

    private Listener listener;
    private Listener tabCompleteListener;

    public ChatService(Gloss plugin) {
        this.plugin = plugin;
    }

    public void enable() {
        listener = chatListener();
        plugin.getServer().getPluginManager().registerEvents(listener, plugin);
        registerTabComplete();
    }

    public void disable() {
        if (listener != null) {
            HandlerList.unregisterAll(listener);
            listener = null;
        }
        if (tabCompleteListener != null) {
            HandlerList.unregisterAll(tabCompleteListener);
            tabCompleteListener = null;
        }
        hooks.clear();
        ChatCapture.clear();
    }

    public void addChatHook(BiConsumer<Player, String> hook) {
        hooks.add(Objects.requireNonNull(hook));
    }

    /** One call per delivered message, from whichever listener path is registered. */
    public void dispatchHooks(Player sender, String message) {
        for (BiConsumer<Player, String> hook : hooks) {
            try {
                hook.accept(sender, message);
            } catch (Throwable failure) {
                Gloss.logExceptionStackThrottled(false, "chat-hook", failure,
                    "Chat hook %s failed for %s.", hook.getClass().getName(), sender.getName());
            }
        }
    }

    private Listener chatListener() {
        if (!plugin.cfg().modules().channels().enabled()) {
            return new ChatListener(plugin);
        }
        Optional<Listener> paper = PaperBridges.load(CHAT_EVENT, CHAT_LISTENER, Listener.class, plugin);
        return paper.orElseGet(() -> new ChatListener(plugin));
    }

    private void registerTabComplete() {
        if (!plugin.cfg().emoji().enabled() || !plugin.cfg().emoji().tabComplete()) {
            return;
        }

        try {
            Class.forName(TAB_COMPLETE_EVENT);
        } catch (ClassNotFoundException absent) {
            return;
        }

        try {
            Class<?> type = Class.forName(TAB_COMPLETE_LISTENER);
            Listener instance = (Listener) type.getConstructor(Gloss.class).newInstance(plugin);
            plugin.getServer().getPluginManager().registerEvents(instance, plugin);
            tabCompleteListener = instance;
        } catch (ReflectiveOperationException | ClassCastException failure) {
            Gloss.logExceptionStack(false, failure, "Emoji tab-complete listener failed to register.");
        }
    }
}
