package art.arcane.gloss.command;

import art.arcane.gloss.Gloss;
import art.arcane.gloss.history.HistoryEntry;
import art.arcane.gloss.history.HistoryService;
import art.arcane.gloss.locale.GlossMessages;
import art.arcane.volmlib.util.director.annotations.Director;
import art.arcane.volmlib.util.director.annotations.Param;
import art.arcane.volmlib.util.director.help.DirectorMiniMenu;
import art.arcane.volmlib.util.localization.MessageArgument;
import org.bukkit.command.CommandSender;

import java.util.List;

@Director(name = "history", descriptionKey = "command.help.history.root",
    description = "List the stored versions of a document")
public final class CommandGlossHistory {
  public static final String PERMISSION = "gloss.history";

  private final Gloss plugin;

  public CommandGlossHistory(Gloss plugin) {
    this.plugin = plugin;
  }

  @Director(name = "list", aliases = {"versions"}, descriptionKey = "command.help.history.root",
      description = "List the stored versions of a document")
  public void list(
      @Param(name = "kind", descriptionKey = "command.help.history.arg_kind",
          description = "Document folder, such as boards or menus") String kind,
      @Param(name = "id", descriptionKey = "command.help.history.arg_id",
          description = "Document id") String id,
      @Param(name = "page", defaultValue = "1", descriptionKey = "command.help.history.arg_page",
          description = "One-based list page") int page,
      @Param(name = "sender", contextual = true) CommandSender sender) {
    if (GlossCommandMessages.denied(sender, PERMISSION)) {
      return;
    }
    HistoryService history = history();
    if (history == null) {
      GlossCommandMessages.send(sender, GlossMessages.HISTORY_DISABLED);
      return;
    }
    List<HistoryEntry> versions = history.versions(kind, id);
    if (versions.isEmpty()) {
      GlossCommandMessages.send(sender, GlossMessages.HISTORY_EMPTY,
          MessageArgument.untrusted("kind", kind),
          MessageArgument.untrusted("id", id));
      return;
    }
    DirectorMiniMenu.ContentPage window = GlossCommandPager.window(versions.size(), page,
        GlossCommandPager.TEXT_PAGE_SIZE);
    for (HistoryEntry entry : versions.subList(window.startIndex(), window.endIndex())) {
      GlossCommandMessages.send(sender, GlossMessages.HISTORY_ENTRY,
          MessageArgument.untrusted("revision", entry.version()),
          MessageArgument.untrusted("source", entry.source()),
          MessageArgument.trusted("count", entry.bytes()));
    }
  }

  private HistoryService history() {
    return plugin == null ? null : plugin.service(HistoryService.class);
  }
}
