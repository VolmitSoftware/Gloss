package art.arcane.gloss.editor.sync;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import org.junit.Test;

import java.util.Base64;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertThrows;
import static org.junit.Assert.assertTrue;

/** Failure 7: conflicts are decided per document and per image path, and named. */
public class EditorSyncChangesetMergerTest {
  private static final String A = "{\"title\":\"Shop\",\"components\":[]}";
  private static final String B = "{\"components\":[]}";

  @Test
  public void aMovedDocumentConflictsByNameWhileTheOthersApply() {
    JsonObject current = project(A, B);
    EditorSyncChangeset changeset = new EditorSyncChangeset(List.of(
        put("a/shop", "sha256:" + "0".repeat(64), "{\"title\":\"Mine\",\"components\":[]}"),
        put("b/other", EditorSyncDocuments.contentRevision(B), "{\"components\":[1]}")),
        List.of());

    EditorSyncChangesetMerger.Merged merged = EditorSyncChangesetMerger.merge(current, changeset,
        Map.of(), Set.of(), Set.of());

    assertEquals(1, merged.changes());
    EditorSyncConflict conflict = merged.conflicts().getFirst();
    assertEquals("a/shop", conflict.id());
    assertEquals("Shop", conflict.name());
    assertEquals("changed", conflict.reason());
    List<EditorSyncDocuments.Entry> documents = EditorSyncDocuments.parse(merged.project());
    assertEquals(A, documents.get(0).json());
    assertEquals("{\"components\":[1]}", documents.get(1).json());
  }

  @Test
  public void untouchedDocumentsAndKindsSurviveWithoutBeingSent() {
    JsonObject current = project(A, B);
    EditorSyncChangeset changeset = new EditorSyncChangeset(List.of(
        put("b/other", EditorSyncDocuments.contentRevision(B), "{\"components\":[2]}")),
        List.of());

    EditorSyncChangesetMerger.Merged merged = EditorSyncChangesetMerger.merge(current, changeset,
        Map.of(), Set.of(), Set.of());

    assertEquals(2, EditorSyncDocuments.parse(merged.project()).size());
    assertTrue(merged.conflicts().isEmpty());
  }

  @Test
  public void createsDeletesAndTheirConflictsAreExplicit() {
    JsonObject current = project(A, B);
    EditorSyncChangeset changeset = new EditorSyncChangeset(List.of(
        put("a/shop", null, B),
        put("c/new", null, B),
        new EditorSyncChangeset.DocumentChange(true, "menu", "b/other",
            EditorSyncDocuments.contentRevision(B), null),
        new EditorSyncChangeset.DocumentChange(true, "menu", "gone/already",
            "sha256:" + "1".repeat(64), null)), List.of());

    EditorSyncChangesetMerger.Merged merged = EditorSyncChangesetMerger.merge(current, changeset,
        Map.of(), Set.of(), Set.of());

    assertEquals(2, merged.changes());
    assertEquals("exists", merged.conflicts().getFirst().reason());
    List<String> ids = EditorSyncDocuments.parse(merged.project()).stream()
        .map(EditorSyncDocuments.Entry::id).toList();
    assertEquals(List.of("a/shop", "c/new"), ids);
  }

  @Test
  public void imagesConflictPerPathNeverAllOrNothing() {
    byte[] first = EditorSyncTestProjects.png(1);
    byte[] second = EditorSyncTestProjects.png(2);
    byte[] added = EditorSyncTestProjects.png(3);
    JsonObject current = project(A, B);
    JsonArray images = new JsonArray();
    images.add(image("one.png", first));
    images.add(image("two.png", second));
    current.add("images", images);
    EditorSyncChangeset changeset = new EditorSyncChangeset(List.of(), List.of(
        new EditorSyncChangeset.ImageChange(false, "one.png", "sha256:" + "9".repeat(64),
            EditorWorkspace.sha256(added), "image/png"),
        new EditorSyncChangeset.ImageChange(false, "three.png", null,
            EditorWorkspace.sha256(added), "image/png"),
        new EditorSyncChangeset.ImageChange(true, "two.png", EditorWorkspace.sha256(second),
            null, null)));

    EditorSyncChangesetMerger.Merged merged = EditorSyncChangesetMerger.merge(current, changeset,
        Map.of(EditorWorkspace.sha256(added), added), Set.of(), Set.of());

    assertEquals(2, merged.changes());
    assertEquals(List.of(new EditorSyncConflict("image", "one.png", "one.png",
        "changed")), merged.conflicts());
    List<String> paths = merged.project().getAsJsonArray("images").asList().stream()
        .map(value -> value.getAsJsonObject().get("path").getAsString()).toList();
    assertEquals(List.of("one.png", "three.png"), paths);
  }

  @Test
  public void anImagePathIsJudgedAgainstTheImageItNamesOnceNormalized() {
    byte[] stored = EditorSyncTestProjects.png(1);
    byte[] added = EditorSyncTestProjects.png(3);
    JsonObject current = project(A, B);
    JsonArray images = new JsonArray();
    images.add(image("shop/coin.png", stored));
    current.add("images", images);
    EditorSyncChangeset changeset = new EditorSyncChangeset(List.of(), List.of(
        new EditorSyncChangeset.ImageChange(false, "shop\\coin.png", null, EditorWorkspace.sha256(added),
            "image/png")));

    EditorSyncChangesetMerger.Merged merged = EditorSyncChangesetMerger.merge(current, changeset,
        Map.of(EditorWorkspace.sha256(added), added), Set.of(), Set.of());

    assertEquals(0, merged.changes());
    assertEquals(List.of(new EditorSyncConflict("image", "shop/coin.png", "shop/coin.png", "exists")),
        merged.conflicts());
  }

  @Test
  public void aKindThisServerDoesNotHandleIsRefusedByName() {
    EditorSyncChangeset changeset = new EditorSyncChangeset(List.of(
        new EditorSyncChangeset.DocumentChange(false, "future-kind", "x", null, "{}")),
        List.of());

    IllegalArgumentException failure = assertThrows(IllegalArgumentException.class,
        () -> EditorSyncChangesetMerger.merge(project(A, B), changeset, Map.of(), Set.of(),
            Set.of()));
    assertTrue(failure.getMessage().contains("future-kind x"));
    assertFalse(failure.getMessage().isBlank());
  }

  private static EditorSyncChangeset.DocumentChange put(String id, String baseHash,
                                                        String json) {
    return new EditorSyncChangeset.DocumentChange(false, "menu", id, baseHash, json);
  }

  private static JsonObject project(String a, String b) {
    return EditorSyncTestProjects.workspaceProject(
        EditorSyncTestProjects.menuDocument("a/shop", a),
        EditorSyncTestProjects.menuDocument("b/other", b));
  }

  private static JsonObject image(String path, byte[] bytes) {
    JsonObject image = new JsonObject();
    image.addProperty("path", path);
    image.addProperty("data", "data:image/png;base64," + Base64.getEncoder()
        .encodeToString(bytes));
    return image;
  }
}
