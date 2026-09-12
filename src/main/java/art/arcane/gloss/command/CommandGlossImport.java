package art.arcane.gloss.command;

import art.arcane.gloss.Gloss;
import art.arcane.gloss.config.GlossConfigFile;
import art.arcane.gloss.importer.DocumentImportEntry;
import art.arcane.gloss.importer.DocumentImportPlan;
import art.arcane.gloss.importer.DocumentImportService;
import art.arcane.gloss.importer.HoloUiDataImporter;
import art.arcane.gloss.history.HistoryService;
import art.arcane.gloss.importer.HoloUiImportDisposition;
import art.arcane.gloss.importer.LegacyGlossDataImporter;
import art.arcane.gloss.importer.LegacyHologramImportService;
import art.arcane.gloss.importer.LegacyImportApplyEntry;
import art.arcane.gloss.importer.LegacyImportApplyResult;
import art.arcane.gloss.importer.LegacyImportBusyException;
import art.arcane.gloss.importer.LegacyImportCandidate;
import art.arcane.gloss.importer.LegacyImportIssue;
import art.arcane.gloss.importer.LegacyImportPlan;
import art.arcane.gloss.importer.LegacyImportSource;
import art.arcane.gloss.locale.GlossMessages;
import art.arcane.gloss.locale.GlossLocalization;
import art.arcane.volmlib.util.collection.KList;
import art.arcane.volmlib.util.director.DirectorParameterHandler;
import art.arcane.volmlib.util.director.annotations.Director;
import art.arcane.volmlib.util.director.annotations.Param;
import art.arcane.volmlib.util.director.exceptions.DirectorParsingException;
import art.arcane.volmlib.util.localization.MessageArgs;
import art.arcane.volmlib.util.localization.MessageArgument;
import art.arcane.volmlib.util.localization.TextKey;
import art.arcane.volmlib.util.scheduling.SchedulerUtils;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;

import java.io.IOException;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.CancellationException;
import java.util.concurrent.CompletionException;
import java.util.concurrent.ExecutionException;

@Director(name = "import", description = "Migrate legacy holograms without modifying source files",
    descriptionKey = "command.help.import")
public final class CommandGlossImport {
  public static final String PERMISSION = "gloss.import";
  public static final String APPLY_PERMISSION = "gloss.import.apply";

  private static final int MAX_DETAIL_LINES = 12;

  @Director(name = "preview", aliases = {"dry-run", "dryrun"},
      description = "Preview a non-destructive legacy hologram migration",
      descriptionKey = "command.help.import.preview")
  public void preview(
      @Param(name = "source", description = "Legacy hologram source",
          descriptionKey = "command.help.arg.import_source", customHandler = LegacySourceHandler.class)
      LegacyImportSource source,
      @Param(name = "sender", contextual = true)
      CommandSender sender
  ) {
    if (!checkPermission(sender, PERMISSION)) {
      return;
    }
    if (DocumentImportService.handles(source)) {
      previewDocuments(sender, source);
      return;
    }
    LegacyHologramImportService importer = importer();
    if (importer.isBusy()) {
      send(sender, GlossMessages.IMPORT_BUSY, MessageArgs.empty());
      return;
    }
    send(sender, GlossMessages.IMPORT_PREVIEW_STARTED,
        MessageArgs.builder().untrusted("source", source.id()).build());
    importer.preview(source).whenComplete((plan, failure) -> {
      if (failure != null) {
        reportFailure(sender, source, failure);
        return;
      }
      sendLater(sender, () -> reportPreview(sender, plan));
    });
  }

  @Director(name = "apply", description = "Apply a no-overwrite legacy hologram migration",
      descriptionKey = "command.help.import.apply")
  public void apply(
      @Param(name = "source", description = "Legacy hologram source",
          descriptionKey = "command.help.arg.import_source", customHandler = LegacySourceHandler.class)
      LegacyImportSource source,
      @Param(name = "sender", contextual = true)
      CommandSender sender
  ) {
    if (!checkPermission(sender, APPLY_PERMISSION)) {
      return;
    }
    if (DocumentImportService.handles(source)) {
      applyDocuments(sender, source);
      return;
    }
    LegacyHologramImportService importer = importer();
    if (importer.isBusy()) {
      send(sender, GlossMessages.IMPORT_BUSY, MessageArgs.empty());
      return;
    }
    send(sender, GlossMessages.IMPORT_APPLY_STARTED,
        MessageArgs.builder().untrusted("source", source.id()).build());
    importer.apply(source).whenComplete((result, failure) -> {
      if (failure != null) {
        reportFailure(sender, source, failure);
        return;
      }
      sendLater(sender, () -> reportApply(sender, result));
    });
  }

