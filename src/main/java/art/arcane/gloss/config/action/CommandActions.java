package art.arcane.gloss.config.action;

import art.arcane.gloss.doc.DocumentParsers;
import art.arcane.gloss.enums.MenuActionCommandSource;
import art.arcane.gloss.enums.MenuActionType;
import com.google.gson.JsonElement;

import java.util.Map;
import java.util.function.Predicate;

/** Finds {@code command} actions in document JSON, judging each the way the document parser reads it. */
public final class CommandActions {
  private CommandActions() {
  }

  /** True when {@code element} is a {@code command} action, whatever it runs as. */
  public static boolean isCommand(JsonElement element) {
    if (element == null || !element.isJsonObject()) {
      return false;
    }
    JsonElement type = element.getAsJsonObject().get("type");
    return type != null && type.isJsonPrimitive()
        && MenuActionType.COMMAND.getSerializedName().equals(type.getAsString());
  }

  /**
   * True when {@code element} is a {@code command} action that runs as the console. The source goes through the
   * parser's own enum adapter, which also takes the constant name ({@code GLOBAL}); an action that does not parse
   * counts as one.
   */
  public static boolean runsAsServer(JsonElement element) {
    if (!isCommand(element)) {
      return false;
    }
    try {
      // The type adapter removes "type" from the tree it reads, so it gets a copy.
      MenuActionData parsed = DocumentParsers.GSON.fromJson(element.deepCopy(), MenuActionData.class);
      return parsed instanceof CommandActionData command && command.sourceOrDefault() == MenuActionCommandSource.GLOBAL;
    } catch (RuntimeException unreadable) {
      return true;
    }
  }

  /** True when {@code test} holds for {@code element} or any object or array inside it. */
  public static boolean any(JsonElement element, Predicate<JsonElement> test) {
    if (element == null || element.isJsonNull() || element.isJsonPrimitive()) {
      return false;
    }
    if (test.test(element)) {
      return true;
    }
    if (element.isJsonArray()) {
      for (JsonElement child : element.getAsJsonArray()) {
        if (any(child, test)) {
          return true;
        }
      }
      return false;
    }
    for (Map.Entry<String, JsonElement> field : element.getAsJsonObject().entrySet()) {
      if (any(field.getValue(), test)) {
        return true;
      }
    }
    return false;
  }
}
