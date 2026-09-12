package art.arcane.gloss.config.action;

import art.arcane.gloss.api.HoloClickTrigger;
import art.arcane.gloss.condition.ShowCondition;
import art.arcane.gloss.dialog.DialogBody;
import art.arcane.gloss.dialog.DialogButton;
import art.arcane.gloss.dialog.DialogDoc;
import art.arcane.gloss.dialog.DialogType;
import art.arcane.gloss.doc.DocumentEnvelope;
import art.arcane.gloss.enums.MenuActionType;
import art.arcane.gloss.menu.action.ConfirmMenuAction;
import art.arcane.gloss.menu.action.MenuAction;

import java.util.List;

/**
 * A yes/no question with no document behind it. The screen is a real confirmation dialog built at
 * load, so it goes through the same encoder, the same token and the same response path as an
 * authored one.
 */
public record ConfirmActionData(String title, String text, List<MenuActionData> yes, List<MenuActionData> no,
                                HoloClickTrigger trigger, String when,
                                Integer cooldownTicks) implements MenuActionData {
  public static final String YES_LABEL = "&aConfirm";
  public static final String NO_LABEL = "&cCancel";

  public ConfirmActionData {
    title = title == null ? "" : title;
    text = text == null || text.isBlank() ? null : text;
    yes = yes == null ? List.of() : List.copyOf(yes);
    no = no == null ? List.of() : List.copyOf(no);
  }

  /** The ad-hoc document this action opens. */
  public DialogDoc document() {
    List<DialogBody> body = text == null
        ? List.of()
        : List.of(new DialogBody(DialogBody.TEXT, text, null, null, null, null, null, null));
    return new DialogDoc(DialogDoc.CURRENT_SCHEMA_VERSION, DocumentEnvelope.INITIAL_REVISION,
        DialogType.CONFIRMATION, title, null, true, false, DialogDoc.AFTER_CLOSE,
        body, List.of(), List.of(),
        new DialogButton(YES_LABEL, null, null, yes),
        new DialogButton(NO_LABEL, null, null, no),
        null, null, null, List.of(), null, ShowCondition.ALWAYS, null, List.of());
  }

  @Override
  public MenuActionType getType() {
    return MenuActionType.CONFIRM;
  }

  @Override
  public ActionEnvelope envelope() {
    return ActionEnvelope.of(when, cooldownTicks);
  }

  @Override
  public MenuAction<?> createAction() {
    return new ConfirmMenuAction(this);
  }

  @Override
  public String invalidReason() {
    return yes.isEmpty() && no.isEmpty() ? "declares a confirm action with no actions on either answer" : null;
  }
}
