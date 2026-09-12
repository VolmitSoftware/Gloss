package art.arcane.gloss.prompt;

import art.arcane.gloss.Gloss;
import art.arcane.gloss.chat.ChatCapture;
import art.arcane.gloss.dialog.InputNamespace;
import art.arcane.gloss.locale.GlossMessages;
import art.arcane.gloss.menu.SessionVariables;
import art.arcane.gloss.menu.action.MenuAction;
import art.arcane.gloss.service.GlossService;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.HandlerList;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerQuitEvent;

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;

/**
 * Routes a request for typed text to whichever editor the author asked for and delivers the answer
 * back into the surface that asked. One prompt per player at a time: two open editors would race
 * for the same session variable and the loser would silently win.
 */
public final class PromptService implements GlossService, Listener {
    public static final String NAME = "prompts";

    private static volatile PromptService active;

    private final Gloss plugin;
    private final SignPrompt sign;
    private final AnvilPrompt anvil;
    private final ChatPrompt chat;
    private final ConcurrentMap<UUID, PromptRequest> pending = new ConcurrentHashMap<>();

    public PromptService(Gloss plugin) {
        this.plugin = plugin;
        this.sign = new SignPrompt(plugin, this);
        this.anvil = new AnvilPrompt(plugin, this);
        this.chat = new ChatPrompt(this);
        active = this;
    }

    public static PromptService active() {
        return active;
    }

    @Override
    public String name() {
        return NAME;
    }

    @Override
    public void enable() {
        sign.enable();
        anvil.enable();
        if (plugin != null && plugin.getServer() != null) {
            plugin.getServer().getPluginManager().registerEvents(this, plugin);
        }
    }

    @Override
    public void disable() {
        sign.disable();
        anvil.disable();
        HandlerList.unregisterAll(this);
        pending.clear();
        if (active == this) {
            active = null;
        }
    }

    /** @return true when an editor was opened for the player */
    public boolean prompt(Player viewer, PromptRequest request) {
        if (viewer == null || request == null) {
            return false;
        }
        if (pending.putIfAbsent(viewer.getUniqueId(), request) != null) {
            send(viewer, GlossMessages.FORMS_PROMPT_BUSY);
            return false;
        }
        boolean opened = switch (request.kind()) {
            case PromptRequest.ANVIL -> anvil.open(viewer, request);
            case PromptRequest.CHAT -> chat.open(viewer, request);
            default -> sign.open(viewer, request);
        };
        if (!opened) {
            pending.remove(viewer.getUniqueId(), request);
        }
        return opened;
    }

    /** The request this player is answering, or null when they are not answering one. */
    public PromptRequest pending(UUID viewer) {
        return pending.get(viewer);
    }

    /** Delivers a completed answer: the session variable is written, then {@code then} runs. */
    public void complete(Player viewer, PromptRequest request, String value) {
        if (viewer == null || request == null || !pending.remove(viewer.getUniqueId(), request)) {
            return;
        }
        SessionVariables variables = request.origin() == null ? null : request.origin().sessionVariables();
        if (variables != null && !request.variable().isEmpty()) {
            variables.set(request.variable(), value);
        }
        if (request.then().isEmpty()) {
            return;
        }
        InputNamespace.bind(request.variable(), Map.of("value", value),
            () -> MenuAction.execute(request.then(), request.origin()));
    }

    /** Drops a request the player never answered and tells them why nothing happened. */
    public void timeout(Player viewer, PromptRequest request) {
        if (viewer == null || request == null || !pending.remove(viewer.getUniqueId(), request)) {
            return;
        }
        release(viewer.getUniqueId(), request);
        send(viewer, GlossMessages.FORMS_PROMPT_TIMEOUT);
    }

    /** Drops a request without telling anyone: the player is gone or the editor was taken away. */
    public void cancel(UUID viewer) {
        PromptRequest request = pending.remove(viewer);
        if (request != null) {
            release(viewer, request);
        }
    }

    /**
     * A quit is the one end no editor reports. Without this the entry outlives the session and the
     * player comes back permanently refused, because {@code pending} is keyed by UUID.
     */
    @EventHandler(priority = EventPriority.MONITOR)
    public void onQuit(PlayerQuitEvent event) {
        cancel(event.getPlayer().getUniqueId());
    }

    /** Lets go of whatever the editor holds outside {@code pending}. */
    private void release(UUID viewer, PromptRequest request) {
        if (PromptRequest.CHAT.equals(request.kind())) {
            ChatCapture.release(viewer);
        }
    }

    Gloss plugin() {
        return plugin;
    }

    private void send(Player viewer, art.arcane.volmlib.util.localization.TextKey key) {
        if (plugin != null && plugin.getLocalization() != null) {
            plugin.getLocalization().send(viewer, key);
        }
    }
}