  @Director(name = "holoui", sync = true,
      description = "Copy HoloUi data from plugins/holoui, overwriting earlier imports",
      descriptionKey = "command.help.import.holoui")
  public void holoui(
      @Param(name = "sender", contextual = true)
      CommandSender sender
  ) {
    if (!checkPermission(sender, PERMISSION)) {
      return;
    }
    Gloss plugin = Gloss.instance;
    HoloUiDataImporter importer = new HoloUiDataImporter(plugin.getDataFolder(), plugin.configLoader());
    if (importer.sourceDirectory() == null) {
      GlossCommandMessages.send(sender, GlossMessages.IMPORT_HOLOUI_MISSING);
      return;
    }
    GlossConfigFile configFile;
    try {
      configFile = plugin.configLoader().loadForReload();
    } catch (IOException failure) {
      GlossCommandMessages.send(sender, GlossMessages.IMPORT_CONFIG_UNREADABLE,
          MessageArgument.untrusted("reason", safeReason(failure)));
      return;
    }
    HoloUiDataImporter.Result result = importer.run(configFile, true);
    plugin.reloadAll();
    long copied = result.count(HoloUiImportDisposition.COPIED);
    long overlaid = result.count(HoloUiImportDisposition.OVERLAID_CONFIG_KEY);
    long errors = result.count(HoloUiImportDisposition.ERROR);
    long skipped = result.entries().size() - copied - overlaid - errors;
    GlossCommandMessages.send(sender, GlossMessages.IMPORT_HOLOUI_DONE,
        MessageArgument.trusted("copied", copied),
        MessageArgument.trusted("skipped", skipped),
        MessageArgument.trusted("overlaid", overlaid),
        MessageArgument.trusted("errors", errors));
  }

  @Director(name = "legacy", sync = true,
      description = "Migrate pre-merger Gloss data files to the v2 envelopes",
      descriptionKey = "command.help.import.legacy")
  public void legacy(
      @Param(name = "sender", contextual = true)
      CommandSender sender
  ) {
    if (!checkPermission(sender, PERMISSION)) {
      return;
    }
    Gloss plugin = Gloss.instance;
    GlossConfigFile configFile;
    try {
      configFile = plugin.configLoader().loadForReload();
    } catch (IOException failure) {
      GlossCommandMessages.send(sender, GlossMessages.IMPORT_CONFIG_UNREADABLE,
          MessageArgument.untrusted("reason", safeReason(failure)));
      return;
    }
    LegacyGlossDataImporter.Result result =
        new LegacyGlossDataImporter(plugin.getDataFolder(), plugin.configLoader()).run(configFile);
    plugin.reloadAll();
    GlossCommandMessages.send(sender, GlossMessages.IMPORT_LEGACY_DONE,
        MessageArgument.trusted("migrated", result.count(LegacyGlossDataImporter.Status.MIGRATED)),
        MessageArgument.trusted("absorbed", result.count(LegacyGlossDataImporter.Status.ABSORBED)),
        MessageArgument.trusted("overlaid", result.count(LegacyGlossDataImporter.Status.OVERLAID)),
        MessageArgument.trusted("errors", result.count(LegacyGlossDataImporter.Status.ERROR)));
  }

  private void reportPreview(CommandSender sender, LegacyImportPlan plan) {
    if (!plan.sourcePresent()) {
      send(sender, GlossMessages.IMPORT_SOURCE_MISSING,
          MessageArgs.builder()
              .untrusted("source", plan.source().id())
              .untrusted("path", plan.sourcePath())
              .build());
      return;
    }
    send(sender, GlossMessages.IMPORT_PREVIEW_SUMMARY,
        MessageArgs.builder()
            .untrusted("source", plan.source().id())
            .untrusted("ready", plan.readyCount())
            .untrusted("resume", plan.resumeCount())
            .untrusted("conflicts", plan.conflictCount())
            .untrusted("errors", plan.errorCount())
            .untrusted("warnings", plan.warningCount())
            .untrusted("path", plan.sourcePath())
            .build());
    int lines = 0;
    for (LegacyImportCandidate candidate : plan.candidates()) {
      if (lines++ >= MAX_DETAIL_LINES) {
        break;
      }
      send(sender, GlossMessages.IMPORT_PREVIEW_ENTRY,
          MessageArgs.builder()
              .untrusted("legacy", candidate.legacyId())
              .untrusted("board", candidate.boardId())
              .untrusted("state", candidate.disposition().name().toLowerCase())
              .untrusted("warnings", candidate.warnings().size())
              .build());
    }
    reportIssues(sender, plan);
  }

