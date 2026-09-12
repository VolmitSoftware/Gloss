package art.arcane.gloss.command;

import art.arcane.gloss.Gloss;
import art.arcane.gloss.glosspack.GlossPackArchive;
import art.arcane.gloss.glosspack.GlossPackLedger;
import art.arcane.gloss.glosspack.GlossPackPreview;
import art.arcane.gloss.glosspack.GlossPackService;
import art.arcane.gloss.locale.GlossMessages;
import art.arcane.volmlib.util.director.annotations.Director;
import art.arcane.volmlib.util.director.annotations.Param;
import art.arcane.volmlib.util.localization.MessageArgument;
import org.bukkit.command.CommandSender;

import java.util.List;
import java.util.Locale;

@Director(name = "pack", aliases = {"packs"}, descriptionKey = "command.help.pack.root",
    description = "Install and manage Gloss packs")
public final class CommandGlossPack {
  public static final String PERMISSION = "gloss.packs";
  public static final String INSTALL_PERMISSION = "gloss.packs.install";
  public static final String UPDATE_PERMISSION = "gloss.packs.update";
  public static final String REMOVE_PERMISSION = "gloss.packs.remove";

  private static final int MAX_REPORTED_OUTCOMES = 12;

  private final Gloss plugin;

  public CommandGlossPack(Gloss plugin) {
    this.plugin = plugin;
  }

  @Director(name = "install", descriptionKey = "command.help.pack.install",
      description = "Install a pack from a file or an HTTPS URL")
  public void install(
      @Param(name = "source", descriptionKey = "command.help.pack.arg_source",
          description = "Pack file under the Gloss data folder, or an HTTPS URL") String source,
      @Param(name = "dry", defaultValue = "true", descriptionKey = "command.help.pack.arg_dry",
          description = "Report what would change without writing anything") boolean dry,
      @Param(name = "sender", contextual = true) CommandSender sender) {
    if (GlossCommandMessages.denied(sender, INSTALL_PERMISSION) || !available(sender)) {
      return;
    }
    GlossPackService packs = packs();
    try {
      GlossPackArchive archive = packs.open(source);
      String id = archive.manifest().id();
      if (dry) {
        report(sender, id, packs.preview(archive), GlossMessages.PACK_PREVIEW);
        return;
      }
      report(sender, id, packs.install(archive, source), GlossMessages.PACK_INSTALLED);
    } catch (InterruptedException interruption) {
      Thread.currentThread().interrupt();
      fail(sender, source, "the download was interrupted");
    } catch (Exception failure) {
      fail(sender, source, reason(failure));
    }
  }

  @Director(name = "update", descriptionKey = "command.help.pack.update",
      description = "Update an installed pack from where it came from")
  public void update(
      @Param(name = "id", descriptionKey = "command.help.pack.arg_id",
          description = "Installed pack id") String id,
      @Param(name = "sender", contextual = true) CommandSender sender) {
    if (GlossCommandMessages.denied(sender, UPDATE_PERMISSION) || !available(sender)) {
      return;
    }
    try {
      report(sender, id, packs().update(id), GlossMessages.PACK_UPDATED);
    } catch (InterruptedException interruption) {
      Thread.currentThread().interrupt();
      fail(sender, id, "the download was interrupted");
    } catch (Exception failure) {
      fail(sender, id, reason(failure));
    }
  }

  @Director(name = "remove", aliases = {"uninstall"}, descriptionKey = "command.help.pack.remove",
      description = "Remove a pack, keeping every file edited since it was installed")
  public void remove(
      @Param(name = "id", descriptionKey = "command.help.pack.arg_id",
          description = "Installed pack id") String id,
      @Param(name = "sender", contextual = true) CommandSender sender) {
    if (GlossCommandMessages.denied(sender, REMOVE_PERMISSION) || !available(sender)) {
      return;
    }
    try {
      report(sender, id, packs().remove(id), GlossMessages.PACK_REMOVED);
    } catch (Exception failure) {
      fail(sender, id, reason(failure));
    }
  }

