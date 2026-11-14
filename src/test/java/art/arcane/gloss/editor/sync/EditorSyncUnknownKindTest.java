package art.arcane.gloss.editor.sync;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import org.junit.Test;


import static org.junit.Assert.assertThrows;
import static org.junit.Assert.assertTrue;

public class EditorSyncUnknownKindTest {
  private static final String MENU = "{\"components\":[]}\n";
  private static final String OPAQUE = "{\"schemaVersion\":1,\"revision\":1,\"opaque\":true}";

  @Test
  public void aKindTheServerNoLongerKnowsIsAcceptedWhenTheEditorEchoesItUnchanged() {
    JsonObject base = workspaceWithUnknownKind(OPAQUE);
    EditorSyncPublicationValidator.Base session = session(base);
    JsonObject published = workspaceWithUnknownKind(OPAQUE);

    EditorSyncPublicationValidator.ValidatedProject validated =
        new EditorSyncPublicationValidator().validate(session,
            new EditorSyncPublication(session.baseRevision(), published),
            1024 * 1024, base);

    assertTrue(validated.appliedDocuments().keySet().stream()
        .noneMatch(key -> key.kind().wireName().equals("menu")
            && key.id().equals("unknown")));
    assertTrue(validated.noOp());
  }

  @Test
  public void aKindTheEditorInventedIsRejectedByName() {
    JsonObject base = EditorSyncTestProjects.workspaceProject(
        EditorSyncTestProjects.menuDocument("one", MENU));
    EditorSyncPublicationValidator.Base session = session(base);
    JsonObject published = workspaceWithUnknownKind(OPAQUE);

    IllegalArgumentException failure = assertThrows(IllegalArgumentException.class, () ->
        new EditorSyncPublicationValidator().validate(session,
            new EditorSyncPublication(session.baseRevision(), published),
            1024 * 1024, base));

    assertTrue(failure.getMessage(), failure.getMessage().contains("future-kind"));
  }

  @Test
  public void anEditedUnknownKindIsRejected() {
    JsonObject base = workspaceWithUnknownKind(OPAQUE);
    EditorSyncPublicationValidator.Base session = session(base);
    JsonObject published = workspaceWithUnknownKind(
        "{\"schemaVersion\":1,\"revision\":1,\"opaque\":false}");

    IllegalArgumentException failure = assertThrows(IllegalArgumentException.class, () ->
        new EditorSyncPublicationValidator().validate(session,
            new EditorSyncPublication(session.baseRevision(), published),
            1024 * 1024, base));

    assertTrue(failure.getMessage(), failure.getMessage().contains("future-kind"));
  }

  @Test
  public void workspaceConstraintsMayListKindsBeyondTheOnesTheServerKnows() {
    JsonObject base = workspaceWithUnknownKind(OPAQUE);
    JsonObject constraints = base.getAsJsonObject("constraints");
    JsonArray documentKinds = constraints.getAsJsonArray("documentKinds");
    JsonArray createKinds = constraints.getAsJsonArray("createDocumentKinds");
    documentKinds.add("zzz-future-kind");
    createKinds.add("zzz-future-kind");
    EditorSyncTestProjects.sign(base);
    EditorSyncPublicationValidator.Base session = session(base);

    EditorSyncPublicationValidator.ValidatedProject validated =
        new EditorSyncPublicationValidator().validate(session,
            new EditorSyncPublication(session.baseRevision(), base.deepCopy()),
            1024 * 1024, base);

    assertTrue(validated.noOp());
  }

  private static JsonObject workspaceWithUnknownKind(String json) {
    JsonObject project = EditorSyncTestProjects.workspaceProject(
        EditorSyncTestProjects.menuDocument("one", MENU));
    JsonObject entry = EditorSyncTestProjects.document("future-kind", "unknown", json);
    entry.addProperty("baseRevision", EditorSyncDocuments.contentRevision(json));
    JsonArray documents = new JsonArray();
    documents.add(entry);
    project.getAsJsonArray("documents").forEach(documents::add);
    project.add("documents", documents);
    return EditorSyncTestProjects.sign(project);
  }

  private static EditorSyncPublicationValidator.Base session(JsonObject project) {
    return new EditorSyncPublicationValidator.Base(project);
  }
}
