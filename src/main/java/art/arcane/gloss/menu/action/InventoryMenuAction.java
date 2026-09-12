package art.arcane.gloss.menu.action;

import art.arcane.gloss.config.action.InventoryActionData;
import art.arcane.gloss.inventory.InventoryMenuService;

/** Hands the viewer to a chest menu; that window owns the flow, so this stops the list. */
public final class InventoryMenuAction extends MenuAction<InventoryActionData> {

  public InventoryMenuAction(InventoryActionData data) {
    super(data);
  }

  @Override
  public ActionOutcome execute(ActionContext context) {
    InventoryMenuService service = InventoryMenuService.active();
    if (service != null) {
      service.open(context.player(), data.id(), data.args());
    }
    return ActionOutcome.STOP;
  }
}
