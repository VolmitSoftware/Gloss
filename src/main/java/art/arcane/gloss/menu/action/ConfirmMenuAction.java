package art.arcane.gloss.menu.action;

import art.arcane.gloss.config.action.ConfirmActionData;
import art.arcane.gloss.dialog.DialogService;

import java.util.Map;

/** Puts an ad-hoc yes/no dialog in front of the viewer and stops the list that asked for it. */
public final class ConfirmMenuAction extends MenuAction<ConfirmActionData> {

  private static final String AD_HOC_ID = "confirm";

  public ConfirmMenuAction(ConfirmActionData data) {
    super(data);
  }

  @Override
  public ActionOutcome execute(ActionContext context) {
    DialogService service = DialogService.active();
    if (service != null) {
      service.openAdHoc(context.player(), AD_HOC_ID, data.document(), Map.of());
    }
    return ActionOutcome.STOP;
  }
}
