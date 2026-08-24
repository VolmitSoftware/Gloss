package art.arcane.gloss.editor.sync;

import art.arcane.gloss.panel.PanelDefinition;
import art.arcane.gloss.panel.PanelTransform;
import art.arcane.volmlib.util.bukkit.json.BukkitJson;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import org.junit.Test;

import java.time.Instant;
import java.util.UUID;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotEquals;
import static org.junit.Assert.assertThrows;
import static org.junit.Assert.assertTrue;

public class EditorSyncPublicationValidatorTest {
  @Test
  public void optimisticBaseAndEditedSnapshotRevisionAreIntentionallyDifferent() {
    JsonObject base = EditorSyncTestProjects.menuProject("shop", "{\"components\":[]}");
    EditorSyncStoredSession session = session(base);
    JsonObject changed = EditorSyncTestProjects.menuProject("shop",
        "{\"offset\":[0,0,1],\"components\":[]}");
    assertNotEquals(session.baseRevision(), changed.get("baseRevision").getAsString());

    EditorSyncPublicationValidator.ValidatedProject validated =
        new EditorSyncPublicationValidator().validate(session,
            new EditorSyncPublication(1L, session.baseRevision(), changed), 1024 * 1024);

    assertEquals("shop", validated.project().subjectId());
    assertNotEquals(changed.get("baseRevision").getAsString(),
        validated.project().baseRevision());
    assertEquals("{\"offset\":[0,0,1],\"components\":[]}\n",
        EditorSyncDocuments.parse(validated.project().json()).getFirst().json());
  }

  @Test
  public void menuCapabilitiesCannotAddASecondMenuOrChangeTheSubject() {
    JsonObject base = EditorSyncTestProjects.menuProject("shop", "{\"components\":[]}");
    EditorSyncStoredSession session = session(base);
    JsonObject changed = EditorSyncTestProjects.menuProject("shop", "{\"components\":[]}");
    JsonArray documents = new JsonArray();
    documents.add(EditorSyncTestProjects.document("menu", "admin", "{\"components\":[]}"));
    documents.addAll(changed.getAsJsonArray("documents"));
    changed.add("documents", documents);
    EditorSyncTestProjects.sign(changed);

    assertThrows(IllegalArgumentException.class, () ->
        new EditorSyncPublicationValidator().validate(session,
            new EditorSyncPublication(1L, session.baseRevision(), changed), 1024 * 1024));
  }

  @Test
  public void menuCapabilitiesCannotPublishAPanelDocument() {
    JsonObject base = EditorSyncTestProjects.menuProject("shop", "{\"components\":[]}");
    EditorSyncStoredSession session = session(base);
    JsonObject changed = EditorSyncTestProjects.menuProject("shop", "{\"components\":[]}");
    PanelDefinition panel = PanelDefinition.create(
        "shop", "shop", PanelTransform.at(
            "minecraft:overworld", UUID.randomUUID(), 0.0D, 64.0D, 0.0D, 0.0D));
    JsonObject panelDocument = EditorSyncTestProjects.document("panel", "shop",
        EditorSyncJson.canonical(
            BukkitJson.GSON.toJsonTree(panel)));
    panelDocument.addProperty("revision", panel.revision());
    changed.getAsJsonArray("documents").add(panelDocument);
    EditorSyncTestProjects.sign(changed);

    IllegalArgumentException failure = assertThrows(IllegalArgumentException.class, () ->
        new EditorSyncPublicationValidator().validate(session,
            new EditorSyncPublication(1L, session.baseRevision(), changed), 1024 * 1024));
    assertTrue(failure.getMessage(), failure.getMessage().contains("panel"));
  }

  @Test
  public void publicationRequestMustUseTheStoredOptimisticBase() {
    JsonObject base = EditorSyncTestProjects.menuProject("shop", "{\"components\":[]}");
    EditorSyncStoredSession session = session(base);
    JsonObject changed = EditorSyncTestProjects.menuProject("shop", "{\"components\":[]}");

    assertThrows(IllegalArgumentException.class, () ->
        new EditorSyncPublicationValidator().validate(session,
            new EditorSyncPublication(1L, "sha256:" + "0".repeat(64), changed),
            1024 * 1024));
  }

  private static EditorSyncStoredSession session(JsonObject project) {
    return new EditorSyncStoredSession(new EditorSyncStoredSession.Capability(
        "session_id_123456789ab", "server_token_123456789", "https://relay.example/v3"),
        new EditorSyncStoredSession.Subject(EditorSyncKind.MENU, "shop"),
        Instant.now().plusSeconds(3600L), 0L, project, null);
  }
}
