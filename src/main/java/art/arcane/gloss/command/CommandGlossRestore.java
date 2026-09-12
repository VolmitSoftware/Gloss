package art.arcane.gloss.command;

import art.arcane.gloss.Gloss;
import art.arcane.gloss.history.HistoryEntry;
import art.arcane.gloss.history.HistoryService;
import art.arcane.gloss.locale.GlossMessages;
import art.arcane.volmlib.util.director.annotations.Director;
import art.arcane.volmlib.util.director.annotations.Param;
import art.arcane.volmlib.util.localization.MessageArgument;
import org.bukkit.command.CommandSender;

import java.io.IOException;

@Director(name = "restore", descriptionKey = "command.help.restore.root",
    description = "Write a stored version back over the live document")
public final class CommandGlossRestore {
  public static final String PERMISSION = "gloss.history.restore";

  private final Gloss plugin;

  public CommandGlossRestore(Gloss plugin) {
    this.plugin = plugin;
  }

  /**
   * Async like {@code /gloss pack} and {@code /gloss import}: the first thing a restore does is take
   * the persistence write permit, and an editor publication can hold that for as long as it takes.
   * The runtime reload it ends with hops back to the global region on its own.
   */
  @Director(name = "document", aliases = {"version"},
      descriptionKey = "command.help.restore.root",
      description = "Write a stored version back over the live document")
  public void restore(
      @Param(name = "kind", descriptionKey = "command.help.history.arg_kind",
          description = "Document folder, such as boards or menus") String kind,
      @Param(name = "id", descriptionKey = "command.help.history.arg_id",
          description = "Document id") String id,
      @Param(name = "version", descriptionKey = "command.help.restore.arg_version",
          description = "Version stamp from /gloss history") String version,
      @Param(name = "sender", contextual = true) CommandSender sender) {
    if (GlossCommandMessages.denied(sender, PERMISSION)) {
      return;
    }
    HistoryService history = history();
    if (history == null) {
      GlossCommandMessages.send(sender, GlossMessages.HISTORY_DISABLED);
      return;
    }
    long stamp;
    try {
      stamp = Long.parseLong(version);
    } catch (NumberFormatException malformed) {
      unknown(sender, kind, id, version);
      return;
    }
    HistoryEntry entry = history.version(kind, id, stamp);
    if (entry == null) {
      unknown(sender, kind, id, version);
      return;
    }
    try {
      history.restore(kind, id, entry);
    } catch (IOException | RuntimeException failure) {
      GlossCommandMessages.send(sender, GlossMessages.RESTORE_FAILED,
          MessageArgument.untrusted("reason", CommandGlossPack.reason(failure)));
      return;
    }
    GlossCommandMessages.send(sender, GlossMessages.RESTORE_DONE,
        MessageArgument.untrusted("kind", kind),
        MessageArgument.untrusted("id", id),
        MessageArgument.untrusted("revision", version));
  }

  private void unknown(CommandSender sender, String kind, String id, String version) {
    GlossCommandMessages.send(sender, GlossMessages.RESTORE_UNKNOWN,
        MessageArgument.untrusted("kind", kind),
        MessageArgument.untrusted("id", id),
        MessageArgument.untrusted("revision", version));
  }

  private HistoryService history() {
    return plugin == null ? null : plugin.service(HistoryService.class);
  }
}
