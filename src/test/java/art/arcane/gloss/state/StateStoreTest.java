package art.arcane.gloss.state;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class StateStoreTest {
    private static final UUID PLAYER = UUID.nameUUIDFromBytes("player".getBytes());
    private static final UUID WORLD = UUID.nameUUIDFromBytes("world".getBytes());

    @TempDir
    Path folder;

    private StateStore store() {
        StateStore store = new StateStore(folder.resolve("state"), Runnable::run, (player, task) -> task.run());
        store.declare(StateTestSupport.declarationsByDocument());
        return store;
    }

    @Test
    void everyScopeReadsItsDeclaredDefaultUntilWrittenAndCoercesOnWrite() {
        StateStore store = store();
        store.beginLoad(PLAYER);

        assertEquals(0.0D, store.get(StateScope.PLAYER, PLAYER, "visits"));
        assertEquals(false, store.get(StateScope.PLAYER, PLAYER, "welcomed"));
        assertEquals("clear", store.get(StateScope.WORLD, WORLD, "weather"));
        assertEquals("none", store.get(StateScope.GLOBAL, null, "event"));
        assertNull(store.get(StateScope.PLAYER, PLAYER, "unknown"));

        store.set(StateScope.PLAYER, PLAYER, "visits", "5");
        store.set(StateScope.PLAYER, PLAYER, "welcomed", "true");
        store.set(StateScope.WORLD, WORLD, "weather", 12.0D);
        store.set(StateScope.GLOBAL, null, "event", "spring");

        assertEquals(5.0D, store.get(StateScope.PLAYER, PLAYER, "visits"));
        assertEquals(true, store.get(StateScope.PLAYER, PLAYER, "welcomed"));
        assertEquals("12", store.get(StateScope.WORLD, WORLD, "weather"));
        assertEquals("spring", store.get(StateScope.GLOBAL, null, "event"));
        assertThrows(IllegalArgumentException.class, () -> store.set(StateScope.PLAYER, PLAYER, "unknown", 1));
        assertThrows(IllegalArgumentException.class, () -> store.set(StateScope.GLOBAL, null, "visits", 1));
    }

    @Test
    void addOnlyAppliesToNumbersAndClearRestoresTheDefault() {
        StateStore store = store();
        store.beginLoad(PLAYER);

        store.add(StateScope.PLAYER, PLAYER, "visits", 2.5D);
        store.add(StateScope.PLAYER, PLAYER, "visits", 1.0D);
        assertEquals(3.5D, store.get(StateScope.PLAYER, PLAYER, "visits"));
        assertThrows(IllegalArgumentException.class, () -> store.add(StateScope.PLAYER, PLAYER, "welcomed", 1.0D));

        store.clear(StateScope.PLAYER, PLAYER, "visits");
        assertEquals(0.0D, store.get(StateScope.PLAYER, PLAYER, "visits"));
    }

    @Test
    void sectionsRoundTripBesideStateValuesInThePlayerFile() {
        StateStore store = store();
        store.beginLoad(PLAYER);

        assertTrue(store.section(PLAYER, "markers").isEmpty());
        store.writeSection(PLAYER, "markers", Map.of("home", Map.of("x", 1.0D)));
        store.set(StateScope.PLAYER, PLAYER, "visits", 1);
        store.flush();

        StateStore reloaded = store();
        reloaded.beginLoad(PLAYER);
        assertEquals(1.0D, ((Map<?, ?>) reloaded.section(PLAYER, "markers").get("home")).get("x"));
        assertEquals(1.0D, reloaded.get(StateScope.PLAYER, PLAYER, "visits"));
    }

    @Test
    void flushWritesOnlyDirtyFilesAndNothingBeforeTheFirstWrite() throws IOException {
        StateStore store = store();
        store.beginLoad(PLAYER);
        store.flush();
        assertFalse(Files.exists(folder.resolve("state")));

        store.set(StateScope.GLOBAL, null, "event", "summer");
        store.flush();
        assertTrue(Files.isRegularFile(folder.resolve("state").resolve("global.json")));
        assertFalse(Files.exists(folder.resolve("state").resolve("players")));
        assertFalse(Files.exists(folder.resolve("state").resolve("worlds")));

        store.set(StateScope.WORLD, WORLD, "weather", "rain");
        store.set(StateScope.PLAYER, PLAYER, "visits", 2);
        store.flush();
        assertTrue(Files.isRegularFile(folder.resolve("state").resolve("worlds").resolve(WORLD + ".json")));
        assertTrue(Files.isRegularFile(folder.resolve("state").resolve("players").resolve(PLAYER + ".json")));

        assertEquals("summer", StateFiles.read(folder.resolve("state").resolve("global.json")).get("event"));
        assertEquals(2.0D, StateFiles.read(folder.resolve("state").resolve("players").resolve(PLAYER + ".json")).get("visits"));
    }

    @Test
    void writesBeforeThePlayerFileArrivesQueueAndApplyOverTheLoadedValues() {
        List<Runnable> ioQueue = new ArrayList<>();
        List<Runnable> regionQueue = new ArrayList<>();
        StateFiles.write(folder.resolve("state").resolve("players").resolve(PLAYER + ".json"),
            Map.of("visits", 7.0D, "welcomed", true));
        StateStore store = new StateStore(folder.resolve("state"), ioQueue::add, (player, task) -> regionQueue.add(task));
        store.declare(StateTestSupport.declarationsByDocument());

        store.beginLoad(PLAYER);
        assertEquals(0.0D, store.get(StateScope.PLAYER, PLAYER, "visits"));
        store.add(StateScope.PLAYER, PLAYER, "visits", 1.0D);
        store.set(StateScope.PLAYER, PLAYER, "welcomed", false);
        assertEquals(0.0D, store.get(StateScope.PLAYER, PLAYER, "visits"));

        ioQueue.forEach(Runnable::run);
        assertEquals(0.0D, store.get(StateScope.PLAYER, PLAYER, "visits"));
        regionQueue.forEach(Runnable::run);

        assertEquals(8.0D, store.get(StateScope.PLAYER, PLAYER, "visits"));
        assertEquals(false, store.get(StateScope.PLAYER, PLAYER, "welcomed"));
        assertTrue(store.loaded(PLAYER));
    }

    @Test
    void forgetFlushesThePlayerAndDropsItFromMemory() {
        StateStore store = store();
        store.beginLoad(PLAYER);
        store.set(StateScope.PLAYER, PLAYER, "visits", 4);

        store.forget(PLAYER);

        assertFalse(store.loaded(PLAYER));
        assertEquals(4.0D, StateFiles.read(folder.resolve("state").resolve("players").resolve(PLAYER + ".json")).get("visits"));
        assertEquals(0.0D, store.get(StateScope.PLAYER, PLAYER, "visits"));
    }

    @Test
    void aQuitBehaviorsWriteLandsBeforeTheStoreDropsThePlayer() {
        StateStore store = store();
        store.deferQuitForget(true);
        store.beginLoad(PLAYER);
        store.set(StateScope.PLAYER, PLAYER, "visits", 3);

        store.onPlayerQuit(PLAYER);
        store.add(StateScope.PLAYER, PLAYER, "visits", 1.0D);
        store.forget(PLAYER);

        assertEquals(4.0D, StateFiles.read(folder.resolve("state").resolve("players")
            .resolve(PLAYER + ".json")).get("visits"));
    }

    @Test
    void aWriteWithNoLoadInFlightIsRefusedInsteadOfQueuedForTheNextSession() {
        StateStore store = store();
        store.beginLoad(PLAYER);
        store.set(StateScope.PLAYER, PLAYER, "visits", 3);
        store.forget(PLAYER);

        store.set(StateScope.PLAYER, PLAYER, "visits", 99);
        store.beginLoad(PLAYER);

        assertEquals(3.0D, store.get(StateScope.PLAYER, PLAYER, "visits"),
            "a write made with no load in flight must not resurface on the next join");
    }

    @Test
    void aJoinWaiterRunsOnlyOnceThePlayersFileHasLanded() {
        List<Runnable> ioQueue = new ArrayList<>();
        List<Runnable> regionQueue = new ArrayList<>();
        StateFiles.write(folder.resolve("state").resolve("players").resolve(PLAYER + ".json"),
            Map.of("visits", 7.0D));
        StateStore store = new StateStore(folder.resolve("state"), ioQueue::add,
            (player, task) -> regionQueue.add(task));
        store.declare(StateTestSupport.declarationsByDocument());

        List<Object> seen = new ArrayList<>();
        store.beginLoad(PLAYER);
        store.whenLoaded(PLAYER, () -> seen.add(store.get(StateScope.PLAYER, PLAYER, "visits")));

        assertTrue(seen.isEmpty(), "a join gate must not read the declared default as a first visit");
        ioQueue.forEach(Runnable::run);
        regionQueue.forEach(Runnable::run);

        assertEquals(List.of(7.0D), seen);
    }

    @Test
    void aJoinWaiterRunsStraightAwayWhenThereIsNothingToWaitFor() {
        StateStore store = store();
        List<String> seen = new ArrayList<>();

        store.whenLoaded(PLAYER, () -> seen.add("unknown"));
        store.beginLoad(PLAYER);
        store.whenLoaded(PLAYER, () -> seen.add("loaded"));

        assertEquals(List.of("unknown", "loaded"), seen);
    }

    @Test
    void redeclaringKeepsStoredValuesButChangesDefaults() {
        StateStore store = store();
        store.beginLoad(PLAYER);
        store.set(StateScope.PLAYER, PLAYER, "visits", 2);

        store.declare(Map.of("other", List.of(
            new StateSchema("visits", StateScope.PLAYER, StateType.NUMBER, 10),
            new StateSchema("streak", StateScope.PLAYER, StateType.NUMBER, 1))));

        assertEquals(2.0D, store.get(StateScope.PLAYER, PLAYER, "visits"));
        assertEquals(1.0D, store.get(StateScope.PLAYER, PLAYER, "streak"));
        assertNull(store.get(StateScope.PLAYER, PLAYER, "welcomed"));
    }
}
