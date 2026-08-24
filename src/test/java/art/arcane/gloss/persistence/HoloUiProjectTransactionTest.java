package art.arcane.gloss.persistence;

import art.arcane.gloss.panel.PanelDefinition;
import art.arcane.gloss.panel.PanelTransform;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import org.junit.Assume;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertThrows;
import static org.junit.Assert.assertTrue;

public class HoloUiProjectTransactionTest {
  @Rule
  public final TemporaryFolder temp = new TemporaryFolder();

  @Test
  public void uncommittedPublishedTransactionRollsBackOnStartupRecovery() throws Exception {
    Path data = temp.newFolder("rollback").toPath();
    Path menu = data.resolve("menus/shop.json");
    Files.createDirectories(menu.getParent());
    byte[] original = "{\"components\":[]}".getBytes(StandardCharsets.UTF_8);
    Files.write(menu, original);
    GlossProjectTransaction transaction = new GlossProjectTransaction(data);

    transaction.apply("session", write(menu, "{\"offset\":[0,0,1],\"components\":[]}"),
        Map.of(menu, original));
    assertTrue(Files.readString(menu).contains("offset"));

    new GlossProjectTransaction(data).recover();

    assertEquals(new String(original, StandardCharsets.UTF_8), Files.readString(menu));
    assertTrue(Files.list(data.resolve("editor-sync-transactions")).findAny().isEmpty());
    assertEquals(1L, Files.list(data.resolve("editor-sync-backups")).count());
  }

  @Test
  public void preparedDeleteRecoveryAcceptsTheUntouchedOriginal() throws Exception {
    Path data = temp.newFolder("prepared-delete").toPath();
    Path menu = data.resolve("menus/shop.json");
    Files.createDirectories(menu.getParent());
    byte[] original = "{\"components\":[]}".getBytes(StandardCharsets.UTF_8);
    Files.write(menu, original);
    GlossProjectTransaction transaction = new GlossProjectTransaction(data);
    GlossProjectTransaction.Pending pending = transaction.apply("session",
        Map.of(menu, GlossProjectTransaction.Mutation.delete()), Map.of(menu, original));
    Files.write(menu, original);
    setState(pending, "prepared");

    new GlossProjectTransaction(data).recover();

    assertEquals(new String(original, StandardCharsets.UTF_8), Files.readString(menu));
  }

  @Test
  public void publishingDeleteRecoveryRestoresTheDeletedOriginal() throws Exception {
    Path data = temp.newFolder("publishing-delete").toPath();
    Path menu = data.resolve("menus/shop.json");
    Files.createDirectories(menu.getParent());
    byte[] original = "{\"components\":[]}".getBytes(StandardCharsets.UTF_8);
    Files.write(menu, original);
    GlossProjectTransaction transaction = new GlossProjectTransaction(data);
    GlossProjectTransaction.Pending pending = transaction.apply("session",
        Map.of(menu, GlossProjectTransaction.Mutation.delete()), Map.of(menu, original));
    setState(pending, "publishing");

    new GlossProjectTransaction(data).recover();

    assertEquals(new String(original, StandardCharsets.UTF_8), Files.readString(menu));
  }

  @Test
  public void publishedDeleteRecoveryRestoresTheDeletedOriginal() throws Exception {
    Path data = temp.newFolder("published-delete").toPath();
    Path menu = data.resolve("menus/shop.json");
    Files.createDirectories(menu.getParent());
    byte[] original = "{\"components\":[]}".getBytes(StandardCharsets.UTF_8);
    Files.write(menu, original);
    GlossProjectTransaction transaction = new GlossProjectTransaction(data);

    transaction.apply("session", Map.of(menu, GlossProjectTransaction.Mutation.delete()),
        Map.of(menu, original));
    assertFalse(Files.exists(menu));

    new GlossProjectTransaction(data).recover();

    assertEquals(new String(original, StandardCharsets.UTF_8), Files.readString(menu));
  }

