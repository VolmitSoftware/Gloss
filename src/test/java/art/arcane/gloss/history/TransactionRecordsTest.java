package art.arcane.gloss.history;

import art.arcane.gloss.persistence.GlossProjectTransaction;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

public class TransactionRecordsTest {
  @Rule
  public final TemporaryFolder temp = new TemporaryFolder();

  @Test
  public void aCommittedEditorPublicationAppearsAsOneHistoryEntryPerOverwrittenFile()
      throws Exception {
    Path data = temp.newFolder("committed").toPath();
    Path board = data.resolve("boards/lobby.json");
    Files.createDirectories(board.getParent());
    byte[] original = "{\"schemaVersion\":2,\"revision\":1}".getBytes(StandardCharsets.UTF_8);
    Files.write(board, original);
    GlossProjectTransaction transaction = new GlossProjectTransaction(data);
    GlossProjectTransaction.Pending pending = transaction.apply("web_session", Map.of(board,
            GlossProjectTransaction.Mutation.write(
                "{\"schemaVersion\":2,\"revision\":2}".getBytes(StandardCharsets.UTF_8))),
        Map.of(board, original));
    transaction.commit(pending);

    List<GlossProjectTransaction.TransactionRecord> records = transaction.committedTransactions();
    List<HistoryEntry> entries = TransactionRecords.of(records);

    assertEquals(1, records.size());
    assertEquals("web_session", records.getFirst().label());
    assertEquals(1, entries.size());
    assertEquals("boards", entries.getFirst().kind());
    assertEquals("lobby", entries.getFirst().id());
    assertEquals("editor:web_session", entries.getFirst().source());
    assertArrayEquals(original, Files.readAllBytes(entries.getFirst().file()));
  }

  @Test
  public void aTransactionThatOnlyCreatedFilesContributesNoHistory() throws Exception {
    Path data = temp.newFolder("created").toPath();
    Path board = data.resolve("boards/new.json");
    GlossProjectTransaction transaction = new GlossProjectTransaction(data);
    GlossProjectTransaction.Pending pending = transaction.apply("web_session",
        Map.of(board, GlossProjectTransaction.Mutation.write(
            "{\"schemaVersion\":2,\"revision\":1}".getBytes(StandardCharsets.UTF_8))),
        Map.of());
    transaction.commit(pending);

    assertTrue(TransactionRecords.of(transaction.committedTransactions()).isEmpty());
  }

  @Test
  public void aServerWithoutAnyEditorPublicationHasNoRecords() throws Exception {
    Path data = temp.newFolder("empty").toPath();

    assertTrue(new GlossProjectTransaction(data).committedTransactions().isEmpty());
  }
}
