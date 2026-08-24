package art.arcane.gloss.command;

import art.arcane.gloss.Gloss;
import art.arcane.gloss.editor.sync.EditorSyncKind;
import art.arcane.gloss.editor.sync.EditorSyncOpenResult;
import art.arcane.gloss.editor.sync.EditorSyncService;
import art.arcane.gloss.locale.GlossMessages;
import art.arcane.volmlib.util.director.annotations.Director;
import art.arcane.volmlib.util.director.annotations.Param;
import art.arcane.volmlib.util.director.help.DirectorMiniMenu;
import art.arcane.volmlib.util.localization.MessageArgs;
import art.arcane.volmlib.util.scheduling.SchedulerUtils;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;

@Director(name = "web", description = "Open and manage the Gloss web editor",
    descriptionKey = "command.help.web")
public final class CommandGlossWeb {
  public static final String PERMISSION = "gloss.web";
  public static final String OPEN_PERMISSION = "gloss.web.open";
  public static final String EDIT_PERMISSION = "gloss.web.edit";
  public static final String WORKSPACE_PERMISSION = "gloss.web.workspace";

  private final Gloss plugin;
  private CommandGlossWebEdit edit;
  private CommandGlossWebSessions sessions;

  public CommandGlossWeb(Gloss plugin) {
    this.plugin = plugin;
    this.edit = new CommandGlossWebEdit(this);
    this.sessions = new CommandGlossWebSessions();
  }

  @Director(name = "open", description = "Open an empty hosted Gloss editor",
      descriptionKey = "command.help.web.open")
  public void open(@Param(name = "sender", contextual = true) CommandSender sender) {
    if (!checkPermission(sender, OPEN_PERMISSION)) {
      return;
    }
    deliverBuilderLink(sender, plugin.cfg().editorSync().builderUrl());
  }

  @Director(name = "workspace", description = "Open the complete live Gloss workspace",
      descriptionKey = "command.help.web.workspace")
  public void workspace(@Param(name = "sender", contextual = true) CommandSender sender) {
    if (!checkPermission(sender, WORKSPACE_PERMISSION)) {
      return;
    }
    EditorSyncService service = availableService(sender);
    if (service == null) {
      return;
    }
    plugin.getLocalization().send(sender, GlossMessages.WEB_PREPARING,
        MessageArgs.builder().untrusted("subject", "workspace").build());
    CompletableFuture<EditorSyncOpenResult> opening;
    try {
      opening = service.openWorkspace();
    } catch (RuntimeException failure) {
      handleOpenInvocationFailure(sender, service, "workspace", "workspace", failure);
      return;
    }
    opening.whenComplete((result, failure) ->
        runForSender(sender, () -> deliverOpenResult(sender, result, failure, "workspace", "workspace")));
  }

  void openSubject(String wireKind, String subjectId, CommandSender sender) {
    if (!checkPermission(sender, EDIT_PERMISSION)) {
      return;
    }
    EditorSyncService service = availableService(sender);
    if (service == null) {
      return;
    }
    EditorSyncKind kind;
    try {
      kind = EditorSyncKind.parse(wireKind);
    } catch (IllegalArgumentException failure) {
      sendOpenFailure(sender, wireKind, subjectId, failure);
      return;
    }
    plugin.getLocalization().send(sender, GlossMessages.WEB_PREPARING,
        MessageArgs.builder().untrusted("subject", subjectId).build());
    CompletableFuture<EditorSyncOpenResult> opening;
    try {
      opening = service.open(kind, subjectId);
    } catch (RuntimeException failure) {
      handleOpenInvocationFailure(sender, service, wireKind, subjectId, failure);
      return;
    }
    opening.whenComplete((result, failure) ->
        runForSender(sender, () -> deliverOpenResult(sender, result, failure, wireKind, subjectId)));
  }

  private EditorSyncService availableService(CommandSender sender) {
    EditorSyncService service = plugin.getEditorSyncService();
    if (!plugin.cfg().editorSync().enabled() || service == null || !service.isAvailable()) {
      plugin.getLocalization().send(sender, GlossMessages.WEB_UNAVAILABLE);
      return null;
    }
    return service;
  }

  private boolean checkPermission(CommandSender sender, String permission) {
    if (sender.hasPermission(permission)) {
      return true;
    }
    plugin.getLocalization().send(sender, GlossMessages.PERMISSION_DENIED,
        MessageArgs.builder().untrusted("permission", permission).build());
    return false;
  }

  private void deliverOpenResult(CommandSender sender, EditorSyncOpenResult result,
                                 Throwable failure, String kind, String subjectId) {
    if (failure == null) {
      deliverCapabilityLink(plugin, sender, result);
      return;
    }
    Throwable cause = rootCause(failure);
    Gloss.logExceptionStack(false, cause,
        "Unable to create a web editor session for %s \"%s\".", kind, subjectId);
    sendOpenFailure(sender, kind, subjectId, cause);
  }

  private void sendOpenFailure(CommandSender sender, String kind, String subjectId, Throwable failure) {
    plugin.getLocalization().send(sender, GlossMessages.WEB_OPEN_FAILED,
        MessageArgs.builder()
            .untrusted("kind", kind)
            .untrusted("subject", subjectId)
            .untrusted("reason", safeMessage(failure))
            .build());
  }

