package art.arcane.gloss.panel;

import art.arcane.gloss.doc.DocumentDelta;
import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.FileTime;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertThrows;
import static org.junit.Assert.assertTrue;

public class PanelRepositoryHotloadTest {
  private static final Gson GSON = new GsonBuilder().serializeNulls().create();
  private static final UUID WORLD_UUID = UUID.fromString("00000000-0000-0000-0000-000000000311");

  @Rule
  public final TemporaryFolder temp = new TemporaryFolder();

  @Test
  public void newNestedPanelsRequireTwoMatchingCapturesWithoutCreatingAnUnusedFolder() throws IOException {
    try (Fixture fixture = fixture("nested")) {
      assertFalse(Files.exists(fixture.repository().directory()));
      assertTrue(fixture.poll(6L).isEmpty());
      assertFalse(Files.exists(fixture.repository().directory()));
      PanelDefinition created = board("new/folder.json/panel");
      fixture.write(created);

      assertTrue(fixture.poll(6L).isEmpty());
      assertTrue(fixture.repository().list().isEmpty());
      assertEquals(List.of(created.id()), fixture.poll(3L).loaded());
      assertEquals(created, fixture.repository().get(created.id()).orElseThrow());
      assertTrue(fixture.poll(6L).isEmpty());
    }
  }

  @Test
  public void changingAndPartialCapturesNeverReplaceTheLastGoodPanel() throws IOException {
    try (Fixture fixture = fixture("partial")) {
      PanelDefinition original = fixture.repository().create(board("spawn/partial"));
      PanelDefinition intermediate = original.withRootMenu("Intermediate").withRevision(2L);
      fixture.write(intermediate);
      assertTrue(fixture.poll(6L).isEmpty());
      Files.writeString(fixture.file(original.id()), "{\"id\":");
      assertTrue(fixture.poll(3L).isEmpty());
      PanelDefinition completed = original.withRootMenu("Completed").withRevision(3L);
      fixture.write(completed);
      assertTrue(fixture.poll(3L).isEmpty());
      assertEquals(original, fixture.repository().get(original.id()).orElseThrow());

      assertEquals(List.of(original.id()), fixture.poll(3L).loaded());
      assertEquals(completed, fixture.repository().get(original.id()).orElseThrow());
    }
  }

  @Test
  public void invalidContentRevisionAndIdentityRetainThePublishedPanel() throws IOException {
    try (Fixture fixture = fixture("validation")) {
      PanelDefinition original = fixture.repository().create(board("spawn/validation"));
      Files.writeString(fixture.file(original.id()), "{not-json");
      assertTrue(fixture.settle().isEmpty());
      assertEquals(original, fixture.repository().get(original.id()).orElseThrow());
      fixture.write(original.withRootMenu("Unversioned"));
      assertTrue(fixture.settle().isEmpty());
      assertEquals(original, fixture.repository().get(original.id()).orElseThrow());
      fixture.write(board(original.id()).withRevision(2L));
      assertTrue(fixture.settle().isEmpty());
      assertEquals(original, fixture.repository().get(original.id()).orElseThrow());

      PanelDefinition corrected = original.withRootMenu("Corrected").withRevision(2L);
      fixture.write(corrected);
      assertEquals(List.of(original.id()), fixture.settle().loaded());
      assertEquals(corrected, fixture.repository().get(original.id()).orElseThrow());
    }
  }

  @Test
  public void replacementCancelsDeletionButConfirmedDirectoryRemovalDeletesPanels() throws IOException {
    try (Fixture fixture = fixture("deletion")) {
      PanelDefinition original = fixture.repository().create(board("nested/deletion"));
      String raw = Files.readString(fixture.file(original.id()));
      Files.delete(fixture.file(original.id()));
      assertTrue(fixture.poll(6L).isEmpty());
      Files.writeString(fixture.file(original.id()), raw);
      assertTrue(fixture.poll(3L).isEmpty());
      assertEquals(original, fixture.repository().get(original.id()).orElseThrow());

      Files.delete(fixture.file(original.id()));
      Files.delete(fixture.file(original.id()).getParent());
      Files.delete(fixture.repository().directory());
      assertTrue(fixture.poll(6L).isEmpty());
      assertTrue(fixture.poll(2L).isEmpty());
      assertEquals(List.of(original.id()), fixture.poll(1L).removed());
      assertTrue(fixture.repository().list().isEmpty());
    }
  }