  @Director(name = "list", descriptionKey = "command.help.pack.list",
      description = "List installed packs")
  public void list(@Param(name = "sender", contextual = true) CommandSender sender) {
    if (GlossCommandMessages.denied(sender, PERMISSION) || !available(sender)) {
      return;
    }
    List<GlossPackLedger> installed = packs().installed();
    if (installed.isEmpty()) {
      GlossCommandMessages.send(sender, GlossMessages.PACK_EMPTY);
      return;
    }
    for (GlossPackLedger ledger : installed) {
      GlossCommandMessages.send(sender, GlossMessages.PACK_ENTRY,
          MessageArgument.untrusted("id", ledger.id()),
          MessageArgument.untrusted("value", ledger.manifest().packVersion()),
          MessageArgument.trusted("count", ledger.installedHashes().size()));
    }
  }

  @Director(name = "info", descriptionKey = "command.help.pack.info",
      description = "Show what one pack owns")
  public void info(
      @Param(name = "id", descriptionKey = "command.help.pack.arg_id",
          description = "Installed pack id") String id,
      @Param(name = "sender", contextual = true) CommandSender sender) {
    if (GlossCommandMessages.denied(sender, PERMISSION) || !available(sender)) {
      return;
    }
    GlossPackLedger ledger = packs().ledger(id);
    if (ledger == null) {
      GlossCommandMessages.send(sender, GlossMessages.PACK_UNKNOWN,
          MessageArgument.untrusted("id", id));
      return;
    }
    GlossCommandMessages.send(sender, GlossMessages.PACK_ENTRY,
        MessageArgument.untrusted("id", ledger.id()),
        MessageArgument.untrusted("value", ledger.manifest().packVersion()),
        MessageArgument.trusted("count", ledger.installedHashes().size()));
    int reported = 0;
    for (String path : ledger.installedHashes().keySet()) {
      if (reported++ >= MAX_REPORTED_OUTCOMES) {
        break;
      }
      GlossCommandMessages.send(sender, GlossMessages.PACK_OUTCOME,
          MessageArgument.untrusted("state", "owns"),
          MessageArgument.untrusted("path", path),
          MessageArgument.untrusted("reason", ""));
    }
  }

  private void report(CommandSender sender, String id, List<GlossPackPreview.Outcome> outcomes,
                      art.arcane.volmlib.util.localization.TextKey summary) {
    GlossCommandMessages.send(sender, summary,
        MessageArgument.untrusted("id", id),
        MessageArgument.trusted("count", outcomes.size()));
    int reported = 0;
    for (GlossPackPreview.Outcome outcome : outcomes) {
      if (reported++ >= MAX_REPORTED_OUTCOMES) {
        break;
      }
      GlossCommandMessages.send(sender, GlossMessages.PACK_OUTCOME,
          MessageArgument.untrusted("state",
              outcome.disposition().name().toLowerCase(Locale.ROOT).replace('_', ' ')),
          MessageArgument.untrusted("path", outcome.path()),
          MessageArgument.untrusted("reason", outcome.reason()));
    }
  }

  private void fail(CommandSender sender, String id, String reason) {
    GlossCommandMessages.send(sender, GlossMessages.PACK_FAILED,
        MessageArgument.untrusted("id", id),
        MessageArgument.untrusted("reason", reason));
  }

  private boolean available(CommandSender sender) {
    if (packs() != null && packs().enabled()) {
      return true;
    }
    GlossCommandMessages.send(sender, GlossMessages.PACK_DISABLED);
    return false;
  }

  private GlossPackService packs() {
    return plugin == null ? null : plugin.service(GlossPackService.class);
  }

  static String reason(Throwable failure) {
    Throwable cause = failure;
    while (cause.getCause() != null && cause.getMessage() == null) {
      cause = cause.getCause();
    }
    String message = cause.getMessage();
    return message == null || message.isBlank()
        ? cause.getClass().getSimpleName()
        : message.substring(0, Math.min(message.length(), 200));
  }
}
