package art.arcane.gloss.prompt;

import art.arcane.gloss.Gloss;
import art.arcane.gloss.chat.ChatCapture;
import art.arcane.gloss.menu.CharacterizationSupport;
import org.bukkit.Server;
import org.bukkit.entity.Player;
import org.bukkit.event.player.PlayerQuitEvent;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * One prompt per player is the rule; these pin the ways a prompt ends without an answer, because
 * every one of them that leaks the pending entry locks the player out of every later prompt and
 * takes the {@code field} component with it.
 */
class PromptLifecycleTest {
    private static final UUID PLAYER_ID = UUID.randomUUID();

    private Object previousServer;
    private Gloss previousGloss;
    private Gloss gloss;
    private PromptService service;

    @BeforeEach
    void installHeadlessServer() throws ReflectiveOperationException {
        Server server = CharacterizationSupport.server(Map.of());
        previousServer = CharacterizationSupport.installServer(server);
        gloss = CharacterizationSupport.bareGloss(server);
        previousGloss = CharacterizationSupport.installGloss(gloss);
        ChatCapture.clear();
        service = new PromptService(gloss);
    }

    @AfterEach
    void restore() throws ReflectiveOperationException {
        ChatCapture.clear();
        CharacterizationSupport.restoreGloss(previousGloss);
        CharacterizationSupport.restoreServer(previousServer);
    }

    @Test
    void aQuitDropsTheUnansweredPromptAndItsChatClaim() {
        Player viewer = player();
        assertTrue(service.prompt(viewer, request(PromptRequest.CHAT)));
        assertFalse(service.prompt(viewer, request(PromptRequest.CHAT)));

        service.onQuit(new PlayerQuitEvent(viewer, (String) null));

        assertNull(service.pending(PLAYER_ID));
        assertTrue(service.prompt(viewer, request(PromptRequest.CHAT)));
    }

    @Test
    void anEditorThatEndedWithoutAnAnswerLetsTheNextPromptOpen() {
        Player viewer = player();
        PromptRequest first = request(PromptRequest.CHAT);
        assertTrue(service.prompt(viewer, first));

        service.timeout(viewer, first);

        assertNull(service.pending(PLAYER_ID));
        assertTrue(service.prompt(viewer, request(PromptRequest.CHAT)));
    }

    @Test
    void anAnvilPromptIsRecognisedByTheKindADocumentSpelled() {
        assertTrue(AnvilPrompt.owns(request(new String(PromptRequest.ANVIL.toCharArray()))));
        assertFalse(AnvilPrompt.owns(request(PromptRequest.SIGN)));
        assertFalse(AnvilPrompt.owns(null));
    }

    private static PromptRequest request(String kind) {
        return new PromptRequest(kind, "name", "Label", "", List.of(), 20, null);
    }

    private static Player player() {
        return (Player) Proxy.newProxyInstance(PromptLifecycleTest.class.getClassLoader(),
            new Class<?>[]{Player.class}, PromptLifecycleTest::answer);
    }

    private static Object answer(Object proxy, Method method, Object[] args) {
        return switch (method.getName()) {
            case "getUniqueId" -> PLAYER_ID;
            case "getName" -> "Tester";
            case "isOnline" -> true;
            case "openInventory" -> null;
            case "hashCode" -> System.identityHashCode(proxy);
            case "equals" -> proxy == args[0];
            case "toString" -> "Player[Tester]";
            default -> throw new UnsupportedOperationException("fake player was asked for " + method.getName());
        };
    }
}
