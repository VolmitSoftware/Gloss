package art.arcane.gloss.menu.components;

import art.arcane.gloss.api.HoloClickTrigger;
import art.arcane.gloss.config.MenuComponentData;
import art.arcane.gloss.config.components.ButtonComponentData;
import art.arcane.gloss.menu.MenuSession;
import art.arcane.gloss.menu.action.MenuAction;
import art.arcane.gloss.menu.icon.MenuIcon;

import java.util.List;

public class ButtonComponent extends ClickableComponent<ButtonComponentData> {

  private final List<MenuAction<?>> actions;
  private final TooltipPane tooltip;

  public ButtonComponent(MenuSession session, MenuComponentData data) {
    super(session, data,
        ((ButtonComponentData) data.data()).highlightMod(),
        ((ButtonComponentData) data.data()).hitbox(),
        ((ButtonComponentData) data.data()).resolvedHoverDurationTicks(),
        ((ButtonComponentData) data.data()).resolvedHoverEasing());
    this.actions = MenuAction.resolve(this.data.actions(), session.getId(), getId());
    this.tooltip = this.data.tooltip() == null ? null : new TooltipPane(session, this.data.tooltip());
  }

  @Override
  protected void onTick(org.bukkit.util.Vector eyeOrigin, org.bukkit.util.Vector eyeDirection) {
    super.onTick(eyeOrigin, eyeDirection);
    if (tooltip != null) {
      tooltip.tick(isSelected(), getLocation());
    }
  }

  @Override
  public void onClose() {
    super.onClose();
    if (tooltip != null) {
      tooltip.hide();
    }
  }

  @Override
  public MenuIcon<?> createIcon() {
    return MenuIcon.createIcon(session, location, data.iconData(), this);
  }

  @Override
  public void onClick(HoloClickTrigger trigger) {
    if (!isInteractable()) {
      return;
    }
    MenuAction.execute(actions, session.actionContext(getId(), trigger));
  }
}
