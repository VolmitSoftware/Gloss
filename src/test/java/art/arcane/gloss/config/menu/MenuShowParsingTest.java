package art.arcane.gloss.config.menu;

import art.arcane.gloss.condition.ShowCondition;
import art.arcane.gloss.config.MenuDefinitionData;
import art.arcane.gloss.config.components.ToggleComponentData;
import art.arcane.gloss.expr.ExprScope;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import org.junit.Test;

import java.util.List;
import java.util.Map;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertThrows;
import static org.junit.Assert.assertTrue;

public class MenuShowParsingTest {
  @Test
  public void omittedAndNullShowDefaultToTrueOnMenusAndComponents() {
    for (String fields : List.of("", "\"show\": null,", "\"show\": true,")) {
      MenuDefinitionData menu = MenuDocumentParser.parse("show-test", source(fields, fields)).definition();
      assertSame(ShowCondition.ALWAYS, menu.getShow());
      assertSame(ShowCondition.ALWAYS, menu.getComponents().getFirst().show());
    }
  }

  @Test
  public void falseShowDoesNotReplaceToggleStateConditions() {
    JsonObject document = JsonParser.parseString(source("\"show\": false,", "\"show\": false,"))
        .getAsJsonObject();
    JsonObject component = document.getAsJsonArray("components").get(0).getAsJsonObject();
    component.add("data", JsonParser.parseString("""
        {
          "type": "toggle",
          "condition": "%player_world%",
          "expectedValue": "world",
          "trueActions": [],
          "falseActions": [],
          "trueIcon": {"type": "text", "text": "ON"},
          "falseIcon": {"type": "text", "text": "OFF"}
        }
        """));

    MenuDefinitionData menu = MenuDocumentParser.parse("show-test", document.toString()).definition();
    assertFalse(menu.getShow().matches(null));
    assertFalse(menu.getComponents().getFirst().show().matches(null));
    ToggleComponentData toggle = (ToggleComponentData) menu.getComponents().getFirst().data();
    assertEquals("%player_world%", toggle.condition());
    assertEquals("world", toggle.expectedValue());
  }

  @Test
  public void expressionStringsUseCurrentScopeValues() {
    MenuDefinitionData menu = MenuDocumentParser.parse("show-test", source(
        "\"show\": \"{{world.name == 'world'}}\",",
        "\"show\": \"world.time < 12000\",")).definition();
    ExprScope daytime = new Scope(Map.of("world.name", "world", "world.time", 6000D));
    ExprScope nighttimeElsewhere = new Scope(Map.of("world.name", "dungeon", "world.time", 18000D));

    assertTrue(menu.getShow().matches(daytime));
    assertTrue(menu.getComponents().getFirst().show().matches(daytime));
    assertFalse(menu.getShow().matches(nighttimeElsewhere));
    assertFalse(menu.getComponents().getFirst().show().matches(nighttimeElsewhere));
  }

  @Test
  public void invalidShowValuesRejectTheDocument() {
    assertThrows(IllegalArgumentException.class,
        () -> MenuDocumentParser.parse("show-test", source("\"show\": 1,", "")));
    assertThrows(IllegalArgumentException.class,
        () -> MenuDocumentParser.parse("show-test", source("", "\"show\": \"true && )\",")));
  }

  private static String source(String menuFields, String componentFields) {
    return """
        {
          %s
          "offset": [0, 1.7, 2.5],
          "particleLayers": [],
          "components": [{
            %s
            "id": "probe",
            "offset": [0, 0, 0],
            "data": {
              "type": "decoration",
              "icon": {"type": "text", "text": "probe"}
            }
          }]
        }
        """.formatted(menuFields, componentFields);
  }

  private record Scope(Map<String, Object> values) implements ExprScope {
    @Override
    public Object variable(String name) {
      return values.get(name);
    }

    @Override
    public Object call(String name, List<Object> arguments) {
      return null;
    }
  }
}
