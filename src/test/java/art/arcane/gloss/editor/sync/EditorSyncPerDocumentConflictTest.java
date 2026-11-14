package art.arcane.gloss.editor.sync;

import com.google.gson.JsonObject;
import org.junit.Test;

import java.util.List;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public class EditorSyncPerDocumentConflictTest {
  private static final String ONE_BASE = "{\"components\":[]}\n";
  private static final String ONE_EDITED = "{\"offset\":[0,0,1],\"components\":[]}\n";
  private static final String TWO_BASE = "{\"components\":[]}\n";
  private static final String TWO_SERVER = "{\"offset\":[0,1,0],\"components\":[]}\n";
  private static final String TWO_EDITED = "{\"offset\":[1,0,0],\"components\":[]}\n";

  @Test
  public void documentsTheServerDidNotMoveApplyWhileMovedOnesAreReported() {
    JsonObject base = EditorSyncTestProjects.workspaceProject(
        EditorSyncTestProjects.menuDocument("one", ONE_BASE),
        EditorSyncTestProjects.menuDocument("two", TWO_BASE));
    EditorSyncPublicationValidator.Base session = workspaceSession(base);
    JsonObject server = EditorSyncTestProjects.workspaceProject(
        EditorSyncTestProjects.menuDocument("one", ONE_BASE),
        EditorSyncTestProjects.menuDocument("two", TWO_SERVER));
    JsonObject published = EditorSyncTestProjects.workspaceProject(
        EditorSyncTestProjects.editedMenuDocument("one", ONE_EDITED, ONE_BASE),
        EditorSyncTestProjects.editedMenuDocument("two", TWO_EDITED, TWO_BASE));

    EditorSyncPublicationValidator.ValidatedProject validated =
        new EditorSyncPublicationValidator().validate(session,
            new EditorSyncPublication(session.baseRevision(), published),
            1024 * 1024, server);

    assertEquals(List.of(new EditorSyncPublicationValidator.DocumentKey(
        EditorSyncDocumentKind.MENU, "two")), validated.conflicts());
    assertEquals("{\"components\":[],\"offset\":[0,0,1]}\n", appliedSource(validated, "one"));
    assertEquals(TWO_SERVER, appliedSource(validated, "two"));
    assertFalse(validated.noOp());
  }

  @Test
  public void aDocumentTheEditorDidNotChangeIsRefreshedSilently() {
    JsonObject base = EditorSyncTestProjects.workspaceProject(
        EditorSyncTestProjects.menuDocument("one", ONE_BASE));
    EditorSyncPublicationValidator.Base session = workspaceSession(base);
    JsonObject server = EditorSyncTestProjects.workspaceProject(
        EditorSyncTestProjects.menuDocument("one", ONE_EDITED));
    JsonObject published = EditorSyncTestProjects.workspaceProject(
        EditorSyncTestProjects.editedMenuDocument("one", ONE_BASE, ONE_BASE));

    EditorSyncPublicationValidator.ValidatedProject validated =
        new EditorSyncPublicationValidator().validate(session,
            new EditorSyncPublication(session.baseRevision(), published),
            1024 * 1024, server);

    assertTrue(validated.conflicts().isEmpty());
    assertEquals(ONE_EDITED, appliedSource(validated, "one"));
    assertTrue(validated.noOp());
  }

  @Test
  public void aDocumentCreatedOnTheServerSurvivesAWorkspaceMirrorThatNeverSawIt() {
    JsonObject base = EditorSyncTestProjects.workspaceProject(
        EditorSyncTestProjects.menuDocument("one", ONE_BASE));
    EditorSyncPublicationValidator.Base session = workspaceSession(base);
    JsonObject server = EditorSyncTestProjects.workspaceProject(
        EditorSyncTestProjects.menuDocument("one", ONE_BASE),
        EditorSyncTestProjects.menuDocument("two", TWO_SERVER));
    JsonObject published = EditorSyncTestProjects.workspaceProject(
        EditorSyncTestProjects.editedMenuDocument("one", ONE_EDITED, ONE_BASE));

    EditorSyncPublicationValidator.ValidatedProject validated =
        new EditorSyncPublicationValidator().validate(session,
            new EditorSyncPublication(session.baseRevision(), published),
            1024 * 1024, server);

    assertTrue(validated.conflicts().isEmpty());
    assertEquals(TWO_SERVER, appliedSource(validated, "two"));
  }

  private static String appliedSource(
      EditorSyncPublicationValidator.ValidatedProject validated, String id) {
    EditorSyncPublicationValidator.ParsedEntry entry = validated.appliedDocuments()
        .get(new EditorSyncPublicationValidator.DocumentKey(EditorSyncDocumentKind.MENU, id));
    return entry == null ? null : entry.entry().json();
  }

  private static EditorSyncPublicationValidator.Base workspaceSession(JsonObject project) {
    return new EditorSyncPublicationValidator.Base(project);
  }
}