  private void reportApply(CommandSender sender, LegacyImportApplyResult result) {
    LegacyImportPlan plan = result.plan();
    if (!plan.sourcePresent()) {
      send(sender, GlossMessages.IMPORT_SOURCE_MISSING,
          MessageArgs.builder()
              .untrusted("source", plan.source().id())
              .untrusted("path", plan.sourcePath())
              .build());
      return;
    }
    send(sender, GlossMessages.IMPORT_APPLY_SUMMARY,
        MessageArgs.builder()
            .untrusted("source", plan.source().id())
            .untrusted("imported", result.importedCount())
            .untrusted("skipped", result.skippedCount())
            .untrusted("failed", result.failedCount())
            .untrusted("errors", plan.errorCount())
            .untrusted("warnings", plan.warningCount())
            .build());
    int lines = 0;
    for (LegacyImportApplyEntry entry : result.entries()) {
      if (entry.status() == LegacyImportApplyEntry.Status.IMPORTED
          || entry.status() == LegacyImportApplyEntry.Status.RESUMED) {
        continue;
      }
      if (lines++ >= MAX_DETAIL_LINES) {
        break;
      }
      send(sender, GlossMessages.IMPORT_APPLY_ENTRY,
          MessageArgs.builder()
              .untrusted("legacy", entry.legacyId())
              .untrusted("state", entry.status().name().toLowerCase())
              .untrusted("reason", entry.message().isBlank() ? "-" : entry.message())
              .build());
    }
    reportIssues(sender, plan);
  }

  private void reportIssues(CommandSender sender, LegacyImportPlan plan) {
    int lines = 0;
    for (LegacyImportIssue issue : plan.issues()) {
      if (lines >= MAX_DETAIL_LINES) {
        return;
      }
      sendIssue(sender, issue.severity().name().toLowerCase(), issue.legacyId(), issue.message());
      lines++;
    }
    for (LegacyImportCandidate candidate : plan.candidates()) {
      for (String warning : candidate.warnings()) {
        if (lines >= MAX_DETAIL_LINES) {
          return;
        }
        sendIssue(sender, "warning", candidate.legacyId(), warning);
        lines++;
      }
    }
  }

  private void sendIssue(CommandSender sender, String severity, String legacyId, String reason) {
    send(sender, GlossMessages.IMPORT_ISSUE,
        MessageArgs.builder()
            .untrusted("severity", severity)
            .untrusted("legacy", legacyId)
            .untrusted("reason", reason)
            .build());
  }

  private void reportFailure(CommandSender sender, LegacyImportSource source, Throwable failure) {
    Throwable cause = rootCause(failure);
    if (cause instanceof LegacyImportBusyException) {
      sendLater(sender, () -> send(sender, GlossMessages.IMPORT_BUSY, MessageArgs.empty()));
      return;
    }
    if (!(cause instanceof CancellationException)) {
      Gloss.logExceptionStack(true, cause, "Legacy hologram import failed for source %s.", source.id());
    }
    sendLater(sender, () -> send(sender, GlossMessages.IMPORT_FAILED,
        MessageArgs.builder()
            .untrusted("source", source.id())
            .untrusted("reason", safeReason(cause))
            .build()));
  }

  private static LegacyHologramImportService importer() {
    return Gloss.instance.getMenuCatalog().legacyImporter();
  }

  /**
   * The sources that convert whole documents rather than holograms. Previewing never writes, and
   * applying leaves an existing document alone: a document already at the target id is reported as
   * a conflict and skipped.
   */
  private void previewDocuments(CommandSender sender, LegacyImportSource source) {
    DocumentImportPlan plan;
    try {
      plan = documentImporter().preview(source);
    } catch (IOException | RuntimeException failure) {
      reportDocumentFailure(sender, source, failure);
      return;
    }
    if (!plan.sourcePresent()) {
      GlossCommandMessages.send(sender, GlossMessages.IMPORT_DOCUMENT_ABSENT,
          MessageArgument.untrusted("source", source.id()),
          MessageArgument.untrusted("path", plan.sourcePath()));
      return;
    }
    GlossCommandMessages.send(sender, GlossMessages.IMPORT_DOCUMENT_PREVIEW,
        MessageArgument.untrusted("source", source.id()),
        MessageArgument.trusted("count", plan.entries().size()));
    reportDocumentEntries(sender, plan);
  }

