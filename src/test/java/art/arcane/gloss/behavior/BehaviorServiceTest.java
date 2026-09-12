package art.arcane.gloss.behavior;

import art.arcane.gloss.state.StateSchema;
import art.arcane.gloss.state.StateScope;
import art.arcane.gloss.state.StateStore;
import art.arcane.gloss.state.StateStores;
import art.arcane.gloss.state.StateType;
import org.bukkit.World;
import org.bukkit.entity.Player;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class BehaviorServiceTest {
    private static final String HEAD = "{\"schemaVersion\":1,\"revision\":1,";
    private static final String STATE = "\"state\":{\"visits\":{\"scope\":\"player\",\"type\":\"number\"},"
        + "\"tag\":{\"scope\":\"player\",\"type\":\"string\"}},";
    private static final String ADD = "{\"type\":\"addState\",\"key\":\"visits\",\"value\":\"1\"}";

    @TempDir
    Path folder;

    private final World world = BehaviorTestSupport.world("survival");
    private final Player alice = BehaviorTestSupport.player("alice", Set.of("quests.daily"), world, 5.0D);
    private final Player bob = BehaviorTestSupport.player("bob", Set.of(), world, 1.0D);
    private StateStore store;

    @BeforeEach
    void setUp() {
        store = new StateStore(folder, Runnable::run, (player, task) -> task.run());
        store.beginLoad(alice.getUniqueId());
        store.beginLoad(bob.getUniqueId());
        StateStores.install(store);
    }

    @AfterEach
    void cleanUp() {
        StateStores.install(null);
    }

    private static BehaviorDoc doc(String body) {
        return BehaviorDoc.parse("test.json", HEAD + body + "}");
    }

    private BehaviorSubscriptions compile(Map<String, BehaviorDoc> documents) {
        return BehaviorSubscriptions.compile(documents, store);
    }

    private double visits(Player player) {
        return (Double) store.get(StateScope.PLAYER, player.getUniqueId(), "visits");
    }

    private void fire(BehaviorSubscriptions subscriptions, BehaviorTrigger trigger, TriggerEvent event) {
        BehaviorDispatcher.fire(subscriptions, trigger, event, BehaviorTestSupport.contexts());
    }

    @Test
    void optionsFilterWhichEntriesAnEventReaches() {
        BehaviorSubscriptions subscriptions = compile(Map.of("mine", doc(STATE + "\"on\":["
            + "{\"trigger\":\"block_break\",\"material\":\"diamond_ore\",\"do\":[" + ADD + "]},"
            + "{\"trigger\":\"block_break\",\"do\":[" + ADD + "]},"
            + "{\"trigger\":\"chat\",\"pattern\":\"^!hi\",\"do\":[" + ADD + "]},"
            + "{\"trigger\":\"command\",\"name\":\"daily\",\"do\":[" + ADD + "]},"
            + "{\"trigger\":\"menu_click\",\"menu\":\"shop\",\"component\":\"buy\",\"do\":[" + ADD + "]},"
            + "{\"trigger\":\"region_enter\",\"region\":\"Spawn\",\"do\":[" + ADD + "]},"
            + "{\"trigger\":\"emit\",\"name\":\"quest.complete\",\"do\":[" + ADD + "]}]")));

        fire(subscriptions, BehaviorTrigger.BLOCK_BREAK, TriggerEvent.block(alice, "minecraft:diamond_ore", alice.getLocation()));
        assertEquals(2.0D, visits(alice));
        fire(subscriptions, BehaviorTrigger.BLOCK_BREAK, TriggerEvent.block(alice, "minecraft:stone", alice.getLocation()));
        assertEquals(3.0D, visits(alice));
        fire(subscriptions, BehaviorTrigger.CHAT, TriggerEvent.chat(alice, "!hi there"));
        fire(subscriptions, BehaviorTrigger.CHAT, TriggerEvent.chat(alice, "hello"));
        assertEquals(4.0D, visits(alice));
        fire(subscriptions, BehaviorTrigger.COMMAND, TriggerEvent.command(alice, "daily", Map.of()));
        fire(subscriptions, BehaviorTrigger.COMMAND, TriggerEvent.command(alice, "weekly", Map.of()));
        assertEquals(5.0D, visits(alice));
        fire(subscriptions, BehaviorTrigger.MENU_CLICK, TriggerEvent.menu(alice, "shop", "buy"));
        fire(subscriptions, BehaviorTrigger.MENU_CLICK, TriggerEvent.menu(alice, "shop", "sell"));
        fire(subscriptions, BehaviorTrigger.MENU_CLICK, TriggerEvent.menu(alice, "bank", "buy"));
        assertEquals(6.0D, visits(alice));
        fire(subscriptions, BehaviorTrigger.REGION_ENTER, TriggerEvent.region(alice, "spawn"));
        fire(subscriptions, BehaviorTrigger.REGION_ENTER, TriggerEvent.region(alice, "arena"));
        assertEquals(7.0D, visits(alice));
        fire(subscriptions, BehaviorTrigger.EMIT, TriggerEvent.emit(alice, "quest.complete", Map.of()));
        fire(subscriptions, BehaviorTrigger.EMIT, TriggerEvent.emit(alice, "quest.start", Map.of()));
        assertEquals(8.0D, visits(alice));
        assertTrue(subscriptions.hasAny(BehaviorTrigger.REGION_ENTER));
        assertFalse(subscriptions.hasAny(BehaviorTrigger.JOIN));
    }

    @Test
    void permissionAndWhenGateEachEntry() {
        BehaviorSubscriptions subscriptions = compile(Map.of("gates", doc(STATE + "\"on\":["
            + "{\"trigger\":\"join\",\"permission\":\"quests.daily\",\"do\":[" + ADD + "]},"
            + "{\"trigger\":\"join\",\"when\":\"level > 3\",\"do\":[" + ADD + "]},"
            + "{\"trigger\":\"join\",\"when\":\"args.first == true\",\"do\":[" + ADD + "]}]")));

        fire(subscriptions, BehaviorTrigger.JOIN, TriggerEvent.viewer(alice));
        fire(subscriptions, BehaviorTrigger.JOIN, TriggerEvent.viewer(bob));
        fire(subscriptions, BehaviorTrigger.JOIN, TriggerEvent.viewer(bob).withArgs(Map.of("first", true)));

        assertEquals(2.0D, visits(alice));
        assertEquals(1.0D, visits(bob));
    }

    @Test
    void disabledDocumentsLoadButNeverFire() {
        BehaviorSubscriptions subscriptions = compile(Map.of(
            "off", doc("\"enabled\":false," + STATE + "\"on\":[{\"trigger\":\"join\",\"do\":[" + ADD + "]}]"),
            "on", doc(STATE + "\"on\":[{\"trigger\":\"join\",\"do\":[" + ADD + "]}]")));

        fire(subscriptions, BehaviorTrigger.JOIN, TriggerEvent.viewer(alice));

        assertEquals(1.0D, visits(alice));
        assertEquals(Set.of("visits", "tag"), store.declarations().keys());
        assertEquals(1, subscriptions.subscribed(BehaviorTrigger.JOIN).size());
        assertTrue(subscriptions.refused().isEmpty());
        assertEquals(2, subscriptions.runtimes().size());
    }

    @Test
    void aConflictingDeclarationRefusesTheLaterDocumentAndKeepsTheFirst() {
        BehaviorSubscriptions subscriptions = compile(Map.of(
            "alpha", doc(STATE + "\"on\":[{\"trigger\":\"join\",\"do\":[" + ADD + "]}]"),
            "beta", doc("\"state\":{\"visits\":{\"scope\":\"global\",\"type\":\"number\"}},"
                + "\"on\":[{\"trigger\":\"join\",\"do\":[{\"type\":\"setState\",\"key\":\"tag\",\"value\":\"'x'\"}]}]")));

        fire(subscriptions, BehaviorTrigger.JOIN, TriggerEvent.viewer(alice));

        assertEquals(1.0D, visits(alice));
        assertEquals("", store.get(StateScope.PLAYER, alice.getUniqueId(), "tag"));
        assertEquals(Set.of("beta"), subscriptions.refused().keySet());
        assertTrue(subscriptions.refused().get("beta").contains("visits"));
        assertEquals(StateScope.PLAYER, store.declarations().get("visits").scope());
    }

    @Test
    void aDocumentThatCollidesWithASceneOnceKeyIsRefusedInsteadOfAbortingTheReload() {
        store.ensureDeclared("sequence:once:streak",
            new StateSchema("streak", StateScope.PLAYER, StateType.BOOLEAN, false));

        BehaviorSubscriptions subscriptions = compile(Map.of(
            "stats", doc("\"state\":{\"streak\":{\"scope\":\"global\",\"type\":\"number\"}},"
                + "\"on\":[{\"trigger\":\"join\",\"do\":[]}]")));

        assertEquals(Set.of("stats"), subscriptions.refused().keySet());
        assertTrue(subscriptions.refused().get("stats").contains("streak"));
        assertEquals(StateScope.PLAYER, store.declarations().get("streak").scope(),
            "the running scene's declaration survives the refused document");
    }

    @Test
    void intervalsFireGlobalEntriesOnTheirTickAndPlayerEntriesPerOnlinePlayer() {
        BehaviorSubscriptions subscriptions = compile(Map.of("timers", doc(STATE + "\"on\":["
            + "{\"trigger\":\"interval\",\"everyTicks\":2,\"do\":[" + ADD + "]},"
            + "{\"trigger\":\"interval\",\"everyTicks\":3,\"scope\":\"global\",\"do\":["
            + "{\"type\":\"setState\",\"key\":\"tag\",\"value\":\"'never'\"}]}]")));
        List<String> globalRuns = new ArrayList<>();
        IntervalScheduler scheduler = new IntervalScheduler(() -> List.of(alice, bob), (player, task) -> task.run(),
            (subscription, event) -> {
                if (event.viewer() == null) {
                    globalRuns.add(subscription.entry().componentId());
                } else {
                    BehaviorDispatcher.run(subscription, event, BehaviorTestSupport.contexts());
                }
            });
        scheduler.rebuild(subscriptions);

        for (int tick = 0; tick < 6; tick++) {
            scheduler.tick();
        }

        assertEquals(3.0D, visits(alice));
        assertEquals(3.0D, visits(bob));
        assertEquals(List.of("on:1", "on:1"), globalRuns);
    }
}
