package art.arcane.gloss.command;

import art.arcane.volmlib.util.director.compat.DirectorEngineFactory;
import art.arcane.volmlib.util.director.runtime.DirectorRuntimeEngine;
import art.arcane.volmlib.util.director.runtime.DirectorInvocation;
import art.arcane.volmlib.util.director.runtime.DirectorRuntimeNode;
import art.arcane.volmlib.util.director.runtime.DirectorRuntimeParameter;
import art.arcane.volmlib.util.director.runtime.DirectorSender;
import org.junit.Test;

import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

public class GlossCommandDiscoveryTest {
  @Test
  public void rootCompletionShowsTheCanonicalSingularWorkflow() {
    DirectorRuntimeEngine engine = engine();
    List<String> suggestions = engine.tabComplete(
        new DirectorInvocation(new TestSender(), "gloss", List.of("")));

    assertTrue(suggestions.containsAll(List.of("menu", "panel", "preview", "item", "sync", "import")));
    assertFalse(suggestions.contains("panels"));
    assertFalse(suggestions.contains("menus"));
    assertFalse(suggestions.contains("previews"));
    assertFalse(suggestions.contains("items"));
  }

  @Test
  public void panelGroupDiscoversEveryOperatorAction() {
    DirectorRuntimeEngine engine = engine();
    DirectorRuntimeNode panel = child(engine.getRoot(), "panel");

    assertNotNull(panel);
    Set<String> names = panel.getChildren().stream()
        .map(node -> node.getDescriptor().getName())
        .collect(Collectors.toUnmodifiableSet());
    assertEquals(Set.of(
        "list", "reload", "near", "info", "create", "delete", "rename", "copy",
        "move", "here", "teleport", "rotate", "scale", "align", "menu", "ranges",
        "visibility", "permissions", "follow", "unfollow", "edit", "save", "cancel",
        "addrow", "insertrow", "setrow", "removerow", "offsetrow", "seticon", "style",
        "image", "web"
    ), names);
  }

  @Test
  public void menuGroupMergesSessionAndPersistentContentActions() {
    DirectorRuntimeEngine engine = engine();
    DirectorRuntimeNode menu = child(engine.getRoot(), "menu");

    assertNotNull(menu);
    Set<String> names = menu.getChildren().stream()
        .map(node -> node.getDescriptor().getName())
        .collect(Collectors.toUnmodifiableSet());
    assertEquals(Set.of(
        "list", "create", "open", "back", "close", "move", "builder", "edit",
        "addrow", "insertrow", "setrow", "removerow", "offsetrow", "seticon", "style",
        "image", "copy", "new"
    ), names);
  }

  @Test
  public void contentParametersExposePageDefaultsAndValueCompletions() {
    DirectorRuntimeEngine engine = engine();
    DirectorRuntimeNode panel = child(engine.getRoot(), "panel");
    DirectorRuntimeNode menu = child(engine.getRoot(), "menu");
    DirectorRuntimeNode create = child(menu, "create");

    assertEquals(List.of("page", "sender"), parameterNames(child(panel, "list")));
    assertEquals(List.of("menu", "row", "type", "value", "sender"),
        parameterNames(child(menu, "seticon")));
    assertEquals(List.of("menu", "row", "property", "value", "sender"),
        parameterNames(child(menu, "style")));
    assertEquals(List.of("menu", "path", "sender"), parameterNames(child(menu, "image")));
    assertEquals(List.of("panel", "row", "type", "value", "sender"),
        parameterNames(child(panel, "seticon")));
    assertEquals(List.of("panel", "row", "property", "value", "sender"),
        parameterNames(child(panel, "style")));
    assertEquals(List.of("panel", "path", "sender"), parameterNames(child(panel, "image")));
    assertEquals(List.of("hologram", "text", "sender"), parameterNames(create));
    assertFalse("*".equals(parameter(create, "text").getDescriptor().getDefaultValue()));
    assertTrue(parameter(create, "hologram").getCustomHandlerOrNull()
        instanceof CommandGlossMenu.HologramIdHandler);

    DirectorRuntimeParameter page = parameter(child(panel, "list"), "page");
    assertEquals(int.class, page.getDescriptor().getType());
    assertEquals("1", page.getDescriptor().getDefaultValue());
    assertEquals("command.help.arg.list_page", page.getDescriptor().getDescriptionKey());

    DirectorRuntimeParameter createMenu = parameter(child(panel, "create"), "menu");
    assertEquals("*", createMenu.getDescriptor().getDefaultValue());
    assertFalse(createMenu.getDescriptor().isRequired());

    DirectorRuntimeParameter menuIconType = parameter(child(menu, "seticon"), "type");
    assertTrue(menuIconType.getCustomHandlerOrNull() instanceof MenuRowCommandSupport.IconTypeHandler);
    assertEquals(
        Set.of("text", "image", "animated", "item", "block", "customItem", "entity"),
        Set.copyOf(menuIconType.getCustomHandlerOrNull().getPossibilities())
    );

    DirectorRuntimeParameter boardStyleProperty = parameter(child(panel, "style"), "property");
    assertTrue(boardStyleProperty.getCustomHandlerOrNull() instanceof MenuRowCommandSupport.StylePropertyHandler);
    assertTrue(boardStyleProperty.getCustomHandlerOrNull().getPossibilities().contains("backgroundArgb"));
    assertEquals("path", parameter(child(panel, "image"), "path").getDescriptor().getName());
  }

