package art.arcane.gloss.menu.action;

import art.arcane.gloss.config.action.PromptActionData;
import art.arcane.gloss.prompt.PromptRequest;
import art.arcane.gloss.prompt.PromptService;

/** Opens a text editor and stops the list; the rest of the flow is the prompt's {@code then}. */
public final class PromptMenuAction extends MenuAction<PromptActionData> {

  private final java.util.List<MenuAction<?>> then;

  public PromptMenuAction(PromptActionData data) {
    super(data);
    this.then = MenuAction.resolve(data.then(), "prompt", data.variable());
  }

  @Override
  public ActionOutcome execute(ActionContext context) {
    PromptService prompts = PromptService.active();
    if (prompts != null) {
      prompts.prompt(context.player(), new PromptRequest(data.kind(), data.variable(), data.label(),
          data.initial(), then, data.timeoutTicks(), context));
    }
    return ActionOutcome.STOP;
  }
}
