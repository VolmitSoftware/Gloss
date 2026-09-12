package art.arcane.gloss.chat;

import art.arcane.gloss.Gloss;
import art.arcane.gloss.menu.CharacterizationSupport;
import org.bukkit.World;
import org.bukkit.entity.Player;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Chat bubbles and every other consumer must see one call per delivered message, never two. */
class ChatHookFanoutTest {
    @TempDir
    Path dataFolder;

    private final List<Player> online = new ArrayList<>();
    private final World overworld = ChatTestHarness.world("world");
    private Gloss previousInstance;
    private boolean installed;

    @AfterEach
    void restore() {
        if (installed) {
            CharacterizationSupport.restoreGloss(previousInstance);
            installed = false;
        }
        ChatCapture.clear();
    }

    @Test
    void aDeliveredMessageInvokesEveryHookExactlyOnce() throws Exception {
        List<String> first = new ArrayList<>();
        List<String> second = new ArrayList<>();
        Gloss gloss = boot();
        gloss.chat().addChatHook((player, message) -> first.add(player.getName() + ":" + message));
        gloss.chat().addChatHook((player, message) -> second.add(message));
        ChannelService service = new ChannelService(gloss);
        service.enable();
        ChatTestHarness.FakePlayer steve = join("Steve");

        assertTrue(service.dispatch(steve.proxy, "hello", new NoopSink()));

        assertEquals(List.of("Steve:hello"), first);
        assertEquals(List.of("hello"), second);
    }

    @Test
    void aDroppedMessageNeverReachesTheHooks() throws Exception {
        List<String> seen = new ArrayList<>();
        Gloss gloss = boot("\"filters\":[{\"match\":\"(?i)badword\",\"replace\":\"\"}],");
        gloss.chat().addChatHook((player, message) -> seen.add(message));
        ChannelService service = new ChannelService(gloss);
        service.enable();
        ChatTestHarness.FakePlayer steve = join("Steve");

        service.dispatch(steve.proxy, "badword", new NoopSink());

        assertTrue(seen.isEmpty());
    }

    @Test
    void theHookSeesTheFilteredTextNotTheRawText() throws Exception {
        List<String> seen = new ArrayList<>();
        Gloss gloss = boot("\"filters\":[{\"match\":\"(?i)badword\",\"replace\":\"***\"}],");
        gloss.chat().addChatHook((player, message) -> seen.add(message));
        ChannelService service = new ChannelService(gloss);
        service.enable();
        ChatTestHarness.FakePlayer steve = join("Steve");

        service.dispatch(steve.proxy, "a badword here", new NoopSink());

        assertEquals(List.of("a *** here"), seen);
    }

    private Gloss boot() throws Exception {
        return boot("");
    }

    private Gloss boot(String extra) throws Exception {
        Path folder = dataFolder.resolve(ChannelDoc.KIND);
        Files.createDirectories(folder);
        Files.writeString(folder.resolve("global.json"),
            "{\"schemaVersion\":1,\"revision\":1,"
                + "\"channel\":{\"name\":\"global\",\"default\":true,\"scope\":\"global\"},"
                + extra
                + "\"format\":\"&f{{ sender.name }}&8: &f{{ message }}\"}");
        Gloss gloss = ChatTestHarness.gloss(dataFolder.toFile(), online);
        previousInstance = CharacterizationSupport.installGloss(gloss);
        installed = true;
        return gloss;
    }

    private ChatTestHarness.FakePlayer join(String name) {
        ChatTestHarness.FakePlayer player = ChatTestHarness.player(name, overworld, 0.0D);
        online.add(player.proxy);
        return player;
    }

    private static final class NoopSink implements ChatSink {
        @Override
        public void dropped(ChatDrop reason) {
        }

        @Override
        public void audience(ChannelRuntime channel, String message, List<Player> viewers) {
        }
    }
}
