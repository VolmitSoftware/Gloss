package art.arcane.gloss.command;

import art.arcane.volmlib.util.director.compat.DirectorEngineFactory;
import art.arcane.volmlib.util.director.help.DirectorMiniMenu;
import art.arcane.volmlib.util.director.runtime.DirectorRuntimeEngine;
import art.arcane.volmlib.util.director.runtime.DirectorRuntimeNode;
import org.junit.Test;

import java.util.List;
import java.util.Optional;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

public class MenuMoveActionTest {

  @Test
  public void moveIsAnInvocableLeaf() {
    DirectorRuntimeEngine engine = DirectorEngineFactory.create(new CommandGlossMenu(null));
    DirectorRuntimeNode move = child(engine, "move");

    assertNotNull(move);
    assertTrue(move.isInvocable());
    assertTrue(move.getChildren().isEmpty());
    assertEquals("/menu move", move.path());

    Optional<DirectorMiniMenu.DirectorHelpPage> page = DirectorMiniMenu.resolveHelp(engine, List.of("move"), 9);
    assertTrue(page.isEmpty());
  }

  private static DirectorRuntimeNode child(DirectorRuntimeEngine engine, String name) {
    for (DirectorRuntimeNode node : engine.getRoot().getChildren()) {
      if (node.allNames().contains(name)) {
        return node;
      }
    }
    return null;
  }
}
