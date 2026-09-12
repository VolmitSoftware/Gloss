package art.arcane.gloss.config.action;

import art.arcane.gloss.api.HoloClickTrigger;
import art.arcane.gloss.enums.MenuActionType;
import art.arcane.gloss.menu.action.InventoryMenuAction;
import art.arcane.gloss.menu.action.MenuAction;

import java.util.Map;

/** Opens an {@code inventories/} chest menu, seeding {@code args.*} for the window it draws. */
public record InventoryActionData(String id, Map<String, Object> args, HoloClickTrigger trigger,
                                  String when, Integer cooldownTicks) implements MenuActionData {
  public InventoryActionData {
    args = args == null ? Map.of() : Map.copyOf(args);
  }

  @Override
  public MenuActionType getType() {
    return MenuActionType.INVENTORY;
  }

  @Override
  public ActionEnvelope envelope() {
    return ActionEnvelope.of(when, cooldownTicks);
  }

  @Override
  public MenuAction<?> createAction() {
    return new InventoryMenuAction(this);
  }

  @Override
  public String invalidReason() {
    return id == null || id.isBlank() ? "declares an inventory action without an id" : null;
  }
}
