package art.arcane.gloss.chat;

import art.arcane.gloss.Gloss;
import art.arcane.gloss.menu.CharacterizationSupport;
import org.bukkit.World;
import org.bukkit.entity.Player;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ChatCommandsTest {
    @TempDir
    Path dataFolder;

    private final List<Player> online = new ArrayList<>();
    private final World overworld = ChatTestHarness.world("world");
    private Gloss previousInstance;
    private boolean installed;
    private ChannelService channels;
    private ChatCommands commands;

    @AfterEach
    void restore() {
        if (installed) {
            CharacterizationSupport.restoreGloss(previousInstance);
            installed = false;
        }
        ChatCapture.clear();
    }

    @Test
    void aPrivateMessageReachesBothSidesAndOpensAReplyPath() throws Exception {
        boot();
        ChatTestHarness.FakePlayer steve = join("Steve").allow("gloss.chat.msg");
        ChatTestHarness.FakePlayer alex = join("Alex");

        commands.execute(steve.proxy, "msg", new String[]{"Alex", "hello", "there"});

        assertEquals(1, steve.received.size());
        assertEquals(1, alex.received.size());
        assertTrue(alex.received.getFirst().contains("hello there"), alex.received.getFirst());
        assertEquals(alex.id, channels.state().partnerOf(steve.id));
        assertEquals(steve.id, channels.state().partnerOf(alex.id));
    }

    @Test
    void messagingAnOfflineNameOrYourselfIsRefused() throws Exception {
        boot();
        ChatTestHarness.FakePlayer steve = join("Steve").allow("gloss.chat.msg");

        commands.execute(steve.proxy, "msg", new String[]{"Nobody", "hi"});
        commands.execute(steve.proxy, "msg", new String[]{"Steve", "hi"});

        assertEquals(2, steve.received.size());
        assertTrue(steve.received.getFirst().contains("Nobody"), steve.received.getFirst());
        assertTrue(steve.received.get(1).toLowerCase().contains("yourself"), steve.received.get(1));
    }

    @Test
    void messageWithoutTextPrintsTheUsage() throws Exception {
        boot();
        ChatTestHarness.FakePlayer steve = join("Steve").allow("gloss.chat.msg");

        commands.execute(steve.proxy, "msg", new String[]{"Alex"});

        assertEquals(1, steve.received.size());
        assertTrue(steve.received.getFirst().contains("/msg"), steve.received.getFirst());
    }

    @Test
    void replyingWithNoConversationIsRefusedAndThenWorks() throws Exception {
        boot();
        ChatTestHarness.FakePlayer steve = join("Steve").allow("gloss.chat.msg");
        ChatTestHarness.FakePlayer alex = join("Alex").allow("gloss.chat.msg");

        commands.execute(alex.proxy, "r", new String[]{"hi"});
        assertEquals(1, alex.received.size());

        commands.execute(steve.proxy, "msg", new String[]{"Alex", "ping"});
        alex.received.clear();
        commands.execute(alex.proxy, "r", new String[]{"pong"});

        assertTrue(alex.received.getFirst().contains("pong"), alex.received.getFirst());
    }

    @Test
    void theChannelCommandListsSelectsAndRefusesUnknownNames() throws Exception {
        boot();
        ChatTestHarness.FakePlayer steve = join("Steve").allow("gloss.chat.channel");

        commands.execute(steve.proxy, "ch", new String[]{"list"});
        assertTrue(steve.received.getFirst().contains("global"), steve.received.getFirst());
        assertFalse(steve.received.getFirst().contains("private"), steve.received.getFirst());

        commands.execute(steve.proxy, "ch", new String[]{"staff"});
        assertEquals("staff", channels.activeChannel(steve.proxy).id());

        commands.execute(steve.proxy, "ch", new String[]{"nope"});
        assertTrue(steve.received.getLast().contains("nope"), steve.received.getLast());
    }

    @Test
    void aDirectChannelCannotBeSelectedByHand() throws Exception {
        boot();
        ChatTestHarness.FakePlayer steve = join("Steve").allow("gloss.chat.channel");

        commands.execute(steve.proxy, "ch", new String[]{"private"});

        assertEquals("global", channels.activeChannel(steve.proxy).id());
    }

    @Test
    void everyCommandIsGatedOnItsPermission() throws Exception {
        boot();
        ChatTestHarness.FakePlayer steve = join("Steve");
        join("Alex");

        commands.execute(steve.proxy, "msg", new String[]{"Alex", "hi"});
        commands.execute(steve.proxy, "ch", new String[]{"list"});

        assertEquals(2, steve.received.size());
        assertTrue(steve.received.getFirst().toLowerCase().contains("permission"), steve.received.getFirst());
    }

    @Test
    void tabCompletionOffersPlayersForMessagesAndChannelsForChannelSwitching() throws Exception {
        boot();
        ChatTestHarness.FakePlayer steve = join("Steve").allow("gloss.chat.msg").allow("gloss.chat.channel");
        join("Alex");

        assertEquals(List.of("Alex", "Steve"), commands.complete(steve.proxy, "msg", new String[]{""}));
        assertEquals(List.of("global", "list", "staff"),
            commands.complete(steve.proxy, "ch", new String[]{""}));
        assertEquals(List.of("staff"), commands.complete(steve.proxy, "ch", new String[]{"st"}));
        assertEquals(List.of(), commands.complete(steve.proxy, "r", new String[]{""}));
    }

    @Test
    void whispersRunThroughTheSameThrottleAsPublicChat() throws Exception {
        writePrivate("\"permission\":\"\"", "{\"minIntervalTicks\":40,\"maxRepeats\":1,\"repeatWindowTicks\":200}");
        boot();
        ChatTestHarness.FakePlayer steve = join("Steve").allow("gloss.chat.msg");
        ChatTestHarness.FakePlayer alex = join("Alex");

        commands.execute(steve.proxy, "msg", new String[]{"Alex", "hello"});
        commands.execute(steve.proxy, "msg", new String[]{"Alex", "hello again"});

        assertEquals(1, alex.received.size(), alex.received.toString());
        assertTrue(steve.received.getLast().toLowerCase().contains("slow"), steve.received.getLast());
    }

    @Test
    void thePrivateChannelsOwnPermissionGatesWhispers() throws Exception {
        writePrivate("\"permission\":\"gloss.chat.private\"", "{\"minIntervalTicks\":0}");
        boot();
        ChatTestHarness.FakePlayer steve = join("Steve").allow("gloss.chat.msg");
        ChatTestHarness.FakePlayer alex = join("Alex");

        commands.execute(steve.proxy, "msg", new String[]{"Alex", "hello"});

        assertEquals(0, alex.received.size());
        assertTrue(steve.received.getLast().toLowerCase().contains("permission"), steve.received.getLast());
    }

    private void writePrivate(String permission, String throttle) throws IOException {
        write("private", "{\"schemaVersion\":1,\"revision\":1,"
            + "\"channel\":{\"name\":\"private\",\"scope\":\"direct\"," + permission + "},"
            + "\"format\":\"&7[{{ sender.name }} -> {{ viewer.name }}] &f{{ message }}\","
            + "\"throttle\":" + throttle + "}");
    }

    private void boot() throws Exception {
        write("global", "{\"schemaVersion\":1,\"revision\":1,"
            + "\"channel\":{\"name\":\"global\",\"default\":true,\"scope\":\"global\"},"
            + "\"format\":\"&f{{ sender.name }}&8: &f{{ message }}\"}");
        write("staff", "{\"schemaVersion\":1,\"revision\":1,"
            + "\"channel\":{\"name\":\"staff\",\"scope\":\"global\"},"
            + "\"format\":\"&c{{ message }}\"}");
        Gloss gloss = ChatTestHarness.gloss(dataFolder.toFile(), online);
        previousInstance = CharacterizationSupport.installGloss(gloss);
        installed = true;
        channels = new ChannelService(gloss);
        channels.enable();
        commands = new ChatCommands(gloss, channels);
    }

    private void write(String id, String json) throws IOException {
        Path folder = dataFolder.resolve(ChannelDoc.KIND);
        Files.createDirectories(folder);
        Files.writeString(folder.resolve(id + ".json"), json);
    }

    private ChatTestHarness.FakePlayer join(String name) {
        ChatTestHarness.FakePlayer player = ChatTestHarness.player(name, overworld, 0.0D);
        online.add(player.proxy);
        return player;
    }
}
