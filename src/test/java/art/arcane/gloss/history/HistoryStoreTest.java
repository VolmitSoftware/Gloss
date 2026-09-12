package art.arcane.gloss.history;

import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.atomic.AtomicLong;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertThrows;
import static org.junit.Assert.assertTrue;

public class HistoryStoreTest {
  private static final long DAY_MILLIS = 86_400_000L;

  @Rule
  public final TemporaryFolder temp = new TemporaryFolder();

  @Test
  public void versionsAreListedNewestFirstWithTheirSourceAndSize() throws Exception {
    AtomicLong clock = new AtomicLong(1_000_000L);
    HistoryStore store = new HistoryStore(data(), clock::get);

    store.record("boards", "lobby", "one".getBytes(StandardCharsets.UTF_8), "watchdog");
    clock.addAndGet(1000L);
    store.record("boards", "lobby", "two!".getBytes(StandardCharsets.UTF_8), "editor:session");

    List<HistoryEntry> versions = store.list("boards", "lobby");

    assertEquals(2, versions.size());
    assertEquals("editor:session", versions.getFirst().source());
    assertEquals(1_001_000L, versions.getFirst().epochMillis());
    assertEquals(4L, versions.getFirst().bytes());
    assertEquals("watchdog", versions.get(1).source());
    assertArrayEquals("one".getBytes(StandardCharsets.UTF_8), store.read(versions.get(1)));
  }

  @Test
  public void pruningKeepsTheNewestVersionsAndDropsExpiredOnes() throws Exception {
    AtomicLong clock = new AtomicLong(10L * DAY_MILLIS);
    HistoryStore store = new HistoryStore(data(), clock::get);
    for (int index = 0; index < 5; index++) {
      store.record("boards", "lobby", ("v" + index).getBytes(StandardCharsets.UTF_8), "watchdog");
      clock.addAndGet(1000L);
    }

    assertEquals(2, store.prune(3, 3650));

    List<HistoryEntry> kept = store.list("boards", "lobby");
    assertEquals(3, kept.size());
    assertArrayEquals("v4".getBytes(StandardCharsets.UTF_8), store.read(kept.getFirst()));

    clock.set(20L * DAY_MILLIS);
    assertEquals(3, store.prune(500, 5));
    assertTrue(store.list("boards", "lobby").isEmpty());
  }

  @Test
  public void aTreeIdKeepsItsFolderPathAndASourceRoundTrips() throws Exception {
    HistoryStore store = new HistoryStore(data(), () -> 42L);

    HistoryEntry recorded = store.record("menus", "shop/main",
        "{}".getBytes(StandardCharsets.UTF_8), "pack:lobby-starter");

    assertEquals("shop/main", recorded.id());
    assertEquals("pack:lobby-starter", store.list("menus", "shop/main").getFirst().source());
    assertTrue(Files.isRegularFile(recorded.file()));
  }

  @Test
  public void anInvalidSourceIsRefusedBeforeAnythingIsWritten() throws Exception {
    Path data = data();
    HistoryStore store = new HistoryStore(data, () -> 42L);

    assertThrows(IllegalArgumentException.class,
        () -> store.record("boards", "lobby", new byte[] {1}, "../escape"));
    assertTrue(Files.notExists(data.resolve("history")));
  }

  private Path data() throws Exception {
    return temp.newFolder("data-" + System.nanoTime()).toPath();
  }
}
