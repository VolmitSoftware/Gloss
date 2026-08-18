package art.arcane.gloss.chat;

import art.arcane.gloss.Gloss;
import org.bukkit.entity.Player;
import org.bukkit.event.HandlerList;
import org.bukkit.event.Listener;

import java.util.List;
import java.util.Objects;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.BiConsumer;

public final class ChatService {
    private static final String TAB_COMPLETE_EVENT = "com.destroystokyo.paper.event.server.AsyncTabCompleteEvent";
    private static final String TAB_COMPLETE_LISTENER = "art.arcane.gloss.paper.PaperTabCompleteListener";

    private final Gloss plugin;
    private final List<BiConsumer<Player, String>> hooks = new CopyOnWriteArrayList<>();

    private ChatListener listener;
    private Listener tabCompleteListener;

    public ChatService(Gloss plugin) {
        this.plugin = plugin;
    }

    public void enable() {
        listener = new ChatListener(plugin, hooks);
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
    }

    public void addChatHook(BiConsumer<Player, String> hook) {
        hooks.add(Objects.requireNonNull(hook));
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
            Gloss.warn("Emoji tab-complete listener failed to register: " + failure);
        }
    }
}
