package art.arcane.gloss.prompt;

import art.arcane.gloss.Gloss;
import art.arcane.gloss.chat.ChatCapture;
import art.arcane.gloss.menu.CharacterizationSupport;
import net.md_5.bungee.api.chat.BaseComponent;
import org.bukkit.Server;
import org.bukkit.entity.Player;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.InventoryView;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryType;
import org.bukkit.event.inventory.ClickType;
import org.bukkit.event.inventory.InventoryAction;
import org.bukkit.event.player.PlayerQuitEvent;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.junit.jupiter.api.Assertions.assertEquals;
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
    private static final List<String> MESSAGES = new ArrayList<>();
    private static final Player.Spigot MESSENGER = new Player.Spigot() {
        @Override
        public void sendMessage(BaseComponent... components) {
            MESSAGES.add(BaseComponent.toPlainText(components));
        }
    };

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
        MESSAGES.clear();
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
    void chatPromptsShowTheLabelAndInitialValue() {
        Player viewer = player();
        PromptRequest request = new PromptRequest(PromptRequest.CHAT, "name", "Label", "Starting value",
            List.of(), 20, null);
        assertTrue(service.prompt(viewer, request));
        assertEquals(List.of("Starting value", "Label"), MESSAGES);
    }

    @Test
    @SuppressWarnings("unchecked")
    void timedOutAnvilsClearTheSeedAndKeepCancellingInventoryClicks() throws ReflectiveOperationException {
        Player viewer = player();
        PromptRequest request = request(PromptRequest.ANVIL);
        AnvilPrompt anvil = (AnvilPrompt) CharacterizationSupport.getField(service, "anvil");
        ConcurrentMap<UUID, Inventory> inventories =
            (ConcurrentMap<UUID, Inventory>) CharacterizationSupport.getField(anvil, "inventories");
        ConcurrentMap<UUID, PromptRequest> pending =
            (ConcurrentMap<UUID, PromptRequest>) CharacterizationSupport.getField(service, "pending");
        AtomicBoolean cleared = new AtomicBoolean();
        Inventory inventory = (Inventory) Proxy.newProxyInstance(getClass().getClassLoader(),
            new Class<?>[]{Inventory.class}, (proxy, method, args) -> {
                if (method.getName().equals("clear")) {
                    cleared.set(true);
                    return null;
                }
                return CharacterizationSupport.identity(proxy, method, args);
            });
        inventories.put(PLAYER_ID, inventory);
        pending.put(PLAYER_ID, request);

        service.timeout(viewer, request);

        assertTrue(cleared.get());
        assertNull(service.pending(PLAYER_ID));
        InventoryView view = (InventoryView) Proxy.newProxyInstance(getClass().getClassLoader(),
            new Class<?>[]{InventoryView.class}, (proxy, method, args) -> switch (method.getName()) {
                case "convertSlot" -> args[0];
                case "getPlayer" -> viewer;
                case "getTopInventory" -> inventory;
                default -> CharacterizationSupport.identity(proxy, method, args);
            });
        InventoryClickEvent click = new InventoryClickEvent(view, InventoryType.SlotType.CONTAINER,
            0, ClickType.LEFT, InventoryAction.PICKUP_ALL) {
            @Override
            public Player getWhoClicked() {
                return viewer;
            }

            @Override
            public Inventory getInventory() {
                return inventory;
            }
        };
        anvil.onClick(click);
        assertTrue(click.isCancelled());
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
            case "spigot" -> MESSENGER;
            case "hashCode" -> System.identityHashCode(proxy);
            case "equals" -> proxy == args[0];
            case "toString" -> "Player[Tester]";
            default -> throw new UnsupportedOperationException("fake player was asked for " + method.getName());
        };
    }
}