  private void applyDocuments(CommandSender sender, LegacyImportSource source) {
    DocumentImportService importer = documentImporter();
    List<DocumentImportEntry> applied;
    DocumentImportPlan plan;
    try {
      plan = importer.preview(source);
      applied = importer.apply(plan, false);
    } catch (IOException | RuntimeException failure) {
      reportDocumentFailure(sender, source, failure);
      return;
    }
    if (!plan.sourcePresent()) {
      GlossCommandMessages.send(sender, GlossMessages.IMPORT_DOCUMENT_ABSENT,
          MessageArgument.untrusted("source", source.id()),
          MessageArgument.untrusted("path", plan.sourcePath()));
      return;
    }
    GlossCommandMessages.send(sender, GlossMessages.IMPORT_DOCUMENT_APPLIED,
        MessageArgument.untrusted("source", source.id()),
        MessageArgument.trusted("count", applied.size()));
    reportDocumentEntries(sender, plan);
    Gloss plugin = Gloss.instance;
    if (plugin != null && !applied.isEmpty()) {
      plugin.publishEditorSyncRuntime(DocumentImportService.kindsOf(applied), false);
    }
  }

  private void reportDocumentEntries(CommandSender sender, DocumentImportPlan plan) {
    int reported = 0;
    for (DocumentImportEntry entry : plan.entries()) {
      if (reported++ >= MAX_DETAIL_LINES) {
        return;
      }
      GlossCommandMessages.send(sender, GlossMessages.IMPORT_DOCUMENT_ENTRY,
          MessageArgument.untrusted("state",
              entry.disposition().name().toLowerCase(Locale.ROOT)),
          MessageArgument.untrusted("path", entry.path()),
          MessageArgument.untrusted("reason", entry.warnings().isEmpty()
              ? entry.dispositionReason()
              : String.join("; ", entry.warnings())));
    }
  }

  private void reportDocumentFailure(CommandSender sender, LegacyImportSource source,
                                     Throwable failure) {
    GlossCommandMessages.send(sender, GlossMessages.IMPORT_FAILED,
        MessageArgument.untrusted("source", source.id()),
        MessageArgument.untrusted("reason", safeReason(failure)));
  }

  private DocumentImportService documentImporter() {
    Gloss plugin = Gloss.instance;
    return new DocumentImportService(plugin.getDataFolder().toPath().getParent().getParent(),
        plugin.getDataFolder().toPath(), plugin.getProjectTransaction(),
        plugin.getPersistenceCoordinator(), this::recordHistory);
  }

  private void recordHistory(String kind, String id, byte[] content, String source) {
    Gloss plugin = Gloss.instance;
    HistoryService history = plugin == null ? null : plugin.service(HistoryService.class);
    if (history != null) {
      history.record(kind, id, content, source);
    }
  }

  private static boolean checkPermission(CommandSender sender, String permission) {
    if (sender.hasPermission(permission)) {
      return true;
    }
    send(sender, GlossMessages.PERMISSION_DENIED,
        MessageArgs.builder().untrusted("permission", permission).build());
    return false;
  }

  private static void sendLater(CommandSender sender, Runnable feedback) {
    boolean accepted = sender instanceof Player player
        ? SchedulerUtils.runEntity(Gloss.instance, player, feedback)
        : SchedulerUtils.runGlobal(Gloss.instance, feedback);
    if (!accepted) {
      Gloss.warnThrottled("legacy-import-feedback-scheduling",
          "Unable to schedule legacy import feedback for %s.", sender.getName());
    }
  }

  private static void send(CommandSender sender, TextKey key, MessageArgs arguments) {
    Gloss.instance.getLocalization().send(sender, key, arguments);
  }

  private static Throwable rootCause(Throwable failure) {
    Throwable current = failure;
    while ((current instanceof CompletionException || current instanceof ExecutionException)
        && current.getCause() != null) {
      current = current.getCause();
    }
    return current;
  }

  private static String safeReason(Throwable failure) {
    String message = failure == null ? null : failure.getMessage();
    return message == null || message.isBlank()
        ? failure == null ? "unknown failure" : failure.getClass().getSimpleName()
        : message;
  }

  public static final class LegacySourceHandler implements DirectorParameterHandler<LegacyImportSource> {
    @Override
    public KList<LegacyImportSource> getPossibilities() {
      KList<LegacyImportSource> sources = new KList<>();
      sources.addAll(Arrays.asList(LegacyImportSource.values()));
      return sources;
    }

    @Override
    public String toString(LegacyImportSource value) {
      return value == null ? "" : value.id();
    }

    @Override
    public LegacyImportSource parse(String in, boolean force) throws DirectorParsingException {
      if (in == null || in.isBlank()) {
        throw new DirectorParsingException(
            GlossLocalization.globalText(GlossMessages.ERROR_IMPORT_SOURCE_REQUIRED));
      }
      try {
        return LegacyImportSource.parse(in);
      } catch (IllegalArgumentException failure) {
        throw new DirectorParsingException(GlossLocalization.globalText(
            GlossMessages.ERROR_IMPORT_SOURCE_UNKNOWN,
            MessageArgs.builder().untrusted("source", in).build()));
      }
    }

    @Override
    public boolean supports(Class<?> type) {
      return type == LegacyImportSource.class;
    }
  }
}
