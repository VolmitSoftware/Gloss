package art.arcane.gloss.command;

import art.arcane.gloss.Gloss;
import art.arcane.gloss.dialog.DialogRuntime;
import art.arcane.gloss.dialog.DialogService;
import art.arcane.gloss.locale.GlossMessages;
import art.arcane.gloss.menu.MenuArguments;
import art.arcane.volmlib.util.collection.KList;
import art.arcane.volmlib.util.director.DirectorParameterHandler;
import art.arcane.volmlib.util.director.annotations.Director;
import art.arcane.volmlib.util.director.annotations.Param;
import art.arcane.volmlib.util.director.exceptions.DirectorParsingException;
import art.arcane.volmlib.util.director.help.DirectorMiniMenu;
import art.arcane.volmlib.util.localization.MessageArgs;
import org.bukkit.Bukkit;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/** {@code /gloss dialog} — lists, inspects and opens the {@code dialogs/} documents. */
@Director(name = "dialog", aliases = {"dialogs"}, description = "Inspect and open dialog documents",
    descriptionKey = "command.help.dialog.root")
public class CommandGlossDialog {
  private static final String LIST_COMMAND = "/gloss dialog list";

  private final Gloss plugin;

  public CommandGlossDialog(Gloss plugin) {
    this.plugin = plugin;
  }

  @Director(name = "list", description = "List configured dialogs", descriptionKey = "command.help.dialog.list")
  public void list(
      @Param(name = "page", defaultValue = "1", description = "One-based list page",
          descriptionKey = "command.help.dialog.list.page")
      int page,
      @Param(name = "sender", contextual = true)
      CommandSender sender
  ) {
    if (GlossCommandMessages.denied(sender, "gloss.dialogs")) {
      return;
    }
    DialogService service = service();
    List<String> ids = service == null ? List.of() : service.ids();
    if (ids.isEmpty()) {
      plugin.getLocalization().send(sender, GlossMessages.FORMS_NO_DIALOGS);
      return;
    }
    DirectorMiniMenu.ContentPage window = GlossCommandPager.window(ids.size(), page, GlossCommandPager.TEXT_PAGE_SIZE);
    DirectorMiniMenu.Theme theme = GlossCommandService.menuTheme();
    List<String> lines = new ArrayList<>();
    GlossCommandPager.appendHeader(lines, plugin.getLocalization().text(GlossMessages.FORMS_DIALOG_LIST_HEADER),
        window, theme);
    for (String id : ids.subList(window.startIndex(), window.endIndex())) {
      lines.add(GlossCommandPager.entry(id, plugin.getLocalization().text(GlossMessages.FORMS_DIALOG_LIST_ENTRY,
          MessageArgs.builder().untrusted("id", id).build()), theme));
    }
    GlossCommandPager.appendFooter(lines, window, LIST_COMMAND, theme);
    DirectorMiniMenu.deliver(sender, lines);
  }

  @Director(name = "info", description = "Show a dialog's type, inputs and buttons",
      descriptionKey = "command.help.dialog.info")
  public void info(
      @Param(name = "dialog", description = "Dialog id", descriptionKey = "command.help.dialog.info.id",
          customHandler = DialogIdHandler.class)
      String id,
      @Param(name = "sender", contextual = true)
      CommandSender sender
  ) {
    if (GlossCommandMessages.denied(sender, "gloss.dialogs.info")) {
      return;
    }
    DialogRuntime runtime = runtime(sender, id);
    if (runtime == null) {
      return;
    }
    plugin.getLocalization().send(sender, GlossMessages.FORMS_DIALOG_INFO, MessageArgs.builder()
        .untrusted("id", id)
        .untrusted("kind", runtime.doc().type().name().toLowerCase(java.util.Locale.ROOT))
        .untrusted("count", String.valueOf(runtime.doc().clickableButtons().size()))
        .build());
  }

