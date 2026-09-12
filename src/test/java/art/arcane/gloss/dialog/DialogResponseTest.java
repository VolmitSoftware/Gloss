package art.arcane.gloss.dialog;

import art.arcane.gloss.Gloss;
import art.arcane.gloss.menu.CharacterizationSupport;
import com.github.retrooper.packetevents.PacketEvents;
import com.github.retrooper.packetevents.protocol.dialog.Dialog;
import com.github.retrooper.packetevents.protocol.nbt.NBTByte;
import com.github.retrooper.packetevents.protocol.nbt.NBTCompound;
import com.github.retrooper.packetevents.protocol.nbt.NBTFloat;
import com.github.retrooper.packetevents.protocol.nbt.NBTInt;
import com.github.retrooper.packetevents.protocol.nbt.NBTString;
import org.bukkit.Server;
import org.bukkit.entity.Player;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicLong;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * What a dialog response does with the payload the client hands back. A screen the player kept open
 * across a replacement, a token past its window, and an index the render has no button for all
 * arrive at this listener, and none of them may run an action list. The observable effect of a
 * button is a second dialog: its {@code when} gate is what proves the inputs were bound and typed.
 */
class DialogResponseTest {

    @BeforeAll
    static void installPacketEventsApi() {
        DialogPacketEventsStub.install();
    }

    @AfterAll
    static void clearPacketEventsApi() {
        PacketEvents.setAPI(null);
    }

    private Path dialogs;
    private Object previousServer;
    private Gloss previousGloss;
    private DialogService service;
    private RecordingSink sink;
    private final AtomicLong clock = new AtomicLong(1_000L);
    private final List<Runnable> deferred = new ArrayList<>();
    private Gloss gloss;
    private boolean supported;

    @BeforeEach
    void installHeadlessServer(@TempDir Path folder) throws ReflectiveOperationException, IOException {
        Server server = CharacterizationSupport.server(Map.of());
        previousServer = CharacterizationSupport.installServer(server);
        gloss = CharacterizationSupport.bareGloss(server);
        CharacterizationSupport.setField(gloss, "dataFolder", folder.toFile());
        previousGloss = CharacterizationSupport.installGloss(gloss);
        dialogs = folder.resolve(DialogDoc.KIND);
        Files.createDirectories(dialogs);
        sink = new RecordingSink();
        deferred.clear();
        supported = true;
        service = new DialogService(gloss, sink, viewer -> supported,
            (viewer, task) -> task.run(), clock::get);
        write("second", SECOND);
    }

    @AfterEach
    void restore() throws ReflectiveOperationException {
        CharacterizationSupport.restoreGloss(previousGloss);
        CharacterizationSupport.restoreServer(previousServer);
    }

    @Test
    void aMatchingTokenRunsTheButtonsActions() throws IOException {
        open("trader", TRADER);
        assertTrue(service.onResponse(player(), payload(0)));
        assertEquals(List.of("trader", "second"), sink.documentIds());
    }

    @Test
    void aBurstOfTheSamePayloadRunsTheButtonOnce() throws IOException {
        deferDispatch();
        open("trader", TRADER);
        NBTCompound payload = payload(0);

        assertTrue(service.onResponse(player(), payload));
        assertFalse(service.onResponse(player(), payload));
        assertFalse(service.onResponse(player(), payload));
        runDeferred();

        assertEquals(List.of("trader", "second"), sink.documentIds());
    }

    @Test
    void aScreenThatStaysOpenTakesTheNextPressButNotACopyOfTheOneRunning() throws IOException {
        deferDispatch();
        open("stay", """
            { "schemaVersion": 1, "revision": 1, "type": "multi_action", "title": "Stay",
              "afterAction": "none",
              "buttons": [ { "label": "Again", "actions": [] } ] }
            """);
        NBTCompound payload = payload(0);

        assertTrue(service.onResponse(player(), payload));
        assertFalse(service.onResponse(player(), payload));
        runDeferred();

        assertTrue(service.onResponse(player(), payload));
    }

