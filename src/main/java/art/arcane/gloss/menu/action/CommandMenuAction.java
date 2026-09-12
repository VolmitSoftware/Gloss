package art.arcane.gloss.menu.action;

import art.arcane.gloss.Gloss;
import art.arcane.gloss.config.action.CommandActionData;
import art.arcane.gloss.enums.MenuActionCommandSource;
import art.arcane.gloss.menu.MenuExpressions;
import art.arcane.volmlib.util.scheduling.SchedulerUtils;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;

import java.util.logging.Level;

public class CommandMenuAction extends MenuAction<CommandActionData> {

  public CommandMenuAction(CommandActionData data) {
    super(data);
  }

  public boolean hasCommand() {
    return data.hasCommand();
  }

  @Override
  public ActionOutcome execute(ActionContext context) {
    String declaredCommand = data.command().trim();
    String resolved = declaredCommand.contains("{{")
        ? MenuExpressions.substitute(declaredCommand, context.conditionScope())
        : declaredCommand;
    String declared = personalizeCommand(resolved, context.player());
    String command = declared.startsWith("/") ? declared.substring(1) : declared;
    if (data.sourceOrDefault() == MenuActionCommandSource.PLAYER) {
      context.player().performCommand(command);
      return ActionOutcome.CONTINUE;
    }
    if (!consoleSafe(command)) {
      Gloss.logThrottled(Level.WARNING, "console-command-" + context.menuId() + "/" + context.componentId(),
          "Component \"%s\" of %s built a console command containing a command separator after substitution; it was not run.",
          context.componentId(), context.menuId());
      return ActionOutcome.CONTINUE;
    }
    SchedulerUtils.runGlobal(Gloss.instance, () -> Bukkit.getServer().dispatchCommand(Bukkit.getServer().getConsoleSender(), command));
    return ActionOutcome.CONTINUE;
  }

  /**
   * A {@code server} command runs as console, so the substituted text may not contain the
   * characters that start a second command. Arguments are player-supplied in every surface that
   * takes them, and escaping them would still leave the operator guessing what actually ran.
   */
  public static boolean consoleSafe(String command) {
    return command != null && command.indexOf(';') < 0 && command.indexOf('\n') < 0
        && command.indexOf('\r') < 0 && command.indexOf('/') < 0;
  }

  static String personalizeCommand(String command, Player player) {
    String playerName = player.getName();
    return command.replace("%player_name%", playerName).replace("%player%", playerName);
  }
}
