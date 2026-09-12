package art.arcane.gloss.prompt;

import art.arcane.gloss.chat.ChatCapture;
import art.arcane.volmlib.util.scheduling.FoliaScheduler;
import org.bukkit.entity.Player;

/**
 * The simplest prompt: the next thing the player types. The claim lives in {@link ChatCapture} so
 * the chat listeners can answer it before anything else formats or broadcasts the message.
 */
public final class ChatPrompt {
    private static final long TICK_MS = 50L;

    private final PromptService service;

    ChatPrompt(PromptService service) {
        this.service = service;
    }

    boolean open(Player viewer, PromptRequest request) {
        if (!ChatCapture.claim(viewer.getUniqueId(),
            message -> service.complete(viewer, request, message),
            request.timeoutTicks() * TICK_MS)) {
            return false;
        }
        FoliaScheduler.runEntity(service.plugin(), viewer, () -> service.timeout(viewer, request),
            request.timeoutTicks());
        return true;
    }
}
