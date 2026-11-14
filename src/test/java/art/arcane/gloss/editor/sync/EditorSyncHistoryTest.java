package art.arcane.gloss.editor.sync;

import art.arcane.gloss.persistence.GlossProjectTransaction;
import com.google.gson.JsonArray;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;

public class EditorSyncHistoryTest {
  @Rule
  public final TemporaryFolder temp = new TemporaryFolder();

  @Test
  public void everyDocumentAPublishDeletesIsKeptForHistoryAndNothingElse() throws Exception {
    Path data = temp.newFolder("deleted").toPath().toAbsolutePath().normalize();
    byte[] hologram = "{\"lines\":[\"Gone\"]}".getBytes(StandardCharsets.UTF_8);
    byte[] menu = "{\"components\":[]}".getBytes(StandardCharsets.UTF_8);
    byte[] tablist = "{\"header\":[]}".getBytes(StandardCharsets.UTF_8);
    Map<Path, byte[]> expected = new LinkedHashMap<>();
    expected.put(data.resolve("holograms/gone.json"), hologram);
    expected.put(data.resolve("menus/shop/main.json"), menu);
    expected.put(data.resolve("tablist.json"), tablist);
    expected.put(data.resolve("holograms/kept.json"), hologram);
    expected.put(data.resolve("images/shop/coin.png"), new byte[] {1, 2, 3});
    expected.put(data.resolve("holograms/new.json"), null);
    Map<Path, GlossProjectTransaction.Mutation> mutations = new LinkedHashMap<>();
    mutations.put(data.resolve("holograms/gone.json"), GlossProjectTransaction.Mutation.delete());
    mutations.put(data.resolve("menus/shop/main.json"), GlossProjectTransaction.Mutation.delete());
    mutations.put(data.resolve("tablist.json"), GlossProjectTransaction.Mutation.delete());
    mutations.put(data.resolve("holograms/kept.json"), GlossProjectTransaction.Mutation.write(menu));
    mutations.put(data.resolve("images/shop/coin.png"), GlossProjectTransaction.Mutation.delete());
    mutations.put(data.resolve("holograms/new.json"), GlossProjectTransaction.Mutation.write(menu));

    List<EditorSyncApplier.Deleted> deleted = EditorSyncApplier.deleted(data, expected, mutations);

    assertEquals(3, deleted.size());
    assertEquals("holograms", deleted.get(0).collection());
    assertEquals("gone", deleted.get(0).id());
    assertArrayEquals(hologram, deleted.get(0).bytes());
    assertEquals("menus", deleted.get(1).collection());
    assertEquals("shop/main", deleted.get(1).id());
    assertEquals("tablist", deleted.get(2).collection());
    assertEquals("tablist", deleted.get(2).id());
  }

  @Test
  public void aProjectCarriesTheVersionsTheServerHasForEachDocument() throws Exception {
    Path data = temp.newFolder("history-section").toPath();
    Files.createDirectories(data.resolve("menus"));
    Files.writeString(data.resolve("menus/shop.json"), "{\"components\":[]}",
        StandardCharsets.UTF_8);

    EditorSyncProject project = new EditorSyncContentSnapshotBuilder(data)
        .open(1024 * 1024,
            new EditorSyncContentSnapshotBuilder.ServerSections() {
              @Override
              public JsonArray history(List<EditorSyncDocuments.Entry> documents) {
                JsonArray history = new JsonArray();
                history.add(EditorSyncProjectSections.historyEntry("menus", "shop", 1700L,
                    "watchdog", 18L));
                return history;
              }
            });

    JsonArray history = project.json().getAsJsonArray("history");
    assertEquals(1, history.size());
    assertEquals("menus", history.get(0).getAsJsonObject().get("kind").getAsString());
    assertEquals(1700L, history.get(0).getAsJsonObject().get("version").getAsLong());
    assertEquals(project.baseRevision(), EditorSyncJson.revision(project.json()));
  }
}
