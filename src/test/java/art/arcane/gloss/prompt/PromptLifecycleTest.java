package art.arcane.gloss.prompt;

import art.arcane.gloss.Gloss;
import art.arcane.gloss.chat.ChatCapture;
import art.arcane.gloss.menu.CharacterizationSupport;
import art.arcane.gloss.packets.PacketEventsStub;
import com.github.retrooper.packetevents.event.PacketSendEvent;
import com.github.retrooper.packetevents.manager.server.ServerVersion;
import com.github.retrooper.packetevents.protocol.ConnectionState;
import com.github.retrooper.packetevents.protocol.packettype.PacketType;
import com.github.retrooper.packetevents.protocol.player.User;
import com.github.retrooper.packetevents.protocol.player.UserProfile;
import art.arcane.gloss.menu.SessionVariables;
import art.arcane.gloss.menu.action.ActionContext;
import org.bukkit.inventory.AnvilInventory;
import org.bukkit.inventory.view.AnvilView;
import net.md_5.bungee.api.chat.BaseComponent;
import org.bukkit.Server;
import org.bukkit.Location;
import com.github.retrooper.packetevents.util.Vector3i;
import org.bukkit.entity.Player;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.InventoryView;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.PrepareAnvilEvent;
import org.bukkit.event.inventory.InventoryType;
import org.bukkit.event.inventory.ClickType;
import org.bukkit.event.inventory.InventoryAction;
import org.bukkit.event.player.PlayerQuitEvent;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Method;
import java.lang.reflect.Constructor;
import java.lang.reflect.Proxy;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertNotSame;
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
    @SuppressWarnings("unchecked")
    void signCancellationAndOldTimeoutsKeepTheCurrentRequestIsolated() throws ReflectiveOperationException {
        Player viewer = (Player) Proxy.newProxyInstance(getClass().getClassLoader(), new Class<?>[]{Player.class},
            (proxy, method, args) -> switch (method.getName()) {
                case "getUniqueId" -> PLAYER_ID;
                case "isOnline" -> false;
                default -> CharacterizationSupport.identity(proxy, method, args);
            });
        SignPrompt sign = (SignPrompt) CharacterizationSupport.getField(service, "sign");
        ConcurrentMap<UUID, SignPrompt.Editor> editors =
            (ConcurrentMap<UUID, SignPrompt.Editor>) CharacterizationSupport.getField(sign, "editors");
        ConcurrentMap<UUID, PromptRequest> pending =
            (ConcurrentMap<UUID, PromptRequest>) CharacterizationSupport.getField(service, "pending");
        PromptRequest first = request(PromptRequest.SIGN);
        SignPrompt.Editor oldEditor = new SignPrompt.Editor(viewer, new Vector3i(1, 68, 2),
            new Location(null, 1, 68, 2), null, first);
        pending.put(PLAYER_ID, first);
        editors.put(PLAYER_ID, oldEditor);
        service.cancel(PLAYER_ID);
        assertNull(service.pending(PLAYER_ID));
        assertTrue(editors.isEmpty());

        PromptRequest next = new PromptRequest(PromptRequest.SIGN, "next", "", "", List.of(), 20, null);
        SignPrompt.Editor nextEditor = new SignPrompt.Editor(viewer, new Vector3i(3, 68, 2),
            new Location(null, 3, 68, 2), null, next);
        pending.put(PLAYER_ID, next);
        editors.put(PLAYER_ID, nextEditor);
        sign.expire(viewer, first, oldEditor);
        assertEquals(next, service.pending(PLAYER_ID));
        assertEquals(nextEditor, editors.get(PLAYER_ID));

        sign.expire(viewer, next, nextEditor);
        assertNull(service.pending(PLAYER_ID));
        assertTrue(editors.isEmpty());
    }

    @Test
    @SuppressWarnings("unchecked")
    void clientWorldResetRetiresSignAndItsOldTimeoutCannotCancelTheNextChat() throws ReflectiveOperationException {
        Player viewer = player();
        SignPrompt sign = (SignPrompt) CharacterizationSupport.getField(service, "sign");
        ConcurrentMap<UUID, SignPrompt.Editor> editors =
            (ConcurrentMap<UUID, SignPrompt.Editor>) CharacterizationSupport.getField(sign, "editors");
        ConcurrentMap<UUID, PromptRequest> pending =
            (ConcurrentMap<UUID, PromptRequest>) CharacterizationSupport.getField(service, "pending");
        PromptRequest first = request(PromptRequest.SIGN);
        SignPrompt.Editor oldEditor = new SignPrompt.Editor(viewer, new Vector3i(1, 68, 2),
            new Location(null, 1, 68, 2), null, first);
        pending.put(PLAYER_ID, first);
        editors.put(PLAYER_ID, oldEditor);

        sign.worldReset(PLAYER_ID);

        assertNull(service.pending(PLAYER_ID));
        assertTrue(editors.isEmpty());
        PromptRequest next = request(PromptRequest.CHAT);
        assertTrue(service.prompt(viewer, next));
        sign.expire(viewer, first, oldEditor);
        assertEquals(next, service.pending(PLAYER_ID));
        service.cancel(PLAYER_ID);
    }

    @Test
    @SuppressWarnings("unchecked")
    void staleWorldResetAndReleaseCannotRemoveAReplacementSign() throws ReflectiveOperationException {
        Player viewer = player();
        SignPrompt sign = (SignPrompt) CharacterizationSupport.getField(service, "sign");
        ConcurrentMap<UUID, SignPrompt.Editor> editors =
            (ConcurrentMap<UUID, SignPrompt.Editor>) CharacterizationSupport.getField(sign, "editors");
        ConcurrentMap<UUID, PromptRequest> pending =
            (ConcurrentMap<UUID, PromptRequest>) CharacterizationSupport.getField(service, "pending");
        PromptRequest first = request(PromptRequest.SIGN);
        SignPrompt.Editor oldEditor = new SignPrompt.Editor(viewer, new Vector3i(1, 68, 2),
            new Location(null, 1, 68, 2), null, first);
        PromptRequest next = request(PromptRequest.SIGN);
        assertEquals(first, next);
        assertNotSame(first, next);
        SignPrompt.Editor nextEditor = new SignPrompt.Editor(viewer, new Vector3i(1, 68, 2),
            new Location(null, 1, 68, 2), null, next);
        pending.put(PLAYER_ID, next);
        editors.put(PLAYER_ID, nextEditor);

        sign.resetEditor(PLAYER_ID, oldEditor);
        sign.release(PLAYER_ID, first);
        service.cancel(PLAYER_ID, first);
        service.complete(viewer, first, "stale");
        sign.expire(viewer, first, oldEditor);

        assertEquals(next, service.pending(PLAYER_ID));
        assertEquals(nextEditor, editors.get(PLAYER_ID));
        sign.worldReset(PLAYER_ID);
        assertNull(service.pending(PLAYER_ID));
        assertTrue(editors.isEmpty());
    }

    @Test
    @SuppressWarnings("unchecked")
    void registeredRespawnListenerIgnoresCancelledAndUnrelatedPacketsWithoutRestoringOldWorld() throws ReflectiveOperationException {
        PacketEventsStub packets = PacketEventsStub.install();
        SignPrompt sign = (SignPrompt) CharacterizationSupport.getField(service, "sign");
        try {
            sign.enable();
            Player viewer = player();
            PromptRequest request = request(PromptRequest.SIGN);
            ConcurrentMap<UUID, SignPrompt.Editor> editors =
                (ConcurrentMap<UUID, SignPrompt.Editor>) CharacterizationSupport.getField(sign, "editors");
            ConcurrentMap<UUID, PromptRequest> pending =
                (ConcurrentMap<UUID, PromptRequest>) CharacterizationSupport.getField(service, "pending");
            SignPrompt.Editor editor = new SignPrompt.Editor(viewer, new Vector3i(1, 68, 2),
                new Location(null, 1, 68, 2), null, request);
            editors.put(PLAYER_ID, editor);
            pending.put(PLAYER_ID, request);

            packets.getEventManager().callEvent(outbound(PacketType.Play.Server.KEEP_ALIVE, false));
            packets.getEventManager().callEvent(outbound(PacketType.Play.Server.RESPAWN, true));
            assertEquals(request, service.pending(PLAYER_ID));
            assertEquals(editor, editors.get(PLAYER_ID));

            packets.getEventManager().callEvent(outbound(PacketType.Play.Server.RESPAWN, false));

            assertNull(service.pending(PLAYER_ID));
            assertTrue(editors.isEmpty());
            assertTrue(packets.sent().isEmpty());
            assertTrue(service.prompt(viewer, request(PromptRequest.CHAT)));
            service.cancel(PLAYER_ID);
        } finally {
            sign.disable();
            PacketEventsStub.uninstall();
        }
    }

    private static PacketSendEvent outbound(PacketType.Play.Server type, boolean cancelled) throws ReflectiveOperationException {
        Constructor<?> constructor = sun.reflect.ReflectionFactory.getReflectionFactory()
            .newConstructorForSerialization(User.class, Object.class.getDeclaredConstructor());
        User user = (User) constructor.newInstance();
        CharacterizationSupport.setField(user, "profile", new UserProfile(PLAYER_ID, "Tester"));
        CharacterizationSupport.setField(user, "encoderState", ConnectionState.PLAY);
        PacketSendEvent event = new PacketSendEvent(0, type, ServerVersion.V_26_2,
            new Object(), user, null, new Object()) {};
        event.setCancelled(cancelled);
        event.markForReEncode(false);
        return event;
    }

    @Test
    void worldResetWithoutSignLeavesChatCaptureActive() throws ReflectiveOperationException {
        Player viewer = player();
        PromptRequest request = request(PromptRequest.CHAT);
        assertTrue(service.prompt(viewer, request));
        SignPrompt sign = (SignPrompt) CharacterizationSupport.getField(service, "sign");

        sign.worldReset(PLAYER_ID);

        assertEquals(request, service.pending(PLAYER_ID));
        assertFalse(service.prompt(viewer, request(PromptRequest.CHAT)));
        service.cancel(PLAYER_ID);
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
    @SuppressWarnings("unchecked")
    void resultClickReadsCurrentNameFromNonPublicAnvilView() throws ReflectiveOperationException {
        Player viewer = player();
        SessionVariables variables = SessionVariables.of(Map.of("name", "Garden"), Map.of());
        ActionContext origin = (ActionContext) Proxy.newProxyInstance(getClass().getClassLoader(),
            new Class<?>[]{ActionContext.class}, (proxy, method, args) -> switch (method.getName()) {
                case "sessionVariables" -> variables;
                default -> CharacterizationSupport.identity(proxy, method, args);
            });
        PromptRequest request = new PromptRequest(PromptRequest.ANVIL, "name", "Name", "Garden",
            List.of(), 20, origin);
        AnvilPrompt anvil = (AnvilPrompt) CharacterizationSupport.getField(service, "anvil");
        ConcurrentMap<UUID, Inventory> inventories =
            (ConcurrentMap<UUID, Inventory>) CharacterizationSupport.getField(anvil, "inventories");
        ConcurrentMap<UUID, String> typed =
            (ConcurrentMap<UUID, String>) CharacterizationSupport.getField(anvil, "typed");
        ConcurrentMap<UUID, PromptRequest> pending =
            (ConcurrentMap<UUID, PromptRequest>) CharacterizationSupport.getField(service, "pending");
        AnvilInventory inventory = (AnvilInventory) Proxy.newProxyInstance(getClass().getClassLoader(),
            new Class<?>[]{AnvilInventory.class}, (proxy, method, args) -> switch (method.getName()) {
                case "getRenameText" -> "Garden";
                default -> CharacterizationSupport.identity(proxy, method, args);
            });
        AnvilView view = (AnvilView) Proxy.newProxyInstance(getClass().getClassLoader(),
            new Class<?>[]{LocalAnvilView.class}, (proxy, method, args) -> switch (method.getName()) {
                case "getRenameText" -> "Sunlit Garden";
                case "convertSlot" -> args[0];
                case "getPlayer" -> viewer;
                case "getTopInventory" -> inventory;
                default -> CharacterizationSupport.identity(proxy, method, args);
            });
        inventories.put(PLAYER_ID, inventory);
        typed.put(PLAYER_ID, "Garden");
        pending.put(PLAYER_ID, request);
        InventoryClickEvent click = new InventoryClickEvent(view, InventoryType.SlotType.RESULT,
            2, ClickType.LEFT, InventoryAction.PICKUP_ALL);
        anvil.onClick(click);
        assertTrue(click.isCancelled());
        assertEquals("Sunlit Garden", variables.get("name"));
        assertNull(service.pending(PLAYER_ID));
        assertFalse(typed.containsKey(PLAYER_ID));
    }

    @Test
    @SuppressWarnings("unchecked")
    void prepareTracksOnlyTheOwnedAnvilAndRemovesExperienceCost() throws ReflectiveOperationException {
        Player viewer = player();
        AnvilPrompt anvil = (AnvilPrompt) CharacterizationSupport.getField(service, "anvil");
        ConcurrentMap<UUID, Inventory> inventories =
            (ConcurrentMap<UUID, Inventory>) CharacterizationSupport.getField(anvil, "inventories");
        ConcurrentMap<UUID, String> typed =
            (ConcurrentMap<UUID, String>) CharacterizationSupport.getField(anvil, "typed");
        ConcurrentMap<UUID, PromptRequest> pending =
            (ConcurrentMap<UUID, PromptRequest>) CharacterizationSupport.getField(service, "pending");
        AnvilInventory inventory = (AnvilInventory) Proxy.newProxyInstance(getClass().getClassLoader(),
            new Class<?>[]{AnvilInventory.class}, CharacterizationSupport::identity);
        AtomicInteger cost = new AtomicInteger(4);
        AnvilView view = (AnvilView) Proxy.newProxyInstance(getClass().getClassLoader(),
            new Class<?>[]{AnvilView.class}, (proxy, method, args) -> switch (method.getName()) {
                case "getRenameText" -> "Sunlit Garden";
                case "getPlayer" -> viewer;
                case "getTopInventory" -> inventory;
                case "setRepairCost" -> {
                    cost.set((Integer) args[0]);
                    yield null;
                }
                default -> CharacterizationSupport.identity(proxy, method, args);
            });
        pending.put(PLAYER_ID, request(PromptRequest.ANVIL));
        anvil.onPrepare(new PrepareAnvilEvent(view, null));
        assertFalse(typed.containsKey(PLAYER_ID));
        assertEquals(4, cost.get());
        inventories.put(PLAYER_ID, inventory);
        anvil.onPrepare(new PrepareAnvilEvent(view, null));
        assertEquals("Sunlit Garden", typed.get(PLAYER_ID));
        assertEquals(0, cost.get());
        pending.put(PLAYER_ID, request(PromptRequest.SIGN));
        typed.put(PLAYER_ID, "Other prompt");
        anvil.onPrepare(new PrepareAnvilEvent(view, null));
        assertEquals("Other prompt", typed.get(PLAYER_ID));
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
            case "openInventory", "closeInventory" -> null;
            case "spigot" -> MESSENGER;
            case "hashCode" -> System.identityHashCode(proxy);
            case "equals" -> proxy == args[0];
            case "toString" -> "Player[Tester]";
            default -> throw new UnsupportedOperationException("fake player was asked for " + method.getName());
        };
    }
    private interface LocalAnvilView extends AnvilView {
    }
}
