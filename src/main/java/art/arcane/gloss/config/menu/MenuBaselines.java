package art.arcane.gloss.config.menu;

import art.arcane.gloss.doc.ShippedResources;
import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;

public final class MenuBaselines {
  public static final String BLANK_HOLOGRAM_RESOURCE = "/baselines/menu-blank.json";

  private static final Gson GSON = new GsonBuilder()
      .disableHtmlEscaping()
      .setPrettyPrinting()
      .create();

  private MenuBaselines() {
  }

  public static String blankHologramSource() {
    return ShippedResources.readText(BLANK_HOLOGRAM_RESOURCE);
  }

  public static String simpleHologramSource(String menuId, String text) {
    String id = MenuIds.require(menuId);
    String content = text == null || text.isBlank() ? "&f" + id : text.strip();

    JsonObject icon = new JsonObject();
    icon.addProperty("type", "text");
    icon.addProperty("text", content);
    JsonObject style = new JsonObject();
    style.addProperty("billboard", "vertical");
    icon.add("style", style);

    JsonObject data = new JsonObject();
    data.addProperty("type", "decoration");
    data.add("icon", icon);

    JsonObject component = new JsonObject();
    component.addProperty("id", "content");
    component.add("offset", vector(0.0D, 0.0D, 0.0D));
    component.add("data", data);

    JsonObject document = new JsonObject();
    document.add("offset", vector(0.0D, 1.7D, 0.0D));
    document.addProperty("lockPosition", false);
    document.addProperty("followPlayer", false);
    document.addProperty("closeOnDeath", true);
    document.addProperty("closeOnTeleport", true);
    JsonArray components = new JsonArray();
    components.add(component);
    document.add("components", components);
    return GSON.toJson(document) + System.lineSeparator();
  }

  private static JsonArray vector(double x, double y, double z) {
    JsonArray vector = new JsonArray();
    vector.add(x);
    vector.add(y);
    vector.add(z);
    return vector;
  }
}