  @Test
  public void midBatchDeleteFailureRollsBackEveryMutation() throws Exception {
    Path data = temp.newFolder("delete-mid-batch").toPath();
    Path first = data.resolve("menus/a.json");
    Path second = data.resolve("menus/b.json");
    Files.createDirectories(first.getParent());
    byte[] firstOriginal = "{\"components\":[]}".getBytes(StandardCharsets.UTF_8);
    byte[] secondOriginal = "{\"components\":[{\"id\":\"old\"}]}".getBytes(StandardCharsets.UTF_8);
    Files.write(first, firstOriginal);
    Files.write(second, secondOriginal);
    AtomicBoolean injected = new AtomicBoolean();
    GlossProjectTransaction transaction = new GlossProjectTransaction(data, directory -> {
      if (directory.equals(first.getParent()) && !Files.exists(first)
          && injected.compareAndSet(false, true)) {
        throw new IOException("injected publish failure");
      }
    });
    Map<Path, GlossProjectTransaction.Mutation> mutations = Map.of(
        first, GlossProjectTransaction.Mutation.delete(),
        second, GlossProjectTransaction.Mutation.write(
            "{\"components\":[{\"id\":\"new\"}]}".getBytes(StandardCharsets.UTF_8)));

    assertThrows(IOException.class,
        () -> transaction.apply("session", mutations,
            Map.of(first, firstOriginal, second, secondOriginal)));

    assertEquals(new String(firstOriginal, StandardCharsets.UTF_8), Files.readString(first));
    assertEquals(new String(secondOriginal, StandardCharsets.UTF_8), Files.readString(second));
    assertTrue(injected.get());
  }

  @Test
  public void newHologramMenuAndBoardRollBackTogether() throws Exception {
    Path data = temp.newFolder("hologram-create-rollback").toPath();
    Path menu = data.resolve("menus/spawn/welcome.json");
    Path board = data.resolve("panels/spawn/welcome.json");
    PanelDefinition definition = PanelDefinition.create(
        "spawn/welcome",
        "spawn/welcome",
        PanelTransform.at("minecraft:overworld", UUID.randomUUID(), 1.0D, 64.0D, 2.0D, 0.0D)
    );
    Map<Path, byte[]> expected = new HashMap<>();
    expected.put(menu, null);
    expected.put(board, null);
    GlossProjectTransaction transaction = new GlossProjectTransaction(data);

    transaction.apply("create-spawn-welcome",
        write(menu, "{\"offset\":[0,1.7,0],\"components\":[]}", board, definition), expected);
    assertTrue(Files.isRegularFile(menu));
    assertTrue(Files.isRegularFile(board));

    transaction.recover();
    assertFalse(Files.exists(menu));
    assertFalse(Files.exists(board));
  }

  @Test
  public void committedTransactionSurvivesRecoveryAndRetainsABoundedBackup() throws Exception {
    Path data = temp.newFolder("commit").toPath();
    Path menu = data.resolve("menus/shop.json");
    Files.createDirectories(menu.getParent());
    byte[] original = "{\"components\":[]}".getBytes(StandardCharsets.UTF_8);
    Files.write(menu, original);
    GlossProjectTransaction transaction = new GlossProjectTransaction(data);
    GlossProjectTransaction.Pending pending = transaction.apply("session",
        write(menu, "{\"offset\":[0,0,1],\"components\":[]}"), Map.of(menu, original));

    transaction.commit(pending);
    transaction.recover();

    assertTrue(Files.readString(menu).contains("offset"));
    assertTrue(Files.list(data.resolve("editor-sync-transactions")).findAny().isEmpty());
    assertEquals(1L, Files.list(data.resolve("editor-sync-backups")).count());
  }

  @Test
  public void recoveryNeverOverwritesAnIndependentExternalEdit() throws Exception {
    Path data = temp.newFolder("external-edit").toPath();
    Path menu = data.resolve("menus/shop.json");
    Files.createDirectories(menu.getParent());
    byte[] original = "{\"components\":[]}".getBytes(StandardCharsets.UTF_8);
    Files.write(menu, original);
    GlossProjectTransaction transaction = new GlossProjectTransaction(data);
    transaction.apply("session", write(menu, "{\"offset\":[0,0,1],\"components\":[]}"),
        Map.of(menu, original));
    Files.writeString(menu, "external-change", StandardCharsets.UTF_8);

    assertThrows(IOException.class, transaction::recover);
    assertEquals("external-change", Files.readString(menu));
  }

