package art.arcane.gloss.command;

import art.arcane.gloss.editor.EditorScope;
import art.arcane.volmlib.util.editor.EditorTokens;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class EditorGrantTest {
  private static final Optional<UUID> PLAYER = Optional.of(UUID.randomUUID());

  @Test
  void blankOptionsTakeTheDefaults() {
    EditorTokens.Grant<EditorScope> grant = CommandGlossEditor.grant(PLAYER, false, "", "", "", "", 7);

    assertEquals(EditorScope.DEFAULT_PAIRING, grant.scopes());
    assertEquals(List.of(), grant.folders());
    assertEquals(Duration.ofDays(7), grant.lifetime());
  }

  @Test
  void keyedOptionsNarrowTheGrant() {
    EditorTokens.Grant<EditorScope> grant = CommandGlossEditor.grant(PLAYER, false, " laptop ", "read,publish:world",
        "shop, lobby/hall", "12h", 7);

    assertEquals("laptop", grant.label());
    assertEquals(List.of(EditorScope.READ, EditorScope.PUBLISH_WORLD), grant.scopes());
    assertEquals(List.of("shop", "lobby/hall"), grant.folders());
    assertEquals(Duration.ofHours(12), grant.lifetime());
  }

  @Test
  void refusalsNameTheFix() {
    IllegalArgumentException unknown = assertThrows(IllegalArgumentException.class,
        () -> CommandGlossEditor.grant(PLAYER, false, "", "read,write", "", "", 7));
    IllegalArgumentException commands = assertThrows(IllegalArgumentException.class,
        () -> CommandGlossEditor.grant(PLAYER, false, "", "read,publish:commands", "", "", 7));
    IllegalArgumentException folder = assertThrows(IllegalArgumentException.class,
        () -> CommandGlossEditor.grant(PLAYER, false, "", "", "Shop", "", 7));
    IllegalArgumentException expires = assertThrows(IllegalArgumentException.class,
        () -> CommandGlossEditor.grant(PLAYER, false, "", "", "", "7w", 7));

    assertTrue(unknown.getMessage().startsWith("Unknown scope write; scopes are read, history, publish:screens"));
    assertTrue(commands.getMessage().endsWith("it needs gloss.editor.commands or the console"));
    assertTrue(folder.getMessage().startsWith("folders take folder names such as shop or shop/sub"));
    assertEquals("expires takes a number with d, h or m, such as 7d", expires.getMessage());
  }

  @Test
  void theConsoleMayPairPublishCommands() {
    EditorTokens.Grant<EditorScope> grant = CommandGlossEditor.grant(Optional.empty(), true, "", "read,publish:commands", "", "", 7);

    assertTrue(grant.scopes().contains(EditorScope.PUBLISH_COMMANDS));
    assertEquals("7d", CommandGlossEditor.describe(Duration.ofDays(7)));
    assertEquals("90m", CommandGlossEditor.describe(Duration.ofMinutes(90)));
  }
}
