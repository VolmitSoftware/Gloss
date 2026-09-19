package art.arcane.gloss.behavior;

import art.arcane.gloss.config.action.AddStateActionData;
import art.arcane.gloss.config.action.BroadcastActionData;
import art.arcane.gloss.config.action.CommandActionData;
import art.arcane.gloss.config.action.DelayActionData;
import art.arcane.gloss.doc.ShippedDocumentCatalog;
import art.arcane.gloss.state.StateScope;
import art.arcane.gloss.state.StateSchema;
import art.arcane.gloss.state.StateType;
import org.junit.jupiter.api.Test;

import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class BehaviorDocTest {
    private static final String HEAD = "{\"schemaVersion\":1,\"revision\":1,";

    private static BehaviorDoc parse(String body) {
        return BehaviorDoc.parse("test.json", HEAD + body + "}");
    }

    private static String refused(String body) {
        IllegalArgumentException failure = assertThrows(IllegalArgumentException.class, () -> parse(body));
        return failure.getMessage();
    }

    @Test
    void minimalDocumentDefaultsToEnabledWithoutServerCommands() {
        BehaviorDoc doc = parse("\"on\":[]");

        assertTrue(doc.enabled());
        assertFalse(doc.allowServerCommands());
        assertTrue(doc.on().isEmpty());
        assertTrue(doc.stateSchemas().isEmpty());
        assertEquals("behaviors", BehaviorDoc.KIND);
        assertThrows(IllegalArgumentException.class, () -> BehaviorDoc.parse("test.json", "{\"schemaVersion\":2,\"revision\":1}"));
    }

    @Test
    void everyTriggerParsesWithItsOwnOptions() {
        BehaviorDoc doc = parse("\"on\":["
            + "{\"trigger\":\"join\",\"do\":[]},"
            + "{\"trigger\":\"first_join\",\"do\":[]},"
            + "{\"trigger\":\"quit\",\"do\":[]},"
            + "{\"trigger\":\"respawn\",\"do\":[]},"
            + "{\"trigger\":\"death\",\"do\":[]},"
            + "{\"trigger\":\"kill\",\"when\":\"source.type == 'player'\",\"do\":[]},"
            + "{\"trigger\":\"damage\",\"do\":[]},"
            + "{\"trigger\":\"chat\",\"pattern\":\"^!help\",\"do\":[]},"
            + "{\"trigger\":\"command\",\"name\":\"daily\",\"permission\":\"quests.daily\",\"do\":[]},"
            + "{\"trigger\":\"block_break\",\"material\":\"DIAMOND_ORE\",\"do\":[]},"
            + "{\"trigger\":\"block_place\",\"do\":[]},"
            + "{\"trigger\":\"pickup\",\"material\":\"minecraft:apple\",\"do\":[]},"
            + "{\"trigger\":\"drop\",\"do\":[]},"
            + "{\"trigger\":\"world_change\",\"do\":[]},"
            + "{\"trigger\":\"region_enter\",\"region\":\"spawn\",\"do\":[]},"
            + "{\"trigger\":\"region_leave\",\"region\":\"spawn\",\"do\":[]},"
            + "{\"trigger\":\"menu_open\",\"menu\":\"shop\",\"do\":[]},"
            + "{\"trigger\":\"menu_close\",\"do\":[]},"
            + "{\"trigger\":\"menu_click\",\"menu\":\"shop\",\"component\":\"buy\",\"do\":[]},"
            + "{\"trigger\":\"inventory_click\",\"do\":[]},"
            + "{\"trigger\":\"interval\",\"everyTicks\":1200,\"scope\":\"global\",\"do\":[]},"
            + "{\"trigger\":\"interval\",\"everyTicks\":20,\"do\":[]},"
            + "{\"trigger\":\"server_start\",\"do\":[]},"
            + "{\"trigger\":\"emit\",\"name\":\"quest.complete\",\"do\":[]}"
            + "]");

        assertEquals(BehaviorTrigger.values().length + 1, doc.on().size());
        assertEquals(BehaviorTrigger.KILL, doc.on().get(5).trigger());
        assertEquals("source.type == 'player'", doc.on().get(5).when());
        assertEquals("quests.daily", doc.on().get(8).permission());
        assertEquals("daily", doc.on().get(8).name());
        assertEquals("minecraft:diamond_ore", doc.on().get(9).material());
        assertEquals("minecraft:apple", doc.on().get(11).material());
        assertEquals("spawn", doc.on().get(14).region());
        assertEquals("buy", doc.on().get(18).component());
        assertEquals(1200, doc.on().get(20).everyTicks());
        assertTrue(doc.on().get(20).globalScope());
        assertFalse(doc.on().get(21).globalScope());
        assertEquals("quest.complete", doc.on().get(23).name());
        assertNull(doc.on().get(0).when());
        assertNull(doc.on().get(0).permission());
    }

    @Test
    void unknownTriggersAndMissingOrForeignOptionsAreRefusedWithThePath() {
        assertTrue(refused("\"on\":[{\"trigger\":\"teleport\",\"do\":[]}]").contains("on[0]"));
        assertTrue(refused("\"on\":[{\"do\":[]}]").contains("on[0]"));
        assertTrue(refused("\"on\":[{\"trigger\":\"join\",\"do\":[]},{\"trigger\":\"region_enter\",\"do\":[]}]").contains("on[1]"));
        assertTrue(refused("\"on\":[{\"trigger\":\"interval\",\"do\":[]}]").contains("everyTicks"));
        assertTrue(refused("\"on\":[{\"trigger\":\"interval\",\"everyTicks\":0,\"do\":[]}]").contains("everyTicks"));
        assertTrue(refused("\"on\":[{\"trigger\":\"interval\",\"everyTicks\":20,\"scope\":\"server\",\"do\":[]}]").contains("scope"));
        assertTrue(refused("\"on\":[{\"trigger\":\"command\",\"do\":[]}]").contains("name"));
        assertTrue(refused("\"on\":[{\"trigger\":\"emit\",\"do\":[]}]").contains("name"));
        assertTrue(refused("\"on\":[{\"trigger\":\"join\",\"region\":\"spawn\",\"do\":[]}]").contains("region"));
        assertTrue(refused("\"on\":[{\"trigger\":\"chat\",\"pattern\":\"[\",\"do\":[]}]").contains("pattern"));
        assertTrue(refused("\"on\":[{\"trigger\":\"join\",\"when\":\"viewer.level >\",\"do\":[]}]").contains("when"));
        assertTrue(refused("\"on\":[{\"trigger\":\"join\"}]").contains("do"));
        assertTrue(refused("\"on\":[null]").contains("on[0]"));
    }

    @Test
    void serverCommandsNeedTheRootFlagAndTheRefusalNamesTheAction() {
        String entries = "\"on\":[{\"trigger\":\"join\",\"do\":["
            + "{\"type\":\"message\",\"message\":\"hi\"},"
            + "{\"type\":\"command\",\"source\":\"server\",\"command\":\"give %player% diamond\"}]}]";

        String reason = refused(entries);
        assertTrue(reason.contains("on[0].do[1]"), reason);
        assertTrue(reason.contains("allowServerCommands"), reason);

        BehaviorDoc allowed = parse("\"allowServerCommands\":true," + entries);
        assertTrue(allowed.allowServerCommands());
        assertInstanceOf(CommandActionData.class, allowed.on().getFirst().actions().get(1));

        BehaviorDoc playerSource = parse("\"on\":[{\"trigger\":\"join\",\"do\":["
            + "{\"type\":\"command\",\"command\":\"spawn\"}]}]");
        assertInstanceOf(CommandActionData.class, playerSource.on().getFirst().actions().getFirst());
    }

    @Test
    void stateDeclarationsCarryScopeTypeAndCoercedDefaults() {
        BehaviorDoc doc = parse("\"state\":{"
            + "\"welcomed\":{\"scope\":\"player\",\"type\":\"boolean\",\"default\":false},"
            + "\"visits\":{\"scope\":\"player\",\"type\":\"number\"},"
            + "\"event\":{\"scope\":\"global\",\"type\":\"string\",\"default\":\"none\"}},"
            + "\"on\":[]");

        List<StateSchema> schemas = doc.stateSchemas();
        assertEquals(3, schemas.size());
        Map<String, StateSchema> byKey = Map.of(schemas.get(0).key(), schemas.get(0), schemas.get(1).key(), schemas.get(1),
            schemas.get(2).key(), schemas.get(2));
        assertEquals(StateScope.PLAYER, byKey.get("welcomed").scope());
        assertEquals(StateType.BOOLEAN, byKey.get("welcomed").type());
        assertEquals(0.0D, byKey.get("visits").defaultValue());
        assertEquals("none", byKey.get("event").defaultValue());

        assertTrue(refused("\"state\":{\"visits\":{\"scope\":\"player\",\"type\":\"list\"}},\"on\":[]").contains("visits"));
        assertTrue(refused("\"state\":{\"Visits\":{\"scope\":\"player\",\"type\":\"number\"}},\"on\":[]").contains("Visits"));
        assertTrue(refused("\"state\":{\"visits\":{\"type\":\"number\"}},\"on\":[]").contains("scope"));
        assertTrue(refused("\"state\":{\"visits\":{\"scope\":\"player\",\"type\":\"number\",\"default\":\"lots\"}},\"on\":[]").contains("visits"));
    }

    @Test
    void runtimeCompilesConditionsPatternsAndActionsPerEntry() {
        BehaviorDoc doc = parse("\"on\":["
            + "{\"trigger\":\"chat\",\"pattern\":\"^!(\\\\w+)\",\"when\":\"viewer.level > 3\",\"do\":[{\"type\":\"message\",\"message\":\"hi\"}]},"
            + "{\"trigger\":\"join\",\"do\":[]}]");

        BehaviorRuntime runtime = BehaviorRuntime.compile("greeter", doc);

        assertEquals("greeter", runtime.id());
        assertEquals(2, runtime.entries().size());
        BehaviorRuntime.CompiledEntry chat = runtime.entries().getFirst();
        assertEquals(0, chat.index());
        assertEquals(BehaviorTrigger.CHAT, chat.entry().trigger());
        assertNotNull(chat.when());
        assertEquals("behaviors/greeter.on[0].when", chat.when().source().path());
        assertTrue(chat.pattern().matcher("!help").find());
        assertEquals(1, chat.actions().size());
        assertNull(runtime.entries().get(1).when());
        assertNull(runtime.entries().get(1).pattern());
    }

    @Test
    void shippedWelcomeDocumentIsCatalogedAndShipsDisabled() throws Exception {
        assertTrue(ShippedDocumentCatalog.all().stream().anyMatch(entry -> entry.kind().equals(BehaviorDoc.KIND)));
        try (InputStream input = getClass().getResourceAsStream("/defaults/behaviors/welcome.json")) {
            assertNotNull(input);
            BehaviorDoc welcome = BehaviorDoc.parse("welcome.json", new String(input.readAllBytes(), StandardCharsets.UTF_8));
            assertFalse(welcome.enabled());
            assertFalse(welcome.allowServerCommands());
            assertEquals(2, welcome.on().size());
            assertInstanceOf(DelayActionData.class, welcome.on().getFirst().actions().getFirst());
            assertInstanceOf(AddStateActionData.class, welcome.on().getFirst().actions().getLast());
            assertInstanceOf(BroadcastActionData.class, welcome.on().get(1).actions().getFirst());
            assertEquals(3, welcome.stateSchemas().size());
        }
    }
}
