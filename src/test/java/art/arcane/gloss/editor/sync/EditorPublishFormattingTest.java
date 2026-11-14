package art.arcane.gloss.editor.sync;

import art.arcane.gloss.panel.PanelDefinition;
import art.arcane.gloss.panel.PanelTransform;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.google.gson.JsonPrimitive;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * A publish writes the keys it changed into the file and leaves every other byte alone, whatever the editor's own
 * encoding did to the untouched values (the browser writes {@code 30.0} as {@code 30}).
 */
class EditorPublishFormattingTest {
  private static final int MIB = 1024 * 1024;

  private static final String HOLOGRAM = """
      {
        "schemaVersion" : 3,
        "revision":3,
        // shown above spawn
        "anchor": {"world": "world", "position": [30.0, 1e3, -0.0]},

        "lines":    ["Hello"],
        "particleLayers": [],
        "style": {
          "billboard": "center",
          "seeThrough": true,
          "scaleX": 1.50,   "scaleY": 30.0,
          "scaleZ": 1.0
        },
        "box": {"enabled": false, "padding": 4, "borderWidth": 1,
                "backgroundArgb": "#B31B1B22", "borderArgb": "#FFAAAAAA"}
      }
      """;

  private static final String MENU = """
      {
        "offset":   [30.0, 1.50, -0.0],
        // keeps the menu in front of the player
        "lockPosition": false,
        "followPlayer": false,
        "closeOnDeath": true,
        "closeOnTeleport": true,
        "particleLayers": [],
        "components": [
          {
            "id": "title",
            "offset": [0.0, 0.35, 0.0],
            "data": {"type": "decoration", "icon": {"type": "text", "text": "&6Shop"}}
          },

          {
            "id": "close",
            "offset": [0.0, -0.35, 0.0],
            "data": {
              "type": "button",
              "highlightModifier": 0.05,
              "hoverDurationTicks": 1e1,
              "icon": {"type": "text", "text": "&cClose"},
              "actions": [{"type": "sound", "sound": "ui.button.click", "source": "master", "volume": 1.0, "pitch": 1.0}]
            }
          }
        ]
      }
      """;

  @TempDir
  Path data;

  @Test
  void aHologramEditKeepsEveryUntouchedByte() throws IOException {
    Path file = write("holograms/spawn.json", HOLOGRAM);
    JsonObject edited = JsonParser.parseString(HOLOGRAM).getAsJsonObject();
    edited.getAsJsonArray("lines").set(0, new JsonPrimitive("Changed"));

    publish("hologram", "spawn", HOLOGRAM, browserText(edited));

    assertEquals(HOLOGRAM.replace("\"revision\":3", "\"revision\":4").replace("[\"Hello\"]", "[\"Changed\"]"),
        Files.readString(file));
  }

  @Test
  void aChangedNumberKeepsTheSpellingItReplaces() throws IOException {
    Path file = write("holograms/spawn.json", HOLOGRAM);
    JsonObject edited = JsonParser.parseString(HOLOGRAM).getAsJsonObject();
    JsonObject style = edited.getAsJsonObject("style");
    style.addProperty("scaleY", 45);
    style.addProperty("scaleX", 2.5);
    edited.getAsJsonObject("anchor").getAsJsonArray("position").set(1, new JsonPrimitive(2000));

    publish("hologram", "spawn", HOLOGRAM, browserText(edited));

    assertEquals(HOLOGRAM.replace("\"revision\":3", "\"revision\":4")
            .replace("\"scaleY\": 30.0", "\"scaleY\": 45.0")
            .replace("\"scaleX\": 1.50", "\"scaleX\": 2.50")
            .replace("[30.0, 1e3, -0.0]", "[30.0, 2e3, -0.0]"),
        Files.readString(file));
  }

  @Test
  void aMenuEditKeepsEveryUntouchedByte() throws IOException {
    Path file = write("menus/shop.json", MENU);
    JsonObject edited = JsonParser.parseString(MENU).getAsJsonObject();
    edited.getAsJsonArray("components").get(0).getAsJsonObject().getAsJsonObject("data")
        .getAsJsonObject("icon").addProperty("text", "&6Store");

    publish("menu", "shop", MENU, browserText(edited));

    assertEquals(MENU.replace("&6Shop", "&6Store"), Files.readString(file));
  }

