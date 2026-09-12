package art.arcane.gloss.paper;

import art.arcane.gloss.Gloss;
import art.arcane.gloss.chat.ChatCapture;
import art.arcane.gloss.menu.CharacterizationSupport;
import io.papermc.paper.event.player.AsyncChatEvent;
import net.kyori.adventure.text.Component;
import org.bukkit.Server;
import org.bukkit.entity.Player;
import art.arcane.gloss.service.PaperBridges;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Method;
import java.util.Optional;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The Paper chat bridge is reached only reflectively, so the shapes it depends on are pinned here:
 * if Paper renames or retypes one of them the alarm fires in this suite rather than at runtime.
 */
class PaperChatListenerTest {
    private static final String EVENT = "io.papermc.paper.event.player.AsyncChatEvent";

    @Test
    void theBridgeLoadsThroughPaperBridgesWithThePluginAsItsOnlyArgument() throws Exception {
        Gloss gloss = CharacterizationSupport.bareGloss(
            CharacterizationSupport.server(java.util.Map.of()));

        Optional<Listener> bridge = PaperBridges.load(EVENT,
            "art.arcane.gloss.paper.PaperChatListener", Listener.class, gloss);

        assertTrue(bridge.isPresent());
        assertEquals(PaperChatListener.class, bridge.orElseThrow().getClass());
    }

    @Test
    void theChatEventStillExposesMessageViewersAndRenderer() throws Exception {
        Class<?> event = Class.forName(EVENT);

        Method message = event.getMethod("message");
        Method viewers = event.getMethod("viewers");
        Method renderer = event.getMethod("renderer");

        assertNotNull(message.getReturnType());
        assertEquals(Set.class, viewers.getReturnType());
        assertNotNull(event.getMethod("renderer", renderer.getReturnType()));
    }

    @Test
    void theRendererInterfaceStillCarriesAFourArgumentRender() throws Exception {
        Class<?> renderer = Class.forName(EVENT).getMethod("renderer").getReturnType();

        long renders = java.util.Arrays.stream(renderer.getMethods())
            .filter(method -> method.getName().equals("render") && method.getParameterCount() == 4)
            .count();

        assertEquals(1L, renders);
    }

    @Test
    void theBridgeListensAtHighAndIgnoresCancelledEvents() throws Exception {
        Method handler = PaperChatListener.class.getMethod("onChat", Class.forName(EVENT));
        EventHandler annotation = handler.getAnnotation(EventHandler.class);

        assertNotNull(annotation);
        assertEquals(EventPriority.HIGH, annotation.priority());
        assertTrue(annotation.ignoreCancelled());
    }

    @Test
    void aChatPromptClaimsTheLineEvenWhileTheChannelEngineIsNotCarryingChat() throws Exception {
        Server server = CharacterizationSupport.server(java.util.Map.of());
        Object previousServer = CharacterizationSupport.installServer(server);
        Gloss gloss = CharacterizationSupport.bareGloss(server);
        CharacterizationSupport.setField(gloss, "laneServices", java.util.List.of());
        Gloss previousGloss = CharacterizationSupport.installGloss(gloss);
        java.util.UUID id = java.util.UUID.randomUUID();
        java.util.List<String> answered = new java.util.ArrayList<>();
        try {
            ChatCapture.clear();
            assertTrue(ChatCapture.claim(id, answered::add, 10_000L));
            AsyncChatEvent event = chatEvent(player(id), "my answer");

            new PaperChatListener(gloss).onChat(event);

            assertEquals(java.util.List.of("my answer"), answered);
            assertTrue(event.isCancelled(), "a claimed line must not reach the vanilla broadcast");
        } finally {
            ChatCapture.clear();
            CharacterizationSupport.restoreGloss(previousGloss);
            CharacterizationSupport.restoreServer(previousServer);
        }
    }

    private static AsyncChatEvent chatEvent(Player sender, String text) {
        return new AsyncChatEvent(true, sender, new java.util.HashSet<>(),
            (source, sourceDisplayName, message, audience) -> message,
            Component.text(text), Component.text(text), null);
    }

    private static Player player(java.util.UUID id) {
        return (Player) java.lang.reflect.Proxy.newProxyInstance(Player.class.getClassLoader(),
            new Class<?>[]{Player.class}, (proxy, method, args) -> switch (method.getName()) {
                case "getUniqueId" -> id;
                case "getName" -> "Tester";
                case "isOnline" -> true;
                case "hashCode" -> id.hashCode();
                case "equals" -> proxy == args[0];
                case "toString" -> "Player[Tester]";
                default -> throw new UnsupportedOperationException(method.getName());
            });
    }

    @Test
    void theRendererProxyImplementsTheServerRendererInterface() throws Exception {
        Class<?> rendererType = Class.forName(EVENT).getMethod("renderer").getReturnType();

        Object proxy = PaperChatRendererProxy.create(viewer -> "<red>hi");

        assertTrue(rendererType.isInstance(proxy));
    }
}
