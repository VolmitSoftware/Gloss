package art.arcane.gloss.dialog;

import art.arcane.gloss.Gloss;
import art.arcane.gloss.GlossConfig;
import art.arcane.gloss.locale.GlossLocalization;
import art.arcane.gloss.menu.CharacterizationSupport;
import com.github.retrooper.packetevents.PacketEvents;
import com.github.retrooper.packetevents.protocol.dialog.Dialog;
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
import java.util.logging.Logger;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * What a viewer whose client cannot draw a dialog is told. A Bedrock viewer with no Geyser forms
 * bridge, or anyone on a client older than the dialog protocol, takes the fallback path; the one
 * thing that must never happen is nothing at all happening.
 */
class DialogFallbackTest {
    private static final String NO_FALLBACK = """
        { "schemaVersion": 1, "revision": 1, "type": "notice", "title": "Shop",
          "buttons": [ { "label": "Ok" } ] }
        """;

    @BeforeAll
    static void installPacketEventsApi() {
        DialogPacketEventsStub.install();
    }

    @AfterAll
    static void clearPacketEventsApi() {
        PacketEvents.setAPI(null);
    }

    private final List<String> told = new ArrayList<>();
    private Path dialogs;
    private Object previousServer;
    private Gloss previousGloss;
    private DialogService service;

    @BeforeEach
    void installHeadlessServer(@TempDir Path folder) throws ReflectiveOperationException, IOException {
        Server server = CharacterizationSupport.server(Map.of());
        previousServer = CharacterizationSupport.installServer(server);
        Gloss gloss = CharacterizationSupport.bareGloss(server);
        CharacterizationSupport.setField(gloss, "dataFolder", folder.toFile());
        Logger logger = Logger.getAnonymousLogger();
        logger.setUseParentHandlers(false);
        CharacterizationSupport.setField(gloss, "localization",
            new GlossLocalization(folder.resolve("lang").toFile(), logger, GlossConfig.current().language()));
        previousGloss = CharacterizationSupport.installGloss(gloss);
        dialogs = folder.resolve(DialogDoc.KIND);
        Files.createDirectories(dialogs);
        service = new DialogService(gloss, new SilentSink(), viewer -> false, (viewer, task) -> task.run(),
            System::currentTimeMillis);
    }

    @AfterEach
    void restore() throws ReflectiveOperationException {
        CharacterizationSupport.restoreGloss(previousGloss);
        CharacterizationSupport.restoreServer(previousServer);
    }

    @Test
    void aClientThatCannotDrawTheDialogAndHasNoFallbackIsToldSo() throws IOException {
        Files.writeString(dialogs.resolve("shop.json"), NO_FALLBACK, StandardCharsets.UTF_8);
        service.reload();

        assertFalse(service.open(player(), "shop", Map.of()));

        assertTrue(told.stream().anyMatch(line -> line.toLowerCase().contains("cannot display")),
            "the viewer was told nothing: " + told);
    }

    @Test
    void aFallbackNamingAnInventoryThatIsNotThereStillTellsTheViewer() throws IOException {
        Files.writeString(dialogs.resolve("shop.json"), """
            { "schemaVersion": 1, "revision": 1, "type": "notice", "title": "Shop",
              "fallback": { "inventory": "nowhere" },
              "buttons": [ { "label": "Ok" } ] }
            """, StandardCharsets.UTF_8);
        service.reload();

        assertFalse(service.open(player(), "shop", Map.of()));

        assertTrue(told.stream().anyMatch(line -> line.toLowerCase().contains("cannot display")),
            "the viewer was told nothing: " + told);
    }

    private Player player() {
        UUID id = UUID.randomUUID();
        return (Player) Proxy.newProxyInstance(DialogFallbackTest.class.getClassLoader(),
            new Class<?>[]{Player.class}, (Object proxy, Method method, Object[] args) -> switch (method.getName()) {
                case "getUniqueId" -> id;
                case "getName" -> "Tester";
                case "isOnline" -> true;
                case "sendMessage", "sendRichMessage" -> {
                    told.add(String.valueOf(args[0]));
                    yield null;
                }
                case "hashCode" -> id.hashCode();
                case "equals" -> proxy == args[0];
                case "toString" -> "Player[Tester]";
                default -> throw new UnsupportedOperationException("fake player was asked for " + method.getName());
            });
    }

    private static final class SilentSink implements DialogService.Sink {
        @Override
        public void show(Player viewer, String documentId, String token, Dialog dialog) {
            throw new UnsupportedOperationException("this client cannot draw dialogs");
        }

        @Override
        public void clear(Player viewer) {
        }
    }
}
