package art.arcane.gloss.config.action;

import art.arcane.gloss.api.HoloClickTrigger;
import art.arcane.gloss.enums.MenuActionType;
import art.arcane.gloss.menu.action.MenuAction;
import art.arcane.gloss.menu.action.PromptMenuAction;
import art.arcane.gloss.prompt.PromptRequest;
import com.google.gson.annotations.SerializedName;

import java.util.List;
import java.util.Locale;

/**
 * Asks the player for a line of text. Terminal: the editor owns the screen, and whatever should
 * happen with the answer goes in {@code then}, where {@code input.value} holds it.
 */
public record PromptActionData(String kind,
                               @SerializedName("var")
                               String variable,
                               String label, String initial, List<MenuActionData> then,
                               Integer timeoutTicks, HoloClickTrigger trigger,
                               String when, Integer cooldownTicks) implements MenuActionData {
  public PromptActionData {
    kind = kind == null ? PromptRequest.SIGN : kind.trim().toLowerCase(Locale.ROOT);
    if (!kind.equals(PromptRequest.SIGN) && !kind.equals(PromptRequest.ANVIL) && !kind.equals(PromptRequest.CHAT)) {
      throw new IllegalArgumentException("prompt kind must be sign, anvil or chat: " + kind);
    }
    variable = variable == null ? "" : variable.trim();
    label = label == null ? "" : label;
    initial = initial == null ? "" : initial;
    then = then == null ? List.of() : List.copyOf(then);
    timeoutTicks = timeoutTicks == null || timeoutTicks <= 0
        ? PromptRequest.DEFAULT_TIMEOUT_TICKS
        : Math.min(timeoutTicks, PromptRequest.MAX_TIMEOUT_TICKS);
  }

  @Override
  public MenuActionType getType() {
    return MenuActionType.PROMPT;
  }

  @Override
  public ActionEnvelope envelope() {
    return ActionEnvelope.of(when, cooldownTicks);
  }

  @Override
  public MenuAction<?> createAction() {
    return new PromptMenuAction(this);
  }

  @Override
  public String invalidReason() {
    return variable.isEmpty() && then.isEmpty()
        ? "declares a prompt that writes no variable and runs nothing"
        : null;
  }
}
