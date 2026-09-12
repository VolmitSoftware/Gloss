package art.arcane.gloss.history;

import art.arcane.gloss.persistence.GlossProjectTransaction;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.atomic.AtomicLong;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertThrows;

public class HistoryRestoreTest {
  private static final String OLD = "{\"schemaVersion\":2,\"revision\":1,\"lines\":[\"old\"]}";
  private static final String CURRENT = "{\"schemaVersion\":2,\"revision\":4,\"lines\":[\"new\"]}";

  @Rule
  public final TemporaryFolder temp = new TemporaryFolder();

  @Test
  public void restoringWritesTheVersionAndKeepsTheCopyItReplaced() throws Exception {
    Path data = temp.newFolder("restore").toPath();
    Path board = data.resolve("boards/lobby.json");
    Files.createDirectories(board.getParent());
    Files.writeString(board, CURRENT, StandardCharsets.UTF_8);
    AtomicLong clock = new AtomicLong(5_000L);
    HistoryStore store = new HistoryStore(data, clock::get);
    HistoryEntry version = store.record("boards", "lobby",
        OLD.getBytes(StandardCharsets.UTF_8), "watchdog");
    clock.set(6_000L);
    HistoryRestore restore = new HistoryRestore(data, new GlossProjectTransaction(data), store);

    restore.apply("boards", "lobby", version);

    assertEquals(OLD, Files.readString(board));
    List<HistoryEntry> versions = store.list("boards", "lobby");
    assertEquals(2, versions.size());
    assertEquals("restore", versions.getFirst().source());
    assertEquals(CURRENT, new String(store.read(versions.getFirst()), StandardCharsets.UTF_8));
  }

  @Test
  public void restoringAKindTheServerDoesNotKnowIsRefused() throws Exception {
    Path data = temp.newFolder("unknown-kind").toPath();
    HistoryStore store = new HistoryStore(data, () -> 1L);
    HistoryEntry version = store.record("not-a-kind", "lobby",
        OLD.getBytes(StandardCharsets.UTF_8), "watchdog");
    HistoryRestore restore = new HistoryRestore(data, new GlossProjectTransaction(data), store);

    assertThrows(IllegalArgumentException.class,
        () -> restore.apply("not-a-kind", "lobby", version));
  }
}
