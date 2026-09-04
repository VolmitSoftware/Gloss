package art.arcane.gloss.menu.components;

import art.arcane.gloss.api.HoloClickTrigger;
import art.arcane.gloss.config.MenuComponentData;
import art.arcane.gloss.config.components.ToggleComponentData;
import art.arcane.gloss.menu.MenuSession;
import art.arcane.gloss.menu.action.ActionContext;
import art.arcane.gloss.menu.action.ActionOutcome;
import art.arcane.gloss.menu.action.MenuAction;
import art.arcane.gloss.menu.icon.MenuIcon;
import art.arcane.gloss.text.TextPipeline;
import com.google.common.collect.Lists;

import java.util.List;

public class ToggleComponent extends ClickableComponent<ToggleComponentData> {

  private final String condition, expected;
  private final MenuIcon<?> trueIcon, falseIcon;
  private final List<MenuAction<?>> trueActions, falseActions;

  private boolean state;

  public ToggleComponent(MenuSession session, MenuComponentData data) {
    super(session, data,
        ((ToggleComponentData) data.data()).highlightMod(),
        ((ToggleComponentData) data.data()).hitbox(),
        ((ToggleComponentData) data.data()).resolvedHoverDurationTicks(),
        ((ToggleComponentData) data.data()).resolvedHoverEasing());
    this.condition = this.data.condition();
    this.expected = this.data.expectedValue();
    this.trueIcon = MenuIcon.createIcon(session, location, this.data.trueIcon(), this);
    this.falseIcon = MenuIcon.createIcon(session, location, this.data.falseIcon(), this);
    this.trueActions = MenuAction.resolve(this.data.trueActions(), session.getId(), getId());
    this.falseActions = MenuAction.resolve(this.data.falseActions(), session.getId(), getId());

    state = isValid();
  }

  @Override
  public void onClick(HoloClickTrigger trigger) {
    if (!isInteractable()) {
      return;
    }
    ActionContext context = session.actionContext(getId(), trigger);
    if (state) {
      if (MenuAction.execute(falseActions, context) == ActionOutcome.STOP)
        return;
      swapIcon(falseIcon);
      state = false;
    } else {
      if (MenuAction.execute(trueActions, context) == ActionOutcome.STOP)
        return;
      swapIcon(trueIcon);
      state = true;
    }
  }

  @Override
  protected MenuIcon<?> createIcon() {
    falseIcon.applyTransform(location);
    trueIcon.applyTransform(location);
    return state ? trueIcon : falseIcon;
  }

  @Override
  public void applyTransform() {
    super.applyTransform();
    if (falseIcon != currentIcon) {
      falseIcon.applyTransform(location);
    }
    if (trueIcon != currentIcon) {
      trueIcon.applyTransform(location);
    }
  }

  private boolean isValid() {
    String renderedCondition = TextPipeline.menuText(session.getPlayer(), condition);
    String renderedExpected = expected == null ? null : TextPipeline.menuText(session.getPlayer(), expected);
    return renderedCondition.equalsIgnoreCase(renderedExpected);
  }
}
