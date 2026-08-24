package art.arcane.gloss.chat;

import art.arcane.gloss.Gloss;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.player.AsyncPlayerChatEvent;

import java.util.List;
import java.util.function.BiConsumer;

@SuppressWarnings("deprecation")
final class ChatListener implements Listener {
    private final Gloss plugin;
    private final List<BiConsumer<Player, String>> hooks;

    ChatListener(Gloss plugin, List<BiConsumer<Player, String>> hooks) {
        this.plugin = plugin;
        this.hooks = hooks;
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onFormat(AsyncPlayerChatEvent event) {
        event.setMessage(plugin.text().chat(event.getPlayer(), event.getMessage()));
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onMonitor(AsyncPlayerChatEvent event) {
        Player sender = event.getPlayer();
        String message = event.getMessage();
        for (BiConsumer<Player, String> hook : hooks) {
            try {
                hook.accept(sender, message);
            } catch (Throwable failure) {
                Gloss.logExceptionStackThrottled(false, "chat-hook", failure,
                    "Chat hook %s failed for %s.", hook.getClass().getName(), sender.getName());
            }
        }
    }
}