  @Test
  public void recoveryDeletesOnlyNewFilesThatStillMatchTheStagedHash() throws Exception {
    Path data = temp.newFolder("new-target").toPath();
    Path menu = data.resolve("menus/new.json");
    GlossProjectTransaction transaction = new GlossProjectTransaction(data);
    Map<Path, byte[]> expected = new HashMap<>();
    expected.put(menu, null);
    transaction.apply("session", write(menu, "{\"components\":[]}"), expected);
    Files.writeString(menu, "unrelated", StandardCharsets.UTF_8);

    assertThrows(IOException.class, transaction::recover);
    assertEquals("unrelated", Files.readString(menu));
  }

  @Test
  public void duplicateJournalTargetsFailClosed() throws Exception {
    Path data = temp.newFolder("duplicate-journal").toPath();
    Path menu = data.resolve("menus/shop.json");
    Files.createDirectories(menu.getParent());
    byte[] original = "{\"components\":[]}".getBytes(StandardCharsets.UTF_8);
    Files.write(menu, original);
    GlossProjectTransaction transaction = new GlossProjectTransaction(data);
    GlossProjectTransaction.Pending pending = transaction.apply("session",
        write(menu, "{\"offset\":[0,0,1],\"components\":[]}"), Map.of(menu, original));
    Path journal = pending.transactionDirectory().resolve("journal.json");
    JsonObject root = JsonParser.parseString(Files.readString(journal)).getAsJsonObject();
    root.getAsJsonArray("entries").add(root.getAsJsonArray("entries").get(0).deepCopy());
    Files.writeString(journal, root.toString());

    assertThrows(IOException.class, transaction::recover);
    assertTrue(Files.readString(menu).contains("offset"));
  }

  @Test
  public void stagedHashMismatchFailsClosedWithoutChangingThePublishedTarget() throws Exception {
    Path data = temp.newFolder("staged-hash").toPath();
    Path menu = data.resolve("menus/shop.json");
    Files.createDirectories(menu.getParent());
    byte[] original = "{\"components\":[]}".getBytes(StandardCharsets.UTF_8);
    Files.write(menu, original);
    GlossProjectTransaction transaction = new GlossProjectTransaction(data);
    GlossProjectTransaction.Pending pending = transaction.apply("session",
        write(menu, "{\"offset\":[0,0,1],\"components\":[]}"), Map.of(menu, original));
    Path staged = pending.transactionDirectory().resolve("stage/menus/shop.json");
    Files.createDirectories(staged.getParent());
    Files.writeString(staged, "tampered", StandardCharsets.UTF_8);
    String published = Files.readString(menu);

    assertThrows(IOException.class, transaction::recover);
    assertEquals(published, Files.readString(menu));
  }

  @Test
  public void optimisticMismatchIsRolledBackAndArchivedBeforeApplyReturns() throws Exception {
    Path data = temp.newFolder("optimistic-mismatch").toPath();
    Path menu = data.resolve("menus/shop.json");
    Files.createDirectories(menu.getParent());
    byte[] original = "{\"components\":[]}".getBytes(StandardCharsets.UTF_8);
    Files.write(menu, original);
    GlossProjectTransaction transaction = new GlossProjectTransaction(data);

    assertThrows(IOException.class, () -> transaction.apply("session",
        write(menu, "{\"offset\":[0,0,1],\"components\":[]}"),
        Map.of(menu, "stale".getBytes(StandardCharsets.UTF_8))));

    assertEquals(new String(original, StandardCharsets.UTF_8), Files.readString(menu));
    assertTrue(Files.list(data.resolve("editor-sync-transactions")).findAny().isEmpty());
    assertEquals(1L, Files.list(data.resolve("editor-sync-backups")).count());
  }

