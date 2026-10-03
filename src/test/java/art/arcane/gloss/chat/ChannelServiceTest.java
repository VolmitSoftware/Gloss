package art.arcane.gloss.chat;

import art.arcane.gloss.Gloss;
import art.arcane.gloss.menu.CharacterizationSupport;
import org.bukkit.World;
import org.bukkit.Server;
import org.bukkit.scheduler.BukkitScheduler;
import org.bukkit.scheduler.BukkitTask;
import java.lang.reflect.Proxy;
import java.util.concurrent.atomic.AtomicBoolean;
import org.bukkit.Material;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import java.util.Map;
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
    void eachMessageCapturesTheCurrentHeldStackOnceWithoutSlotChanges() throws Exception {
        ChannelService service = service(global("\"throttle\":{\"minIntervalTicks\":0},"));
        ChatTestHarness.FakePlayer steve = join("Steve");
        RecordingSink sink = new RecordingSink();
        Object previous = CharacterizationSupport.installServer(CharacterizationSupport.server(Map.of()));
        try {
            steve.heldItem = new HeldStack(Material.DIAMOND_SWORD, 1);
            assertTrue(service.dispatch(steve.proxy, "First [item]", sink));
            ChatContext first = sink.dispatches.getFirst().context();
            assertEquals("minecraft:diamond_sword", first.item().id());
            steve.heldItem = new HeldStack(Material.OAK_LOG, 32);
            assertTrue(service.dispatch(steve.proxy, "Second [item]", sink));
            assertEquals("minecraft:oak_log", sink.dispatches.getLast().context().item().id());
            assertEquals(32, sink.dispatches.getLast().context().item().amount());
            assertEquals("minecraft:diamond_sword", first.item().id());
            assertEquals(2, steve.inventoryReads);
            assertTrue(service.dispatch(steve.proxy, "No item token", sink));
            assertEquals(2, steve.inventoryReads);
        } finally {
            CharacterizationSupport.restoreServer(previous);
        }
    }

    @Test
    void deferredItemMessagesReturnBeforeOwnershipTransferAndStopOnRetirementOrShutdown() throws Exception {
        ChannelService service = service(global("\"throttle\":{\"minIntervalTicks\":0},"));
        Gloss plugin = (Gloss) CharacterizationSupport.getField(service, "plugin");
        CharacterizationSupport.setField(plugin, "isEnabled", true);
        ChatTestHarness.FakePlayer steve = join("Steve");
        steve.heldItem = new HeldStack(Material.DIAMOND_SWORD, 1);
        List<Runnable> queued = new ArrayList<>();
        AtomicBoolean owned = new AtomicBoolean(false);
        Object previous = CharacterizationSupport.installServer(scheduledServer(queued, owned));
        try {
            RecordingSink sink = new RecordingSink();
            service.dispatchDeferred(steve.proxy, "First [item]", sink);
            assertTrue(sink.audiences.isEmpty());
            assertEquals(0, steve.inventoryReads);
            assertEquals(1, queued.size());
            owned.set(true);
            queued.removeFirst().run();
            assertEquals(1, sink.audiences.size());
            assertEquals("minecraft:diamond_sword", sink.dispatches.getFirst().context().item().id());
            assertEquals(1, steve.inventoryReads);
            owned.set(false);
            service.dispatchDeferred(steve.proxy, "Retired [item]", sink);
            steve.online = false;
            owned.set(true);
            queued.removeFirst().run();
            assertEquals(1, sink.audiences.size());
            assertEquals(1, steve.inventoryReads);
            steve.online = true;
            owned.set(false);
            service.dispatchDeferred(steve.proxy, "Stopped [item]", sink);
            CharacterizationSupport.setField(plugin, "isEnabled", false);
            owned.set(true);
            queued.removeFirst().run();
            assertEquals(1, sink.audiences.size());
            assertEquals(1, steve.inventoryReads);
            assertEquals(List.of(ChatDrop.NO_CHANNEL, ChatDrop.NO_CHANNEL), sink.drops);
        } finally {
            CharacterizationSupport.restoreServer(previous);
            CharacterizationSupport.setField(plugin, "isEnabled", false);
        }
    }

    private static Server scheduledServer(List<Runnable> queued, AtomicBoolean owned) {
        Server base = CharacterizationSupport.server(Map.of());
        BukkitTask task = (BukkitTask) Proxy.newProxyInstance(BukkitTask.class.getClassLoader(),
            new Class<?>[]{BukkitTask.class}, (proxy, method, args) -> null);
        BukkitScheduler scheduler = (BukkitScheduler) Proxy.newProxyInstance(BukkitScheduler.class.getClassLoader(),
            new Class<?>[]{BukkitScheduler.class}, (proxy, method, args) -> {
                if (method.getName().equals("runTask")) {
                    queued.add((Runnable) args[1]);
                    return task;
                }
                throw new UnsupportedOperationException(method.getName());
            });
        return (Server) Proxy.newProxyInstance(Server.class.getClassLoader(), new Class<?>[]{Server.class},
            (proxy, method, args) -> switch (method.getName()) {
                case "isPrimaryThread", "isOwnedByCurrentRegion" -> owned.get();
                case "getScheduler" -> scheduler;
                default -> method.invoke(base, args);
            });
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
    void configReloadCanEnableDisableAndReenableChannels() throws Exception {
        write("global", global(""));
        Gloss gloss = ChatTestHarness.gloss(dataFolder.toFile(), online);
        CharacterizationSupport.setField(gloss, "config", ChatTestHarness.configWithChannels(false));
        previousInstance = CharacterizationSupport.installGloss(gloss);
        installed = true;
        ChannelService service = new ChannelService(gloss);
        service.enable();
        assertFalse(service.active());

        CharacterizationSupport.setField(gloss, "config", ChatTestHarness.configWithChannels(true));
        service.reload();
        assertTrue(service.active());
        assertNotNull(service.channelFor("global"));

        CharacterizationSupport.setField(gloss, "config", ChatTestHarness.configWithChannels(false));
        service.reload();
        assertFalse(service.active());
        assertTrue(service.channels().isEmpty());

        CharacterizationSupport.setField(gloss, "config", ChatTestHarness.configWithChannels(true));
        service.reload();
        assertTrue(service.active());
        assertNotNull(service.channelFor("global"));
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

    private static final class HeldStack extends ItemStack {
        private final Material type;
        private final int amount;

        private HeldStack(Material type, int amount) {
            this.type = type;
            this.amount = amount;
        }

        @Override
        public Material getType() {
            return type;
        }

        @Override
        public int getAmount() {
            return amount;
        }

        @Override
        public ItemMeta getItemMeta() {
            return null;
        }
    }

    private static final class RecordingSink implements ChatSink {
        private final List<ChatDrop> drops = new ArrayList<>();
        private final List<List<Player>> audiences = new ArrayList<>();
        private final List<ChatDispatch> dispatches = new ArrayList<>();

        @Override
        public void dropped(ChatDrop reason) {
            drops.add(reason);
        }

        @Override
        public void audience(ChatDispatch dispatch) {
            audiences.add(dispatch.viewers());
            dispatches.add(dispatch);
        }
    }
}