  @Test
  void aPanelEditKeepsEveryUntouchedByte() throws IOException {
    write("menus/shop.json", MENU);
    PanelDefinition panel = PanelDefinition.create("lobby", "shop", new PanelTransform("minecraft:overworld",
        UUID.fromString("00000000-0000-0000-0000-000000000001"), 30.0D, 1000.0D, -0.0D, 0.0D, 0.0D, 0.0D, 1.5D));
    String source = new GsonBuilder().serializeNulls().disableHtmlEscaping().setPrettyPrinting().create()
        .toJson(panel)
        .replace("\"y\": 1000.0", "\"y\": 1e3")
        .replace("\"z\": 0.0", "\"z\": -0.0")
        .replace("\"scale\": 1.5", "\"scale\":   1.50")
        .replace("  \"rootMenuId\"", "  // opens the shop\n\n  \"rootMenuId\"")
        + "\n";
    Path file = write("panels/lobby.json", source);
    JsonObject edited = JsonParser.parseString(source).getAsJsonObject();
    edited.getAsJsonObject("transform").addProperty("yaw", 90);

    publish("panel", "lobby", source, EditorSyncJson.canonical(edited));

    assertEquals(source.replace("\"revision\": 1", "\"revision\": 2").replace("\"yaw\": 0.0", "\"yaw\": 90.0"),
        Files.readString(file));
  }

  private Path write(String relative, String text) throws IOException {
    Path file = data.resolve(relative);
    Files.createDirectories(file.getParent());
    Files.writeString(file, text, StandardCharsets.UTF_8);
    return file;
  }

  private void publish(String kind, String id, String base, String json) {
    EditorWorkspace workspace = EditorWorkspaceFixtures.detached(data, 8 * MIB);
    EditorWorkspace.Outcome outcome = workspace.publish("chg_format", new EditorSyncChangeset(List.of(
        new EditorSyncChangeset.DocumentChange(false, kind, id, EditorSyncDocuments.contentRevision(base), json)),
        List.of()), Map.of());
    assertEquals("applied", outcome.status(), outcome.message());
  }

  /**
   * The document as the browser editor sends it: two-space indent, numeric arrays on one line, and whole numbers
   * without a fraction, since a JavaScript number cannot tell {@code 30.0} from {@code 30}.
   */
  static String browserText(JsonElement value) {
    return browserText(value, "");
  }

  private static String browserText(JsonElement value, String indent) {
    if (value.isJsonNull()) {
      return "null";
    }
    if (value.isJsonPrimitive()) {
      JsonPrimitive primitive = value.getAsJsonPrimitive();
      if (!primitive.isNumber()) {
        return primitive.toString();
      }
      double number = primitive.getAsDouble();
      return number == Math.rint(number) && Math.abs(number) < 1e15 ? Long.toString((long) number)
          : Double.toString(number);
    }
    String inner = indent + "  ";
    StringBuilder text = new StringBuilder();
    if (value.isJsonArray()) {
      if (value.getAsJsonArray().isEmpty()) {
        return "[]";
      }
      boolean numbers = value.getAsJsonArray().asList().stream()
          .allMatch((JsonElement item) -> item.isJsonPrimitive() && item.getAsJsonPrimitive().isNumber());
      if (numbers) {
        List<String> items = value.getAsJsonArray().asList().stream()
            .map((JsonElement item) -> browserText(item, inner)).toList();
        return "[" + String.join(", ", items) + "]";
      }
      text.append("[\n");
      List<JsonElement> items = value.getAsJsonArray().asList();
      for (int index = 0; index < items.size(); index++) {
        text.append(inner).append(browserText(items.get(index), inner)).append(index + 1 < items.size() ? ",\n" : "\n");
      }
      return text.append(indent).append(']').toString();
    }
    JsonObject object = value.getAsJsonObject();
    if (object.isEmpty()) {
      return "{}";
    }
    text.append("{\n");
    List<String> keys = List.copyOf(object.keySet());
    for (int index = 0; index < keys.size(); index++) {
      text.append(inner).append(new JsonPrimitive(keys.get(index))).append(": ")
          .append(browserText(object.get(keys.get(index)), inner)).append(index + 1 < keys.size() ? ",\n" : "\n");
    }
    return text.append(indent).append('}').toString();
  }
}