    @Test
    void aClosedScreenLeavesADeadToken() throws IOException {
        open("plain", """
            { "schemaVersion": 1, "revision": 1, "type": "multi_action", "title": "Plain",
              "buttons": [ { "label": "Ok", "actions": [] } ] }
            """);
        NBTCompound payload = payload(0);

        assertTrue(service.onResponse(player(), payload));
        assertFalse(service.onResponse(player(), payload));
        assertEquals(1, sink.cleared.size());
    }

    @Test
    void aTokenPastItsWindowIsIgnored() throws IOException {
        open("trader", TRADER);
        NBTCompound payload = payload(0);
        clock.addAndGet(1_000L * 60L * 60L * 24L);

        assertFalse(service.onResponse(player(), payload));
        assertEquals(List.of("trader"), sink.documentIds());
    }

    @Test
    void anUnknownTokenIsIgnored() throws IOException {
        open("trader", TRADER);
        NBTCompound payload = payload(0);
        payload.setTag(DialogEncoder.PAYLOAD_TOKEN, new NBTString("not-the-token"));

        assertFalse(service.onResponse(player(), payload));
        assertEquals(List.of("trader"), sink.documentIds());
    }

    @Test
    void anIndexTheRenderHasNoButtonForIsIgnored() throws IOException {
        open("trader", TRADER);
        assertFalse(service.onResponse(player(), payload(9)));
        assertEquals(List.of("trader"), sink.documentIds());
    }

    @Test
    void openingASecondDialogRetiresThePendingOne() throws IOException {
        open("trader", TRADER);
        NBTCompound stale = payload(0);
        write("other", TRADER);
        service.reload();
        assertTrue(service.open(player(), "other", Map.of()));

        assertFalse(service.onResponse(player(), stale));
        assertEquals(List.of("trader", "other"), sink.documentIds());
    }

    @Test
    void inputsBindWithTheTypeTheDocumentDeclared() throws IOException {
        open("inputs", """
            { "schemaVersion": 1, "revision": 1, "type": "multi_action", "title": "Inputs",
              "inputs": [
                { "type": "number", "key": "qty", "label": "Q", "start": 1, "end": 64 },
                { "type": "bool", "key": "gift", "label": "G" },
                { "type": "text", "key": "note", "label": "N" },
                { "type": "option", "key": "color", "label": "C",
                  "options": [ { "id": "red" }, { "id": "blue" } ] }
              ],
              "buttons": [ { "label": "Buy", "actions": [ { "type": "dialog", "id": "second",
                "when": "input.qty == 7 && input.gift && input.note == 'hello' && input.color == 'blue'" } ] } ] }
            """);
        NBTCompound payload = payload(0);
        payload.setTag("qty", new NBTFloat(7F));
        payload.setTag("gift", new NBTByte(true));
        payload.setTag("note", new NBTString("hello"));
        payload.setTag("color", new NBTString("blue"));

        assertTrue(service.onResponse(player(), payload));
        assertEquals(List.of("inputs", "second"), sink.documentIds());
    }

    @Test
    void theInputBindingDoesNotOutliveTheActionRun() throws IOException {
        open("inputs", """
            { "schemaVersion": 1, "revision": 1, "type": "multi_action", "title": "Inputs",
              "inputs": [ { "type": "text", "key": "note", "label": "N" } ],
              "buttons": [ { "label": "Ok", "actions": [] } ] }
            """);
        NBTCompound payload = payload(0);
        payload.setTag("note", new NBTString("scribble"));
        service.onResponse(player(), payload);

        assertEquals(null, new InputNamespace().resolve("note", null));
    }

    @Test
    void waitForResponseReopensTheSameDialogWhenNothingElseOpened() throws IOException {
        open("wait", """
            { "schemaVersion": 1, "revision": 1, "type": "multi_action", "title": "Wait",
              "afterAction": "wait_for_response",
              "buttons": [ { "label": "Again", "actions": [] } ] }
            """);
        service.onResponse(player(), payload(0));

        assertEquals(List.of("wait", "wait"), sink.documentIds());
        assertEquals(0, sink.cleared.size());
    }

