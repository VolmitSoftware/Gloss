package art.arcane.gloss.menu.components;

import art.arcane.gloss.api.HoloClickTrigger;
import art.arcane.gloss.config.MenuComponentData;
import art.arcane.gloss.config.components.FieldComponentData;
import art.arcane.gloss.config.icon.TextIconData;
import art.arcane.gloss.menu.MenuSession;
import art.arcane.gloss.menu.icon.MenuIcon;
import art.arcane.gloss.prompt.PromptRequest;
import art.arcane.gloss.prompt.PromptService;

import java.util.List;

/**
 * A button whose click opens a text prompt. The menu stays open behind it: a sign or anvil editor
 * is a client screen, and the answer lands back in the session variable the field names.
 */
public final class FieldComponent extends ClickableComponent<FieldComponentData> {

  public FieldComponent(MenuSession session, MenuComponentData data) {
    super(session, data, 0F, null, 0, art.arcane.gloss.config.components.HoverEasing.resolve(null));
  }

  @Override
  public MenuIcon<?> createIcon() {
    return MenuIcon.createIcon(session, location, new TextIconData(data.label(), data.style(), null, null), this);
  }

  @Override
  public void onClick(HoloClickTrigger trigger) {
    if (!isInteractable()) {
      return;
    }
    PromptService prompts = PromptService.active();
    if (prompts == null) {
      return;
    }
    prompts.prompt(session.getPlayer(), new PromptRequest(data.prompt(), data.variable(), data.label(),
        currentValue(), List.of(), PromptRequest.DEFAULT_TIMEOUT_TICKS,
        session.actionContext(getId(), trigger)));
  }

  private String currentValue() {
    Object value = session.variables().get(data.variable());
    return value == null ? data.initial() : String.valueOf(value);
  }
}