  @Test
  public void startupRemovesOnlyStrictlyShapedPreJournalTransactions() throws Exception {
    Path data = temp.newFolder("pre-journal").toPath();
    Path transactions = data.resolve("editor-sync-transactions");
    Path partial = transactions.resolve(
        "1770000000000-session-00000000-0000-4000-8000-000000000042");
    Files.createDirectories(partial.resolve("stage/menus"));
    Files.writeString(partial.resolve("stage/menus/shop.json"), "staged",
        StandardCharsets.UTF_8);

    new GlossProjectTransaction(data).recover();

    assertFalse(Files.exists(partial));
  }

  @Test
  public void startupFailsClosedForUnknownPreJournalContent() throws Exception {
    Path data = temp.newFolder("bad-pre-journal").toPath();
    Path transactions = data.resolve("editor-sync-transactions");
    Path partial = transactions.resolve(
        "1770000000000-session-00000000-0000-4000-8000-000000000042");
    Files.createDirectories(partial);
    Files.writeString(partial.resolve("unexpected"), "preserve", StandardCharsets.UTF_8);

    assertThrows(IOException.class, () -> new GlossProjectTransaction(data).recover());
    assertEquals("preserve", Files.readString(partial.resolve("unexpected")));
  }

  @Test
  public void failureBeforeCommitPointRemainsRecoverableByRollback() throws Exception {
    Path data = temp.newFolder("pre-commit-failure").toPath();
    Path menu = data.resolve("menus/shop.json");
    Files.createDirectories(menu.getParent());
    byte[] original = "{\"components\":[]}".getBytes(StandardCharsets.UTF_8);
    Files.write(menu, original);
    GlossProjectTransaction transaction = new GlossProjectTransaction(data);
    GlossProjectTransaction.Pending pending = transaction.apply("session",
        write(menu, "{\"offset\":[0,0,1],\"components\":[]}"), Map.of(menu, original));
    Path journal = pending.transactionDirectory().resolve("journal.json");
    JsonObject root = JsonParser.parseString(Files.readString(journal)).getAsJsonObject();
    root.addProperty("state", "publishing");
    Files.writeString(journal, root.toString());

    assertThrows(IOException.class, () -> transaction.commit(pending));
    transaction.recover();
    assertEquals(new String(original, StandardCharsets.UTF_8), Files.readString(menu));
  }

  @Test
  public void failureAfterCommitPointNeverRollsBackPublishedFiles() throws Exception {
    Path data = temp.newFolder("post-commit-failure").toPath();
    Path menu = data.resolve("menus/shop.json");
    Files.createDirectories(menu.getParent());
    byte[] original = "{\"components\":[]}".getBytes(StandardCharsets.UTF_8);
    Files.write(menu, original);
    GlossProjectTransaction transaction = new GlossProjectTransaction(data);
    GlossProjectTransaction.Pending pending = transaction.apply("session",
        write(menu, "{\"offset\":[0,0,1],\"components\":[]}"), Map.of(menu, original));
    Path blockingBackup = data.resolve("editor-sync-backups").resolve(pending.id());
    Files.createDirectories(blockingBackup);

    assertThrows(GlossProjectTransaction.CommittedCleanupException.class,
        () -> transaction.commit(pending));
    assertTrue(Files.readString(menu).contains("offset"));
    Files.delete(blockingBackup);
    transaction.recover();
    assertTrue(Files.readString(menu).contains("offset"));
  }

