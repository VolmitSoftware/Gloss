package art.arcane.gloss.doc;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class DataWatchdogTest {
    @Test
    void entriesArePolledInRegistrationOrder() {
        DataWatchdog watchdog = new DataWatchdog(null);
        List<String> order = new ArrayList<>();
        watchdog.register("first", () -> order.add("first"));
        watchdog.register("second", () -> order.add("second"));
        watchdog.register("third", () -> order.add("third"));

        watchdog.tick();

        assertEquals(List.of("first", "second", "third"), order);
    }

    @Test
    void registeringTheSameNameReplacesTheEntry() {
        DataWatchdog watchdog = new DataWatchdog(null);
        List<String> polled = new ArrayList<>();
        watchdog.register("entry", () -> polled.add("old"));
        watchdog.register("entry", () -> polled.add("new"));

        watchdog.tick();

        assertEquals(List.of("new"), polled);
    }

    @Test
    void unregisteredEntriesAreNoLongerPolled() {
        DataWatchdog watchdog = new DataWatchdog(null);
        List<String> polled = new ArrayList<>();
        watchdog.register("keep", () -> polled.add("keep"));
        watchdog.register("drop", () -> polled.add("drop"));
        watchdog.unregister("drop");

        watchdog.tick();

        assertEquals(List.of("keep"), polled);
    }

    @Test
    void unregisteringAnUnknownNameLeavesEveryOtherEntryArmed() {
        DataWatchdog watchdog = new DataWatchdog(null);
        List<String> polled = new ArrayList<>();
        watchdog.register("menus", () -> polled.add("menus"));
        watchdog.register("previews", () -> polled.add("previews"));
        watchdog.unregister("absent");

        watchdog.tick();

        assertEquals(List.of("menus", "previews"), polled);
    }

    @Test
    void oneEntryMayRunSeveralPhasesAndTheOrderIsStableAcrossPasses() {
        DataWatchdog watchdog = new DataWatchdog(null);
        List<String> polled = new ArrayList<>();
        watchdog.register("menus", () -> {
            polled.add("fast");
            polled.add("slow");
        });

        watchdog.tick();
        watchdog.tick();

        assertEquals(List.of("fast", "slow", "fast", "slow"), polled);
    }

    @Test
    void aThrowingEntryDoesNotStopLaterEntries() {
        DataWatchdog watchdog = new DataWatchdog(null);
        List<String> polled = new ArrayList<>();
        watchdog.register("broken", () -> {
            throw new IllegalStateException("boom");
        });
        watchdog.register("error", () -> {
            throw new StackOverflowError();
        });
        watchdog.register("after", () -> polled.add("after"));

        watchdog.tick();

        assertEquals(List.of("after"), polled);
    }

    @Test
    @SuppressWarnings("removal")
    void threadDeathIsRethrown() {
        DataWatchdog watchdog = new DataWatchdog(null);
        watchdog.register("fatal", () -> {
            throw new ThreadDeath();
        });

        assertThrows(ThreadDeath.class, watchdog::tick);
    }
}