  @Director(name = "open", description = "Open a dialog for a player", descriptionKey = "command.help.dialog.open")
  public void open(
      @Param(name = "dialog", description = "Dialog id", descriptionKey = "command.help.dialog.open.id",
          customHandler = DialogIdHandler.class)
      String id,
      @Param(name = "player", defaultValue = "", description = "Player to open it for; defaults to you",
          descriptionKey = "command.help.dialog.open.player")
      String playerName,
      @Param(name = "args", defaultValue = "", description = "Space-separated key=value arguments",
          descriptionKey = "command.help.dialog.open.args")
      String args,
      @Param(name = "sender", contextual = true)
      CommandSender sender
  ) {
    if (GlossCommandMessages.denied(sender, "gloss.dialogs.open")) {
      return;
    }
    Player target = target(sender, playerName);
    if (target == null) {
      return;
    }
    Map<String, Object> arguments;
    try {
      arguments = MenuArguments.parse(args);
    } catch (IllegalArgumentException badArguments) {
      plugin.getLocalization().send(sender, GlossMessages.FORMS_BAD_ARGS,
          MessageArgs.builder().untrusted("value", badArguments.getMessage()).build());
      return;
    }
    DialogService service = service();
    if (service == null || runtime(sender, id) == null) {
      return;
    }
    if (!service.open(target, id, arguments)) {
      plugin.getLocalization().send(sender, GlossMessages.FORMS_DIALOG_UNSUPPORTED);
      return;
    }
    plugin.getLocalization().send(sender, GlossMessages.FORMS_DIALOG_OPENED, MessageArgs.builder()
        .untrusted("id", id)
        .untrusted("player", target.getName())
        .build());
  }

  @Director(name = "reset", description = "Restore shipped dialog defaults", descriptionKey = "command.help.dialog.reset")
  public void reset(
      @Param(name = "sender", contextual = true)
      CommandSender sender,
      @Param(name = "name", defaultValue = "*", description = "Name to reset, or * for every shipped default",
          descriptionKey = "command.help.dialog.reset.name")
      String name
  ) {
    if (GlossCommandMessages.denied(sender, "gloss.dialogs.reset")) {
      return;
    }
    DialogService service = service();
    GlossCommandMessages.sendResetResult(sender, "dialog", name,
        service == null ? List.of() : service.resetToDefault(name));
  }

  private DialogService service() {
    return plugin.service(DialogService.class);
  }

  private DialogRuntime runtime(CommandSender sender, String id) {
    DialogService service = service();
    DialogRuntime runtime = service == null ? null : service.runtime(id);
    if (runtime == null) {
      plugin.getLocalization().send(sender, GlossMessages.FORMS_UNKNOWN_DIALOG,
          MessageArgs.builder().untrusted("id", id).build());
    }
    return runtime;
  }

  private Player target(CommandSender sender, String playerName) {
    if (playerName != null && !playerName.isBlank()) {
      Player named = Bukkit.getPlayerExact(playerName.trim());
      if (named == null) {
        plugin.getLocalization().send(sender, GlossMessages.COMMAND_PLAYER_ONLY);
      }
      return named;
    }
    if (sender instanceof Player self) {
      return self;
    }
    plugin.getLocalization().send(sender, GlossMessages.COMMAND_PLAYER_ONLY);
    return null;
  }

  /** Completes on the dialog ids currently loaded. */
  public static final class DialogIdHandler implements DirectorParameterHandler<String> {
    @Override
    public KList<String> getPossibilities() {
      KList<String> out = new KList<>();
      Gloss plugin = Gloss.instance;
      DialogService service = plugin == null ? null : plugin.service(DialogService.class);
      if (service != null) {
        out.addAll(service.ids());
      }
      return out;
    }

    @Override
    public String toString(String value) {
      return value == null ? "" : value;
    }

    @Override
    public String parse(String in, boolean force) throws DirectorParsingException {
      if (in == null || in.isBlank()) {
        throw new DirectorParsingException("dialog id is required");
      }
      return in.strip();
    }

    @Override
    public boolean supports(Class<?> type) {
      return type == String.class;
    }
  }
}
