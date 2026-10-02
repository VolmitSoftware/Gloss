package art.arcane.gloss.config.menu;

import art.arcane.gloss.config.MenuComponentData;
import art.arcane.gloss.config.MenuDefinitionData;
import art.arcane.gloss.config.action.MenuActionData;
import art.arcane.gloss.config.components.ButtonComponentData;
import art.arcane.gloss.config.components.ComponentData;
import art.arcane.gloss.config.components.ToggleComponentData;
import art.arcane.gloss.menu.action.MenuAction;
import art.arcane.gloss.doc.DocumentParsers;
import com.google.gson.JsonElement;
import com.google.gson.JsonParser;

import java.util.List;

public final class MenuDocumentParser {
  private MenuDocumentParser() {
  }

  public static MenuDocument parse(String menuId, String source) {
    String requiredId = MenuIds.require(menuId);
    if (source == null || source.isEmpty()) {
      throw new IllegalArgumentException("menu document must not be empty");
    }
    JsonElement root = JsonParser.parseString(source);
    if (!root.isJsonObject()) {
      throw new IllegalArgumentException("menu document must be a JSON object");
    }
    MenuDefinitionData definition = DocumentParsers.GSON.fromJson(source, MenuDefinitionData.class);
    if (definition == null) {
      throw new IllegalArgumentException("menu document must not be null");
    }
    definition.setId(requiredId);
    precompileActions(definition.getId(), definition.getComponents());
    for (MenuDefinitionData.Variant variant : definition.getVariants()) {
      precompileActions(definition.getId(), variant.components());
    }
    return new MenuDocument(requiredId, MenuDocument.revisionOf(source), source, definition);
  }

  private static void precompileActions(String menuId, List<MenuComponentData> components) {
    if (components == null) {
      return;
    }
    for (MenuComponentData component : components) {
      if (component == null || component.data() == null) {
        continue;
      }
      ComponentData data = component.data();
      if (data instanceof ButtonComponentData button) {
        resolveActions(button.actions(), menuId, component.id());
      } else if (data instanceof ToggleComponentData toggle) {
        resolveActions(toggle.trueActions(), menuId, component.id());
        resolveActions(toggle.falseActions(), menuId, component.id());
      }
    }
  }

  private static void resolveActions(List<MenuActionData> actions, String menuId, String componentId) {
    MenuAction.resolve(actions, menuId, componentId);
  }
}
