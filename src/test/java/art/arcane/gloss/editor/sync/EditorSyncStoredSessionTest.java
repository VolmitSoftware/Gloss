package art.arcane.gloss.editor.sync;

import com.google.gson.JsonObject;
import org.junit.Test;

import java.time.Instant;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public class EditorSyncStoredSessionTest {
  @Test
  public void capabilitiesUseTheSharedWireLengthAndAlphabet() {
    assertFalse(EditorSyncStoredSession.validCapability("a".repeat(21)));
    assertTrue(EditorSyncStoredSession.validCapability("a".repeat(22)));
    assertTrue(EditorSyncStoredSession.validCapability("A_-0" + "b".repeat(124)));
    assertFalse(EditorSyncStoredSession.validCapability("a".repeat(129)));
    assertFalse(EditorSyncStoredSession.validCapability("a".repeat(21) + "."));
  }
  @Test
  public void appliedBaseAdvancesOnlyAfterTheDurableAcknowledgementSucceeds() {
    JsonObject base = project("fixture", "{\"components\":[]}");
    JsonObject applied = project("fixture", "{\"components\":[{\"type\":\"button\"}]}");
    EditorSyncStoredSession session = session(base);
    EditorSyncStoredSession pending = session.withPendingAck(
        new EditorSyncPendingAck(1L, "applied", "Published.", applied));

    assertEquals(0L, pending.lastPublicationRevision());
    assertEquals(base.get("baseRevision").getAsString(), pending.baseRevision());

    EditorSyncStoredSession acknowledged = pending.acknowledgePending();
    assertEquals(1L, acknowledged.lastPublicationRevision());
    assertEquals(applied.get("baseRevision").getAsString(), acknowledged.baseRevision());
    assertNull(acknowledged.pendingAck());
  }

  @Test
  public void conflictPromotesCurrentServerBaseWhileRejectedRetainsTheOldBase() {
    JsonObject base = project("fixture", "{\"components\":[]}");
    JsonObject current = project("fixture", "{\"components\":[{\"type\":\"text\"}]}");
    EditorSyncStoredSession session = session(base);
    EditorSyncStoredSession conflicted = session.withPendingAck(
        new EditorSyncPendingAck(2L, "conflict", "Changed.", current)).acknowledgePending();
    EditorSyncStoredSession rejected = session.withPendingAck(
        new EditorSyncPendingAck(2L, "rejected", "Invalid.", null)).acknowledgePending();

    assertEquals(current.get("baseRevision").getAsString(), conflicted.baseRevision());
    assertEquals(base.get("baseRevision").getAsString(), rejected.baseRevision());
    assertEquals(2L, rejected.lastPublicationRevision());
  }

  private EditorSyncStoredSession session(JsonObject project) {
    return new EditorSyncStoredSession(new EditorSyncStoredSession.Capability(
        "session_id_123456789ab", "server_token_123456789", "https://relay.example/v2"),
        new EditorSyncStoredSession.Subject(EditorSyncKind.MENU, "fixture"),
        Instant.now().plusSeconds(3600L), 0L, project, null);
  }

  private JsonObject project(String id, String source) {
    return EditorSyncTestProjects.menuProject(id, source);
  }
}
