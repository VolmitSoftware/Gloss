package art.arcane.gloss.menu.action;

import art.arcane.gloss.config.action.DialogActionData;
import art.arcane.gloss.dialog.DialogService;

/** Hands the viewer to a dialog; the flow belongs to that screen from here, so this stops the list. */
public final class DialogMenuAction extends MenuAction<DialogActionData> {

  public DialogMenuAction(DialogActionData data) {
    super(data);
  }

  @Override
  public ActionOutcome execute(ActionContext context) {
    DialogService service = DialogService.active();
    if (service != null) {
      service.open(context.player(), data.id(), data.args());
    }
    return ActionOutcome.STOP;
  }
}