  @Test
  public void panelAliasesRemainDiscoverable() {
    DirectorRuntimeEngine engine = engine();
    DirectorRuntimeNode panel = child(engine.getRoot(), "panel");

    assertTrue(child(panel, "delete").allNames().contains("remove"));
    assertTrue(child(panel, "here").allNames().contains("movehere"));
    assertTrue(child(panel, "here").allNames().contains("tphere"));
    assertTrue(child(panel, "teleport").allNames().contains("tp"));
    assertTrue(child(panel, "web").allNames().contains("editweb"));
    assertTrue(child(panel, "web").allNames().contains("webedit"));
    assertTrue(child(panel, "menu").allNames().contains("root"));
    assertTrue(panel.allNames().contains("panels"));
    assertFalse(panel.allNames().contains("board"));
    assertFalse(panel.allNames().contains("boards"));
  }

  @Test
  public void importGroupExposesExplicitPreviewAndApplyModes() {
    DirectorRuntimeEngine engine = engine();
    DirectorRuntimeNode imports = child(engine.getRoot(), "import");

    assertNotNull(imports);
    Set<String> names = imports.getChildren().stream()
        .map(node -> node.getDescriptor().getName())
        .collect(Collectors.toUnmodifiableSet());
    assertEquals(Set.of("preview", "apply", "holoui", "legacy"), names);
    assertTrue(child(imports, "preview").allNames().contains("dry-run"));
  }

  @Test
  public void syncGroupExposesOperatorControlsForEverySenderSurface() {
    DirectorRuntimeEngine engine = engine();
    DirectorRuntimeNode sync = child(engine.getRoot(), "sync");
    DirectorRuntimeNode panel = child(engine.getRoot(), "panel");

    assertNotNull(sync);
    Set<String> names = sync.getChildren().stream()
        .map(node -> node.getDescriptor().getName())
        .collect(Collectors.toUnmodifiableSet());
    assertEquals(Set.of("list", "status", "revoke", "pull"), names);
    assertEquals(List.of("page", "sender"), parameterNames(child(sync, "list")));
    assertEquals(List.of("session", "sender"), parameterNames(child(sync, "status")));
    assertEquals(List.of("session", "sender"), parameterNames(child(sync, "revoke")));
    assertEquals(List.of("session", "sender"), parameterNames(child(sync, "pull")));
    assertTrue(child(sync, "pull").allNames().contains("poll"));
    assertTrue(child(panel, "web").allNames().contains("webedit"));
  }

  private static DirectorRuntimeEngine engine() {
    return DirectorEngineFactory.create(new CommandGloss(null));
  }

  private static DirectorRuntimeNode child(DirectorRuntimeNode parent, String name) {
    for (DirectorRuntimeNode node : parent.getChildren()) {
      if (node.allNames().contains(name)) {
        return node;
      }
    }
    return null;
  }

  private static DirectorRuntimeParameter parameter(DirectorRuntimeNode node, String name) {
    assertNotNull(node);
    return node.getParameters().stream()
        .filter(parameter -> parameter.getDescriptor().getName().equals(name))
        .findFirst()
        .orElseThrow();
  }

  private static List<String> parameterNames(DirectorRuntimeNode node) {
    assertNotNull(node);
    return node.getParameters().stream()
        .map(parameter -> parameter.getDescriptor().getName())
        .toList();
  }

  private static final class TestSender implements DirectorSender {
    @Override
    public boolean isPlayer() {
      return true;
    }

    @Override
    public String getName() {
      return "test";
    }

    @Override
    public void sendMessage(String message) {
    }
  }
}