    @Test
    void closeAfterActionClearsTheDialogWhenNoActionTookOver() throws IOException {
        open("plain", """
            { "schemaVersion": 1, "revision": 1, "type": "multi_action", "title": "Plain",
              "buttons": [ { "label": "Ok", "actions": [] } ] }
            """);
        service.onResponse(player(), payload(0));
        assertEquals(1, sink.cleared.size());
    }

    @Test
    void anActionThatOpenedAnotherSurfaceIsNotCleared() throws IOException {
        open("trader", TRADER);
        service.onResponse(player(), payload(0));
        assertEquals(0, sink.cleared.size());
    }

    private static final String TRADER = """
        { "schemaVersion": 1, "revision": 1, "type": "multi_action", "title": "Trader",
          "buttons": [ { "label": "Buy", "actions": [ { "type": "dialog", "id": "second" } ] } ] }
        """;

    private static final String SECOND = """
        { "schemaVersion": 1, "revision": 1, "type": "notice", "title": "Second",
          "buttons": [ { "label": "Ok" } ] }
        """;

    /** Stands in for the region hop a real response takes between the netty thread and the actions. */
    private void deferDispatch() {
        service = new DialogService(gloss, sink, viewer -> supported, (viewer, task) -> deferred.add(task),
            clock::get);
    }

    private void runDeferred() {
        List<Runnable> tasks = List.copyOf(deferred);
        deferred.clear();
        tasks.forEach(Runnable::run);
    }

    private void open(String id, String raw) throws IOException {
        write(id, raw);
        service.reload();
        assertTrue(service.open(player(), id, Map.of()));
    }

    private void write(String id, String raw) throws IOException {
        Files.writeString(dialogs.resolve(id + ".json"), raw, StandardCharsets.UTF_8);
    }

    private NBTCompound payload(int buttonIndex) {
        RecordingSink.Shown shown = sink.shown.getLast();
        NBTCompound payload = new NBTCompound();
        payload.setTag(DialogEncoder.PAYLOAD_DOCUMENT, new NBTString(shown.documentId()));
        payload.setTag(DialogEncoder.PAYLOAD_BUTTON, new NBTInt(buttonIndex));
        payload.setTag(DialogEncoder.PAYLOAD_TOKEN, new NBTString(shown.token()));
        return payload;
    }

    private static final UUID PLAYER_ID = UUID.randomUUID();

    private static Player player() {
        return (Player) Proxy.newProxyInstance(DialogResponseTest.class.getClassLoader(),
            new Class<?>[]{Player.class}, DialogResponseTest::answer);
    }

    private static Object answer(Object proxy, Method method, Object[] args) {
        return switch (method.getName()) {
            case "getUniqueId" -> PLAYER_ID;
            case "getName" -> "Tester";
            case "isOnline" -> true;
            case "hashCode" -> System.identityHashCode(proxy);
            case "equals" -> proxy == args[0];
            case "toString" -> "Player[Tester]";
            default -> throw new UnsupportedOperationException("fake player was asked for " + method.getName());
        };
    }

    /** Captures what would have gone out on the wire. */
    private static final class RecordingSink implements DialogService.Sink {
        private final List<Shown> shown = new ArrayList<>();
        private final List<UUID> cleared = new ArrayList<>();

        @Override
        public void show(Player viewer, String documentId, String token, Dialog dialog) {
            shown.add(new Shown(documentId, token, dialog));
        }

        @Override
        public void clear(Player viewer) {
            cleared.add(viewer.getUniqueId());
        }

        private List<String> documentIds() {
            return shown.stream().map(Shown::documentId).toList();
        }

        private record Shown(String documentId, String token, Dialog dialog) {
        }
    }
}
