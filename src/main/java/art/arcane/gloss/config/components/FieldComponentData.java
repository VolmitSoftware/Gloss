package art.arcane.gloss.config.components;

import art.arcane.gloss.api.IconDisplayStyle;
import art.arcane.gloss.config.MenuComponentData;
import art.arcane.gloss.enums.MenuComponentType;
import art.arcane.gloss.menu.MenuSession;
import art.arcane.gloss.menu.components.FieldComponent;
import art.arcane.gloss.menu.components.MenuComponent;
import com.google.gson.annotations.SerializedName;

import java.util.List;
import java.util.Locale;

/** A button that opens a text prompt and writes the answer into a session variable. */
public record FieldComponentData(
    @SerializedName("var")
    String variable,
    String prompt,
    String label,
    String initial,
    IconDisplayStyle style
) implements ComponentData {

  public static final List<String> PROMPTS = List.of("sign", "anvil", "chat");

  public FieldComponentData {
    variable = variable == null ? "" : variable.trim();
    if (!variable.matches("[a-z][a-z0-9_]*")) {
      throw new IllegalArgumentException("field var must match [a-z][a-z0-9_]*: " + variable);
    }
    prompt = prompt == null ? "sign" : prompt.trim().toLowerCase(Locale.ROOT);
    if (!PROMPTS.contains(prompt)) {
      throw new IllegalArgumentException("field prompt must be one of " + PROMPTS + ": " + prompt);
    }
    label = label == null ? "" : label;
    initial = initial == null ? "" : initial;
  }

  @Override
  public MenuComponentType getType() {
    return MenuComponentType.FIELD;
  }

  @Override
  public MenuComponent<?> createComponent(MenuSession session, MenuComponentData data) {
    return new FieldComponent(session, data);
  }
}
