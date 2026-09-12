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
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ChannelServiceTest {
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
    void aMessageTheFiltersEmptyIsDroppedAndTheSenderIsTold() throws Exception {
        ChannelService service = service(global("\"filters\":[{\"match\":\"(?i)badword\",\"replace\":\"\"}],"));
        ChatTestHarness.FakePlayer steve = join("Steve");
        RecordingSink sink = new RecordingSink();

        assertFalse(service.dispatch(steve.proxy, "badword", sink));

        assertEquals(List.of(ChatDrop.FILTERED), sink.drops);
        assertTrue(sink.audiences.isEmpty());
        assertEquals(1, steve.received.size());
    }

    @Test
    void aRepeatedMessageInsideTheWindowIsDropped() throws Exception {
        ChannelService service = service(global("\"throttle\":{\"repeatWindowTicks\":200,\"maxRepeats\":1,\"minIntervalTicks\":0},"));
        ChatTestHarness.FakePlayer steve = join("Steve");
        RecordingSink sink = new RecordingSink();

        assertTrue(service.dispatch(steve.proxy, "hello", sink));
        assertFalse(service.dispatch(steve.proxy, "hello", sink));

        assertEquals(List.of(ChatDrop.REPEAT), sink.drops);
        assertEquals(1, sink.audiences.size());
    }

    @Test
    void aPromptThatClaimedTheSenderSwallowsTheMessage() throws Exception {
        ChannelService service = service(global(""));
        ChatTestHarness.FakePlayer steve = join("Steve");
        List<String> captured = new ArrayList<>();
        assertTrue(ChatCapture.claim(steve.id, captured::add, 5_000L));
        RecordingSink sink = new RecordingSink();

        assertFalse(service.dispatch(steve.proxy, "answer", sink));

        assertEquals(List.of("answer"), captured);
        assertEquals(List.of(ChatDrop.CAPTURED), sink.drops);
        assertTrue(sink.audiences.isEmpty());
    }

    @Test
    void aGlobalChannelReachesEveryOnlinePlayerIncludingTheSender() throws Exception {
        ChannelService service = service(global(""));
        ChatTestHarness.FakePlayer steve = join("Steve");
        ChatTestHarness.FakePlayer alex = join("Alex");
        RecordingSink sink = new RecordingSink();

        assertTrue(service.dispatch(steve.proxy, "hello", sink));

        assertEquals(List.of(steve.proxy, alex.proxy), sink.audiences.getFirst());
    }

    @Test
    void aViewerWhoCannotSeeTheSenderIsNotInTheAudience() throws Exception {
        ChannelService service = service(global(""));
        ChatTestHarness.FakePlayer steve = join("Steve");
        ChatTestHarness.FakePlayer alex = join("Alex");
        alex.hide(steve);
        RecordingSink sink = new RecordingSink();

        service.dispatch(steve.proxy, "hello", sink);

        assertEquals(List.of(steve.proxy), sink.audiences.getFirst());
    }

    @Test
    void aRadiusChannelOnlyReachesPlayersInsideIt() throws Exception {
        ChannelService service = service("""
            {"schemaVersion":1,"revision":1,
             "channel":{"name":"local","default":true,"scope":"radius","radius":10},
             "format":"&f{{ sender.name }}&8: &f{{ message }}"}
            """);
        ChatTestHarness.FakePlayer steve = join("Steve", 0.0D);
        ChatTestHarness.FakePlayer near = join("Near", 5.0D);
        join("Far", 500.0D);
        RecordingSink sink = new RecordingSink();

        service.dispatch(steve.proxy, "hello", sink);

        assertEquals(List.of(steve.proxy, near.proxy), sink.audiences.getFirst());
    }

    @Test
    void aPermissionChannelOnlyReachesHolders() throws Exception {
        ChannelService service = service("""
            {"schemaVersion":1,"revision":1,
             "channel":{"name":"staff","default":true,"scope":"permission","permission":"server.staff"},
             "format":"&f{{ sender.name }}&8: &f{{ message }}"}
            """);
        ChatTestHarness.FakePlayer steve = join("Steve").allow("server.staff");
        ChatTestHarness.FakePlayer alex = join("Alex").allow("server.staff");
        join("Guest");
        RecordingSink sink = new RecordingSink();

        service.dispatch(steve.proxy, "hello", sink);

        assertEquals(List.of(steve.proxy, alex.proxy), sink.audiences.getFirst());
    }

    @Test
    void theDefaultChannelIsTheMarkedOneAndAliasesResolve() throws Exception {
        write("global", global(""));
        write("staff", """
            {"schemaVersion":1,"revision":1,
             "channel":{"name":"staff","aliases":["s"],"scope":"global","priority":-50},
             "format":"&c{{ message }}"}
            """);
        ChannelService service = load();

        assertEquals("global", service.defaultChannel().id());
        assertEquals("staff", service.channelFor("s").id());
        assertEquals("staff", service.channelFor("STAFF").id());
        assertNull(service.channelFor("nope"));
    }

    @Test
    void aDuplicateAliasRefusesTheLaterDocumentById() throws Exception {
        write("alpha", """
            {"schemaVersion":1,"revision":1,"channel":{"name":"alpha","aliases":["g"],"default":true},
             "format":"&f{{ message }}"}
            """);
        write("beta", """
            {"schemaVersion":1,"revision":1,"channel":{"name":"beta","aliases":["g"]},
             "format":"&f{{ message }}"}
            """);
        ChannelService service = load();

        assertNotNull(service.channelFor("alpha"));
        assertNull(service.channelFor("beta"));
        assertEquals("alpha", service.channelFor("g").id());
    }

    @Test
    void aPlayerChoiceOverridesTheDefaultUntilTheChannelDisappears() throws Exception {
        write("global", global(""));
        write("staff", """
            {"schemaVersion":1,"revision":1,"channel":{"name":"staff","scope":"global"},
             "format":"&c{{ message }}"}
            """);
        ChannelService service = load();
        ChatTestHarness.FakePlayer steve = join("Steve");

        assertTrue(service.selectChannel(steve.proxy, "staff"));
        assertEquals("staff", service.activeChannel(steve.proxy).id());
        assertFalse(service.selectChannel(steve.proxy, "nope"));
        assertEquals("staff", service.activeChannel(steve.proxy).id());
    }

    @Test
    void aDisabledModuleLeavesTheEngineInactiveAndDispatchesNothing() throws Exception {
        write("global", global(""));
        Gloss gloss = ChatTestHarness.gloss(dataFolder.toFile(), online);
        CharacterizationSupport.setField(gloss, "config", ChatTestHarness.configWithChannels(false));
        previousInstance = CharacterizationSupport.installGloss(gloss);
        installed = true;
        ChannelService service = new ChannelService(gloss);
        service.enable();
        ChatTestHarness.FakePlayer steve = join("Steve");
        RecordingSink sink = new RecordingSink();

        assertFalse(service.active());
        assertFalse(service.dispatch(steve.proxy, "hello", sink));
        assertEquals(List.of(ChatDrop.NO_CHANNEL), sink.drops);
    }

    @Test
    void theShippedPrivateChannelLoadsAlongsideTheAuthoredOnes() throws Exception {
        ChannelService service = service(global(""));

        assertNotNull(service.channelFor("private"));
        assertEquals(ChannelDoc.Scope.DIRECT, service.channelFor("private").scope());
    }

    private static String global(String extra) {
        return "{\"schemaVersion\":1,\"revision\":1,"
            + "\"channel\":{\"name\":\"global\",\"aliases\":[\"g\"],\"default\":true,\"scope\":\"global\"},"
            + extra
            + "\"format\":\"&f{{ sender.name }}&8: &f{{ message }}\"}";
    }

    private ChannelService service(String globalJson) throws Exception {
        write("global", globalJson);
        return load();
    }

    private ChannelService load() throws ReflectiveOperationException {
        Gloss gloss = ChatTestHarness.gloss(dataFolder.toFile(), online);
        previousInstance = CharacterizationSupport.installGloss(gloss);
        installed = true;
        ChannelService service = new ChannelService(gloss);
        service.enable();
        return service;
    }

    private void write(String id, String json) throws IOException {
        Path folder = dataFolder.resolve(ChannelDoc.KIND);
        Files.createDirectories(folder);
        Files.writeString(folder.resolve(id + ".json"), json);
    }

    private ChatTestHarness.FakePlayer join(String name) {
        return join(name, 0.0D);
    }

    private ChatTestHarness.FakePlayer join(String name, double x) {
        ChatTestHarness.FakePlayer player = ChatTestHarness.player(name, overworld, x);
        online.add(player.proxy);
        return player;
    }

    private static final class RecordingSink implements ChatSink {
        private final List<ChatDrop> drops = new ArrayList<>();
        private final List<List<Player>> audiences = new ArrayList<>();

        @Override
        public void dropped(ChatDrop reason) {
            drops.add(reason);
        }

        @Override
        public void audience(ChannelRuntime channel, String message, List<Player> viewers) {
            audiences.add(viewers);
        }
    }
}
