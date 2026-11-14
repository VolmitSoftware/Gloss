package art.arcane.gloss.editor.sync;

import art.arcane.gloss.panel.PanelDefinition;
import art.arcane.gloss.panel.PanelTransform;
import art.arcane.volmlib.util.bukkit.json.BukkitJson;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import org.junit.Test;

import java.util.UUID;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotEquals;
import static org.junit.Assert.assertThrows;
import static org.junit.Assert.assertTrue;

public class EditorSyncPublicationValidatorTest {
  @Test
  public void optimisticBaseAndEditedSnapshotRevisionAreIntentionallyDifferent() {
    JsonObject base = EditorSyncTestProjects.menuProject("shop", "{\"components\":[]}");
    EditorSyncPublicationValidator.Base session = session(base);
    JsonObject changed = EditorSyncTestProjects.menuProject("shop",
        "{\"offset\":[0,0,1],\"components\":[]}");
    assertNotEquals(session.baseRevision(), changed.get("baseRevision").getAsString());

    EditorSyncPublicationValidator.ValidatedProject validated =
        new EditorSyncPublicationValidator().validate(session,
            new EditorSyncPublication(session.baseRevision(), changed), 1024 * 1024,
            session.baseProject());

    assertNotEquals(changed.get("baseRevision").getAsString(),
        validated.project().baseRevision());
    assertEquals("{\"components\":[],\"offset\":[0,0,1]}",
        EditorSyncDocuments.parse(validated.project().json()).getFirst().json());
  }

  @Test
  public void publicationRequestMustUseTheStoredOptimisticBase() {
    JsonObject base = EditorSyncTestProjects.menuProject("shop", "{\"components\":[]}");
    EditorSyncPublicationValidator.Base session = session(base);
    JsonObject changed = EditorSyncTestProjects.menuProject("shop", "{\"components\":[]}");

    assertThrows(IllegalArgumentException.class, () ->
        new EditorSyncPublicationValidator().validate(session,
            new EditorSyncPublication("sha256:" + "0".repeat(64), changed),
            1024 * 1024, session.baseProject()));
  }

  private static EditorSyncPublicationValidator.Base session(JsonObject project) {
    return new EditorSyncPublicationValidator.Base(project);
  }
}
