package art.arcane.gloss.menu.action;

import art.arcane.gloss.config.action.DialogActionData;
import art.arcane.gloss.prompt.DialogDefinition;
import art.arcane.gloss.prompt.PromptService;

public final class DialogMenuAction extends MenuAction<DialogActionData> {
    private final DialogDefinition definition;

    public DialogMenuAction(DialogActionData data) {
        super(data);
        this.definition = new DialogDefinition(data);
    }

    @Override
    public ActionOutcome execute(ActionContext context) {
        PromptService prompts = PromptService.active();
        if (prompts == null || !prompts.dialog(context, definition)) {
            MenuAction.execute(definition.unsupported(), context);
        }
        return ActionOutcome.STOP;
    }
}
