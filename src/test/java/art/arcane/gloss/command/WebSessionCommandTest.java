package art.arcane.gloss.command;

import art.arcane.gloss.editor.sync.EditorSyncConfigurationException;
import art.arcane.gloss.editor.sync.EditorSyncKind;
import art.arcane.gloss.editor.sync.EditorSyncRelayException;
import art.arcane.gloss.editor.sync.EditorSyncSessionInfo;
import org.junit.Test;

import java.time.Instant;
import java.util.List;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertThrows;
import static org.junit.Assert.assertTrue;

public class WebSessionCommandTest {
  @Test
  public void exactAndUniqueTwelveCharacterPrefixesResolve() {
    EditorSyncSessionInfo first = session("abcdefghijkl-first-capability");
    EditorSyncSessionInfo second = session("zyxwvutsrqpo-second-capability");

    assertEquals(first.sessionId(), CommandGlossWebSessions.resolveSessionId(
        first.sessionId(), List.of(first, second)).orElseThrow());
    assertEquals(first.sessionId(), CommandGlossWebSessions.resolveSessionId(
        "abcdefghijkl", List.of(first, second)).orElseThrow());
    assertTrue(CommandGlossWebSessions.resolveSessionId("abcdefghijk", List.of(first, second)).isEmpty());
  }

  @Test
  public void ambiguousPrefixesFailClosed() {
    EditorSyncSessionInfo first = session("abcdefghijkl-first-capability");
    EditorSyncSessionInfo second = session("abcdefghijkl-second-capability");

    assertThrows(IllegalArgumentException.class, () ->
        CommandGlossWebSessions.resolveSessionId("abcdefghijkl", List.of(first, second)));
  }

  @Test
  public void expectedConfigurationRefusalsDoNotPrintExceptionStacks() {
    assertFalse(CommandGlossWeb.shouldLogOpenFailure(
        new EditorSyncConfigurationException("missing token")));
    assertTrue(CommandGlossWeb.shouldLogOpenFailure(
        new EditorSyncRelayException("relay failed")));
  }

  private static EditorSyncSessionInfo session(String id) {
    return new EditorSyncSessionInfo(id, EditorSyncKind.MENU, "fixture",
        Instant.now().plusSeconds(60L), 0L, null);
  }
}