  @Test
  public void durableCommittedMarkerIsAlwaysClassifiedAsCommittedOnRetry() throws Exception {
    Path data = temp.newFolder("committed-marker-retry").toPath();
    Path menu = data.resolve("menus/shop.json");
    Files.createDirectories(menu.getParent());
    byte[] original = "{\"components\":[]}".getBytes(StandardCharsets.UTF_8);
    Files.write(menu, original);
    GlossProjectTransaction transaction = new GlossProjectTransaction(data);
    GlossProjectTransaction.Pending pending = transaction.apply("session",
        write(menu, "{\"offset\":[0,0,1],\"components\":[]}"), Map.of(menu, original));
    Path journal = pending.transactionDirectory().resolve("journal.json");
    JsonObject root = JsonParser.parseString(Files.readString(journal)).getAsJsonObject();
    root.addProperty("state", "committed");
    Files.writeString(journal, root.toString());

    assertThrows(GlossProjectTransaction.CommittedCleanupException.class,
        () -> transaction.commit(pending));
    transaction.recover();
    assertTrue(Files.readString(menu).contains("offset"));
  }

  @Test
  public void commitMarkerDirectoryFlushFailureRemainsUnacknowledgeableUntilRecovery()
      throws Exception {
    Path data = temp.newFolder("commit-marker-fsync").toPath();
    Path menu = data.resolve("menus/shop.json");
    Files.createDirectories(menu.getParent());
    byte[] original = "{\"components\":[]}".getBytes(StandardCharsets.UTF_8);
    Files.write(menu, original);
    AtomicBoolean failDirectoryForce = new AtomicBoolean();
    GlossProjectTransaction transaction = new GlossProjectTransaction(data, directory -> {
      if (failDirectoryForce.get()) {
        throw new IOException("injected directory fsync failure");
      }
    });
    GlossProjectTransaction.Pending pending = transaction.apply("session",
        write(menu, "{\"offset\":[0,0,1],\"components\":[]}"), Map.of(menu, original));

    failDirectoryForce.set(true);
    assertThrows(GlossProjectTransaction.CommitUncertainException.class,
        () -> transaction.commit(pending));
    assertThrows(GlossProjectTransaction.CommitUncertainException.class,
        () -> transaction.commit(pending));
    assertTrue(Files.exists(pending.transactionDirectory()));
    assertTrue(Files.readString(menu).contains("offset"));

    failDirectoryForce.set(false);
    new GlossProjectTransaction(data).recover();
    assertTrue(Files.readString(menu).contains("offset"));
    assertFalse(Files.exists(pending.transactionDirectory()));
  }

  @Test
  public void symbolicCollectionAncestorsCannotEscapeTheDataDirectory() throws Exception {
    Path data = temp.newFolder("symlink-data").toPath();
    Path outside = temp.newFolder("symlink-outside").toPath();
    try {
      Files.createSymbolicLink(data.resolve("menus"), outside);
    } catch (IOException | UnsupportedOperationException failure) {
      Assume.assumeNoException(failure);
    }
    GlossProjectTransaction transaction = new GlossProjectTransaction(data);
    Path escaped = data.resolve("menus/shop.json");

    Map<Path, byte[]> expected = new HashMap<>();
    expected.put(escaped, null);
    assertThrows(IOException.class, () -> transaction.apply("session",
        write(escaped, "{\"components\":[]}"), expected));
    assertFalse(Files.exists(outside.resolve("shop.json")));
  }

  private static Map<Path, GlossProjectTransaction.Mutation> write(Path path, String source) {
    return Map.of(path, GlossProjectTransaction.Mutation.write(
        source.getBytes(StandardCharsets.UTF_8)));
  }

  private static Map<Path, GlossProjectTransaction.Mutation> write(
      Path menu, String source, Path panel, PanelDefinition definition) {
    return Map.of(
        menu, GlossProjectTransaction.Mutation.write(source.getBytes(StandardCharsets.UTF_8)),
        panel, GlossProjectTransaction.Mutation.write(
            art.arcane.volmlib.util.bukkit.json.BukkitJson.GSON.toJson(definition)
                .getBytes(StandardCharsets.UTF_8)));
  }

  private static void setState(GlossProjectTransaction.Pending pending, String state)
      throws IOException {
    Path journal = pending.transactionDirectory().resolve("journal.json");
    JsonObject root = JsonParser.parseString(Files.readString(journal)).getAsJsonObject();
    root.addProperty("state", state);
    Files.writeString(journal, root.toString());
  }
}
