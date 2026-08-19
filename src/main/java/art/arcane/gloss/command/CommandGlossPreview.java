package art.arcane.gloss.command;

import art.arcane.gloss.Gloss;
import art.arcane.gloss.locale.GlossMessages;
import art.arcane.gloss.preview.PreviewElement;
import art.arcane.gloss.preview.doc.CompiledPreviewDocument;
import art.arcane.gloss.preview.doc.PreviewDocumentRegistry;
import art.arcane.gloss.preview.doc.PreviewStateContext;
import art.arcane.volmlib.util.director.annotations.Director;
import art.arcane.volmlib.util.director.annotations.Param;
import art.arcane.volmlib.util.director.help.DirectorMiniMenu;
import art.arcane.volmlib.util.localization.MessageArgs;
import art.arcane.volmlib.util.scheduling.SchedulerUtils;
import org.bukkit.block.Block;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;

import java.util.ArrayList;
import java.util.List;

/**
 * {@code /gloss preview} — inspects and manages the JSON container-preview documents owned by
 * {@link PreviewDocumentRegistry}. Threading and permission patterns copied verbatim from
 * {@link CommandGlossItem}.
 */
@Director(name = "preview", aliases = {"previews"}, description = "Preview document tools", descriptionKey = "command.help.preview")
public class CommandGlossPreview {

  private static final int MAX_REPORTED_ERRORS = 3;
  private static final String LIST_COMMAND = "/gloss preview list";

  private static void sendOnSender(CommandSender sender, String message) {
    runOnSender(sender, () -> sender.sendMessage(message));
  }

  private static void runOnSender(CommandSender sender, Runnable action) {
    if (sender instanceof Player player) {
      SchedulerUtils.runEntity(Gloss.instance, player, action);
      return;
    }

    SchedulerUtils.runGlobal(Gloss.instance, action);
  }

  @Director(name = "list", description = "List preview documents and their match rules", descriptionKey = "command.help.preview.list")
  public void list(
      @Param(name = "page", defaultValue = "1", description = "One-based list page", descriptionKey = "command.help.arg.list_page")
      int page,
      @Param(name = "sender", contextual = true)
      CommandSender sender
  ) {
    String permission = "gloss.previews";
    if (!sender.hasPermission(permission)) {
      sendPermissionDenied(sender, permission);
      return;
    }

    PreviewDocumentRegistry registry = Gloss.instance.getPreviewRegistry();
    List<String> names = new ArrayList<>(registry.names());
    names.sort(String::compareTo);

    DirectorMiniMenu.Theme theme = GlossCommandService.menuTheme();
    List<String> lines = new ArrayList<>();
    lines.add(DirectorMiniMenu.banner(Gloss.instance.getLocalization().text(GlossMessages.PREVIEWS_LIST_HEADER), theme));

    if (names.isEmpty()) {
      String empty = Gloss.instance.getLocalization().text(GlossMessages.PREVIEWS_LIST_EMPTY);
      lines.add("<" + theme.description() + ">" + DirectorMiniMenu.escapeText(empty) + "</" + theme.description() + ">");
      lines.add(DirectorMiniMenu.bar(theme));
      DirectorMiniMenu.deliver(sender, lines);
      return;
    }

    GlossCommandPager.Window window = GlossCommandPager.window(names.size(), page, GlossCommandPager.TEXT_PAGE_SIZE);
    for (String name : names.subList(window.startIndex(), window.endIndex())) {
      CompiledPreviewDocument document = registry.get(name);
      if (document == null) {
        continue;
      }
      lines.add(listEntry(name, document, theme));
    }

    GlossCommandPager.appendFooter(lines, window, LIST_COMMAND, theme);
    lines.add(DirectorMiniMenu.bar(theme));
    DirectorMiniMenu.deliver(sender, lines);
  }

  private String listEntry(String name, CompiledPreviewDocument document, DirectorMiniMenu.Theme theme) {
    CompiledPreviewDocument.MatchSummary summary = document.matchSummary();
    String summaryText = Gloss.instance.getLocalization().text(
        GlossMessages.PREVIEWS_LIST_ENTRY,
        MessageArgs.builder()
            .untrusted("blocks", summary.blocks())
            .untrusted("entities", summary.entities())
            .untrusted("special", summary.special() == null ? "-" : summary.special())
            .untrusted("priority", summary.priority())
            .build()
    );
    return "<" + theme.muted() + ">⇀</" + theme.muted() + "> "
        + "<gradient:" + theme.primaryLeft() + ":" + theme.primaryRight() + ">" + DirectorMiniMenu.escapeText(name) + "</gradient> "
        + "<" + theme.description() + ">" + DirectorMiniMenu.escapeText(summaryText) + "</" + theme.description() + ">";
  }

