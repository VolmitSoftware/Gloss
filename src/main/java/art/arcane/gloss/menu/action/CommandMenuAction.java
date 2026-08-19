package art.arcane.gloss.menu.action;

import art.arcane.gloss.Gloss;
import art.arcane.gloss.config.action.CommandActionData;
import art.arcane.gloss.enums.MenuActionCommandSource;
import art.arcane.volmlib.util.scheduling.SchedulerUtils;
import org.bukkit.Bukkit;

public class CommandMenuAction extends MenuAction<CommandActionData> {

  public CommandMenuAction(CommandActionData data) {
    super(data);
  }

  public boolean hasCommand() {
    if (data.command() == null) {
      return false;
    }
    String command = data.command().trim();
    return !command.isEmpty() && !command.equals("/");
  }

  @Override
  public ActionOutcome execute(ActionContext context) {
    String declared = data.command().trim();
    String command = declared.startsWith("/") ? declared.substring(1) : declared;
    if (data.sourceOrDefault() == MenuActionCommandSource.PLAYER)
      context.player().performCommand(command);
    else
      SchedulerUtils.runGlobal(Gloss.instance, () -> Bukkit.getServer().dispatchCommand(Bukkit.getServer().getConsoleSender(), command));
    return ActionOutcome.CONTINUE;
  }
}
