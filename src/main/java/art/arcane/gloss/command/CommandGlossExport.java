package art.arcane.gloss.command;

import art.arcane.gloss.Gloss;
import art.arcane.gloss.editor.sync.EditorSyncDocumentKind;
import art.arcane.gloss.importer.ExportService;
import art.arcane.gloss.locale.GlossMessages;
import art.arcane.volmlib.util.director.annotations.Director;
import art.arcane.volmlib.util.director.annotations.Param;
import art.arcane.volmlib.util.localization.MessageArgument;
import org.bukkit.command.CommandSender;

import java.io.IOException;
import java.nio.file.Path;
import java.util.Optional;

@Director(name = "export", descriptionKey = "command.help.export.root",
    description = "Copy documents out as plain JSON")
public final class CommandGlossExport {
  public static final String PERMISSION = "gloss.export";
  private static final String DEFAULT_DIRECTORY = "exports";

  private final Gloss plugin;

  public CommandGlossExport(Gloss plugin) {
    this.plugin = plugin;
  }

  @Director(name = "documents", aliases = {"json"}, descriptionKey = "command.help.export.root",
      description = "Copy documents out as plain JSON")
  public void documents(
      @Param(name = "kind", defaultValue = "*", descriptionKey = "command.help.export.arg_kind",
          description = "Limit the export to one document folder") String kind,
      @Param(name = "id", defaultValue = "*", descriptionKey = "command.help.export.arg_id",
          description = "Limit the export to one document") String id,
      @Param(name = "dir", defaultValue = DEFAULT_DIRECTORY,
          descriptionKey = "command.help.export.arg_dir",
          description = "Destination folder under the Gloss data folder") String dir,
      @Param(name = "sender", contextual = true) CommandSender sender) {
    if (GlossCommandMessages.denied(sender, PERMISSION)) {
      return;
    }
    try {
      Path destination = destination(dir);
      ExportService.Result result = new ExportService(plugin.getDataFolder().toPath())
          .export(destination, filter(kind), filter(id));
      done(sender, result);
    } catch (IOException | RuntimeException failure) {
      GlossCommandMessages.send(sender, GlossMessages.EXPORT_FAILED,
          MessageArgument.untrusted("reason", CommandGlossPack.reason(failure)));
    }
  }

  @Director(name = "bundle", descriptionKey = "command.help.export.bundle",
      description = "Write the whole workspace as an editor bundle")
  public void bundle(
      @Param(name = "dir", defaultValue = DEFAULT_DIRECTORY,
          descriptionKey = "command.help.export.arg_dir",
          description = "Destination folder under the Gloss data folder") String dir,
      @Param(name = "sender", contextual = true) CommandSender sender) {
    if (GlossCommandMessages.denied(sender, PERMISSION)) {
      return;
    }
    try {
      ExportService.Result result = new ExportService(plugin.getDataFolder().toPath())
          .exportBundle(destination(dir));
      done(sender, result);
    } catch (IOException | RuntimeException failure) {
      GlossCommandMessages.send(sender, GlossMessages.EXPORT_FAILED,
          MessageArgument.untrusted("reason", CommandGlossPack.reason(failure)));
    }
  }

  private void done(CommandSender sender, ExportService.Result result) {
    GlossCommandMessages.send(sender, GlossMessages.EXPORT_DONE,
        MessageArgument.trusted("count", result.documents()),
        MessageArgument.untrusted("path", result.directory().toString()));
  }

  private Path destination(String dir) {
    return destination(plugin.getDataFolder().toPath(), dir);
  }

  /**
   * The export writes a re-serialization of every document with {@code AtomicFiles.replace} - no
   * transaction, no backup journal, no history copy - so it must never be aimed at the live tree.
   * The data folder itself and any document collection under it are refused.
   */
  static Path destination(Path dataFolder, String dir) {
    Path data = dataFolder.toAbsolutePath().normalize();
    Path candidate = Path.of(dir);
    Path target = (candidate.isAbsolute() ? candidate : data.resolve(candidate)).normalize();
    if (!target.startsWith(data)) {
      throw new IllegalArgumentException("exports must stay under the Gloss data folder");
    }
    if (target.equals(data)) {
      throw new IllegalArgumentException("exports must not overwrite the live documents");
    }
    for (EditorSyncDocumentKind kind : EditorSyncDocumentKind.values()) {
      if (target.equals(data.resolve(kind.storageName()).normalize())) {
        throw new IllegalArgumentException("exports must not overwrite the live "
            + kind.storageName() + " documents");
      }
    }
    return target;
  }

  private static Optional<String> filter(String value) {
    return value == null || value.isBlank() || value.equals("*")
        ? Optional.empty()
        : Optional.of(value);
  }
}