  @Director(name = "reset", description = "Restore shipped preview defaults (does not remove extra user documents that may shadow them)", descriptionKey = "command.help.preview.reset")
  public void reset(
      @Param(name = "name", description = "Document name to reset, or * for every shipped document", descriptionKey = "command.help.arg.previews_name", defaultValue = "*")
      String name,
      @Param(name = "sender", contextual = true)
      CommandSender sender
  ) {
    String permission = "gloss.previews.reset";
    if (!sender.hasPermission(permission)) {
      sendPermissionDenied(sender, permission);
      return;
    }

    String target = name == null || name.trim().isEmpty() ? "*" : name.trim();

    // resetToDefault performs up to thirteen file writes plus a full reparse; never block the
    // calling thread (which may be the main thread) on that.
    SchedulerUtils.runAsync(Gloss.instance, () -> {
      sendOnSender(sender, Gloss.instance.getLocalization().legacy(
          GlossMessages.PREVIEWS_RESET_STARTED,
          MessageArgs.builder().untrusted("name", target).build()
      ));
      List<String> affected = Gloss.instance.getPreviewRegistry().resetToDefault(target);
      if (affected.isEmpty()) {
        sendOnSender(sender, Gloss.instance.getLocalization().legacy(
            GlossMessages.PREVIEWS_RESET_NONE,
            MessageArgs.builder().untrusted("name", target).build()
        ));
        return;
      }
      sendOnSender(sender, Gloss.instance.getLocalization().legacy(
          GlossMessages.PREVIEWS_RESET_DONE,
          MessageArgs.builder().untrusted("count", affected.size()).build()
      ));
    });
  }

  @Director(name = "dump", description = "Build a preview document once and print its element counts", descriptionKey = "command.help.preview.dump")
  public void dump(
      @Param(name = "name", description = "Document name to build", descriptionKey = "command.help.arg.previews_dump_name")
      String name,
      @Param(name = "sender", contextual = true)
      CommandSender sender
  ) {
    String permission = "gloss.previews.dump";
    if (!sender.hasPermission(permission)) {
      sendPermissionDenied(sender, permission);
      return;
    }

    String docName = name == null ? "" : name.trim();
    if (sender instanceof Player player) {
      // Building touches live block/inventory state, so the player path must run on the region
      // thread that owns the player (a no-op scheduling hop on non-Folia servers).
      runOnSender(player, () -> executeDump(player, docName));
      return;
    }

    // Console never touches world state (statics-only) and RCON reads the response buffer the
    // instant dispatch returns, so a next-tick hop here would hand RCON an empty response.
    executeDump(sender, docName);
  }

  private void executeDump(CommandSender sender, String docName) {
    // Same trailing-".json" tolerance reset() gets for free through resetToDefault -> extract.
    CompiledPreviewDocument document = Gloss.instance.getPreviewRegistry().get(PreviewDocumentRegistry.normalize(docName));
    if (document == null) {
      sender.sendMessage(Gloss.instance.getLocalization().legacy(
          GlossMessages.PREVIEWS_DUMP_UNKNOWN,
          MessageArgs.builder().untrusted("name", docName).build()
      ));
      return;
    }

    PreviewStateContext context = dumpContext(sender, document);
    List<String> errors = new ArrayList<>();
    List<PreviewElement> elements = document.build(context, errors::add);
    reportDump(sender, document, elements, errors);
  }

  /** The looked-at block when the sender is a player looking at one this document matches, else statics. */
  private PreviewStateContext dumpContext(CommandSender sender, CompiledPreviewDocument document) {
    if (sender instanceof Player player) {
      Block block = Gloss.instance.getSessionManager().lookedAtBlock(player);
      if (block != null && document.matchesBlock(block.getType())) {
        return PreviewStateContext.forBlock(block, player, document.varsForBlock(block.getType()));
      }
    }
    return PreviewStateContext.statics(document.varsForBlock(null));
  }

  private void reportDump(CommandSender sender, CompiledPreviewDocument document, List<PreviewElement> elements, List<String> errors) {
    int panels = 0;
    int cells = 0;
    int slots = 0;
    int labels = 0;
    for (PreviewElement element : elements) {
      if (element instanceof PreviewElement.Panel) {
        panels++;
      } else if (element instanceof PreviewElement.Cell) {
        cells++;
      } else if (element instanceof PreviewElement.Slot) {
        slots++;
      } else if (element instanceof PreviewElement.Label) {
        labels++;
      }
    }

    sender.sendMessage(Gloss.instance.getLocalization().legacy(
        GlossMessages.PREVIEWS_DUMP_RESULT,
        MessageArgs.builder()
            .untrusted("name", document.name())
            .untrusted("total", elements.size())
            .untrusted("panels", panels)
            .untrusted("cells", cells)
            .untrusted("slots", slots)
            .untrusted("labels", labels)
            .build()
    ));
    reportDumpErrors(sender, errors);
  }

  /** Up to {@link #MAX_REPORTED_ERRORS} build-error strings, then a "+N more" tail pointing at the console log. */
  private void reportDumpErrors(CommandSender sender, List<String> errors) {
    if (errors.isEmpty()) {
      sender.sendMessage(Gloss.instance.getLocalization().legacy(GlossMessages.PREVIEWS_DUMP_NO_ERRORS));
      return;
    }

    int shown = Math.min(MAX_REPORTED_ERRORS, errors.size());
    for (int index = 0; index < shown; index++) {
      sender.sendMessage(Gloss.instance.getLocalization().legacy(
          GlossMessages.PREVIEWS_DUMP_ERROR_LINE,
          MessageArgs.builder().untrusted("message", errors.get(index)).build()
      ));
    }

    int remaining = errors.size() - shown;
    if (remaining > 0) {
      sender.sendMessage(Gloss.instance.getLocalization().legacy(
          GlossMessages.PREVIEWS_DUMP_ERROR_MORE,
          MessageArgs.builder().untrusted("count", remaining).build()
      ));
    }
  }

  private void sendPermissionDenied(CommandSender sender, String permission) {
    sender.sendMessage(Gloss.instance.getLocalization().legacy(
        GlossMessages.PERMISSION_DENIED,
        MessageArgs.builder().untrusted("permission", permission).build()
    ));
  }
}