  @Test
  public void localWritesAndSemanticNoOpsDoNotProduceHotloadChanges() throws IOException {
    try (Fixture fixture = fixture("own-writes")) {
      PanelDefinition original = fixture.repository().create(board("spawn/local"));
      assertTrue(fixture.poll(6L).isEmpty());
      fixture.write(original);
      assertTrue(fixture.settle().isEmpty());
      fixture.write(original.withRootMenu("External").withRevision(2L));
      assertTrue(fixture.poll(6L).isEmpty());

      PanelDefinition updated = fixture.repository().update(original.id(), original.revision(),
          value -> value.withRootMenu("Local"));
      assertTrue(fixture.poll(3L).isEmpty());
      assertEquals(updated, fixture.repository().get(updated.id()).orElseThrow());
      PanelDefinition renamed = fixture.repository().rename(updated.id(), "archive/local", updated.revision());
      assertTrue(fixture.poll(6L).isEmpty());
      fixture.repository().delete(renamed.id(), renamed.revision());
      assertTrue(fixture.poll(6L).isEmpty());
      assertTrue(fixture.repository().list().isEmpty());
    }
  }

  @Test
  public void reconciliationFindsEditsWithUnchangedSizeAndTimestamp() throws IOException {
    try (Fixture fixture = fixture("same-metadata")) {
      PanelDefinition original = board("spawn/metadata").withRootMenu("MenuA");
      fixture.write(original);
      fixture.repository().load();
      Path file = fixture.file(original.id());
      FileTime modified = Files.getLastModifiedTime(file);
      long size = Files.size(file);
      PanelDefinition updated = original.withRootMenu("MenuB").withRevision(2L);
      fixture.write(updated);
      Files.setLastModifiedTime(file, modified);
      assertEquals(size, Files.size(file));

      assertEquals(List.of(original.id()), fixture.settle().loaded());
      assertEquals(updated, fixture.repository().get(updated.id()).orElseThrow());
    }
  }

  @Test
  public void duplicateUuidCannotReplaceItsOwnerButAConfirmedRenameCanLoad() throws IOException {
    try (Fixture fixture = fixture("rename")) {
      PanelDefinition original = fixture.repository().create(board("old/owner"));
      PanelDefinition renamed = new PanelDefinition(original.schemaVersion(), "new/owner", original.uuid(),
          2L, original.rootMenuId(), original.transform(), original.follow(), original.visibility(), original.show());
      fixture.write(renamed);
      assertTrue(fixture.settle().isEmpty());
      assertEquals(List.of(original), fixture.repository().list());

      Files.delete(fixture.file(original.id()));
      for (int attempt = 0; attempt < 6 && fixture.repository().get(renamed.id()).isEmpty(); attempt++) {
        fixture.poll(3L);
        assertTrue(fixture.repository().list().size() <= 1);
      }
      assertEquals(List.of(renamed), fixture.repository().list());
    }
  }

  @Test
  public void continuouslyChangingCandidatesDoNotStarveAStableQueuedPanel() throws IOException {
    try (Fixture fixture = fixture("fairness")) {
      PanelDefinition[] changing = new PanelDefinition[32];
      for (int index = 0; index < changing.length; index++) {
        changing[index] = board(String.format("a/%02d", index));
        fixture.write(changing[index]);
      }
      PanelDefinition stable = board("z/stable");
      fixture.write(stable);
      for (int attempt = 0; attempt < 8 && fixture.repository().get(stable.id()).isEmpty(); attempt++) {
        for (PanelDefinition definition : changing) {
          fixture.write(definition.withRootMenu("Changing" + attempt).withRevision(attempt + 1L));
        }
        DocumentDelta delta = fixture.poll(6L);
        assertTrue(delta.loaded().size() + delta.removed().size() <= 32);
      }
      assertEquals(stable, fixture.repository().get(stable.id()).orElseThrow());
    }
  }

  @Test
  public void closedRepositoryDoesNotResumeWatching() throws IOException {
    Fixture fixture = fixture("closed");
    fixture.close();
    fixture.write(board("closed/new"));
    assertTrue(fixture.poll(6L).isEmpty());
    assertTrue(fixture.repository().list().isEmpty());
    assertThrows(IllegalStateException.class, fixture.repository()::load);
  }

  private Fixture fixture(String name) throws IOException {
    AtomicLong clock = new AtomicLong(TimeUnit.SECONDS.toNanos(100L));
    PanelRepository repository = new PanelRepository(temp.newFolder(name).toPath(), clock::get);
    repository.load();
    return new Fixture(repository, clock);
  }

  private static PanelDefinition board(String id) {
    return PanelDefinition.create(id, "Menu", PanelTransform.at("minecraft:overworld", WORLD_UUID,
        1.0D, 65.0D, 2.0D, 0.0D));
  }

  private record Fixture(PanelRepository repository, AtomicLong clock) implements AutoCloseable {
    private Path file(String id) {
      return repository.directory().resolve(id + ".json");
    }

    private void write(PanelDefinition definition) throws IOException {
      Path file = file(definition.id());
      Files.createDirectories(file.getParent());
      Files.writeString(file, GSON.toJson(definition));
    }

    private DocumentDelta poll(long seconds) throws IOException {
      clock.addAndGet(TimeUnit.SECONDS.toNanos(seconds));
      return repository.poll();
    }

    private DocumentDelta settle() throws IOException {
      assertTrue(poll(6L).isEmpty());
      return poll(3L);
    }

    @Override
    public void close() {
      repository.close();
    }
  }
}