  private void handleOpenInvocationFailure(CommandSender sender, EditorSyncService service,
                                           String kind, String subjectId, RuntimeException failure) {
    if (!service.isAvailable()) {
      plugin.getLocalization().send(sender, GlossMessages.WEB_UNAVAILABLE);
      return;
    }
    deliverOpenResult(sender, null, failure, kind, subjectId);
  }

  private void deliverBuilderLink(CommandSender sender, String url) {
    if (!(sender instanceof Player)) {
      plugin.getLocalization().send(sender, GlossMessages.WEB_OPEN,
          MessageArgs.builder().untrusted("url", url).build());
      return;
    }
    DirectorMiniMenu.Theme theme = GlossCommandService.menuTheme();
    String hover = plugin.getLocalization().text(GlossMessages.WEB_OPEN_HOVER);
    List<String> lines = new ArrayList<>();
    lines.add(DirectorMiniMenu.banner(plugin.getLocalization().text(GlossMessages.WEB_HEADER), theme));
    lines.add(linkLine(url, url, hover, theme));
    lines.add(DirectorMiniMenu.bar(theme));
    DirectorMiniMenu.deliver(sender, lines);
  }

  static void deliverCapabilityLink(Gloss plugin, CommandSender sender, EditorSyncOpenResult result) {
    MessageArgs linkArguments = MessageArgs.builder()
        .untrusted("session", EditorSyncService.abbreviate(result.sessionId()))
        .build();
    if (!(sender instanceof Player)) {
      plugin.getLocalization().send(sender, GlossMessages.WEB_CAPABILITY_WARNING);
      plugin.getLocalization().send(sender, GlossMessages.WEB_OPEN_CONSOLE,
          MessageArgs.builder()
              .untrusted("subject", result.subjectId())
              .untrusted("url", result.editorUrl())
              .build());
      return;
    }
    DirectorMiniMenu.Theme theme = GlossCommandService.menuTheme();
    String open = plugin.getLocalization().text(GlossMessages.WEB_OPEN_LABEL);
    String copy = plugin.getLocalization().text(GlossMessages.WEB_COPY_LABEL);
    boolean canRevoke = sender.hasPermission(CommandGlossWebSessions.PERMISSION);
    String hover = canRevoke
        ? plugin.getLocalization().text(GlossMessages.WEB_LINK_HOVER, linkArguments)
        : plugin.getLocalization().text(GlossMessages.WEB_OPEN_HOVER);
    String escapedHover = escapeHover(hover);
    String url = result.editorUrl();
    String revokeControl = canRevoke
        ? " <click:run_command:'/gloss web sessions revoke session=" + result.sessionId() + "'><red>["
            + DirectorMiniMenu.escapeText(plugin.getLocalization().text(GlossMessages.WEB_REVOKE_LABEL))
            + "]</red></click>"
        : "";
    String line = "<hover:show_text:'" + escapedHover + "'>"
        + "<click:open_url:'" + url + "'><gradient:" + theme.primaryLeft() + ":"
        + theme.primaryRight() + ">[" + DirectorMiniMenu.escapeText(open) + "]</gradient></click> "
        + "<click:copy_to_clipboard:'" + url + "'><" + theme.muted() + ">["
        + DirectorMiniMenu.escapeText(copy) + "]</" + theme.muted() + "></click>"
        + revokeControl + "</hover>";
    List<String> lines = new ArrayList<>();
    lines.add(DirectorMiniMenu.banner(plugin.getLocalization().text(GlossMessages.WEB_HEADER), theme));
    lines.add(line);
    lines.add(DirectorMiniMenu.bar(theme));
    DirectorMiniMenu.deliver(sender, lines);
  }

  private static String linkLine(String url, String label, String hover, DirectorMiniMenu.Theme theme) {
    return "<hover:show_text:'" + escapeHover(hover) + "'><click:open_url:'" + url + "'>"
        + "<" + theme.muted() + ">⇀</" + theme.muted() + "> "
        + "<gradient:" + theme.primaryLeft() + ":" + theme.primaryRight() + ">"
        + DirectorMiniMenu.escapeText(label) + "</gradient></click></hover>";
  }

  private void runForSender(CommandSender sender, Runnable task) {
    boolean accepted = sender instanceof Player player
        ? SchedulerUtils.runEntity(plugin, player, task)
        : SchedulerUtils.runGlobal(plugin, task);
    if (!accepted) {
      Gloss.warnThrottled("web-editor-feedback-scheduling",
          "Unable to schedule web editor feedback for %s.", sender.getName());
    }
  }

  private static String escapeHover(String hover) {
    return DirectorMiniMenu.escapeText(hover).replace("\\", "\\\\").replace("'", "\\'");
  }

  private static Throwable rootCause(Throwable failure) {
    Throwable current = failure;
    while (current instanceof CompletionException && current.getCause() != null) {
      current = current.getCause();
    }
    return current;
  }

  private static String safeMessage(Throwable failure) {
    String message = failure.getMessage();
    return message == null || message.isBlank() ? failure.getClass().getSimpleName() : message;
  }
}
