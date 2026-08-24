package art.arcane.gloss.command;

import art.arcane.gloss.Gloss;
import art.arcane.gloss.editor.sync.EditorSyncService;
import art.arcane.gloss.editor.sync.EditorSyncSessionInfo;
import art.arcane.gloss.locale.GlossLocalization;
import art.arcane.gloss.locale.GlossMessages;
import art.arcane.volmlib.util.collection.KList;
import art.arcane.volmlib.util.director.DirectorParameterHandler;
import art.arcane.volmlib.util.director.annotations.Director;
import art.arcane.volmlib.util.director.annotations.Param;
import art.arcane.volmlib.util.director.exceptions.DirectorParsingException;
import art.arcane.volmlib.util.director.help.DirectorMiniMenu;
import art.arcane.volmlib.util.localization.MessageArgs;
import art.arcane.volmlib.util.scheduling.SchedulerUtils;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.NoSuchElementException;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;

@Director(name = "sessions", description = "Manage active web editor sessions",
    descriptionKey = "command.help.web.sessions")
public final class CommandGlossWebSessions {
  public static final String PERMISSION = "gloss.web.sessions";
  private static final String LIST_COMMAND = "/gloss web sessions list";

  @Director(name = "list", description = "List active web editor sessions",
      descriptionKey = "command.help.web.sessions.list")
  public void list(
      @Param(name = "page", defaultValue = "1", description = "One-based list page",
          descriptionKey = "command.help.arg.list_page") int page,
      @Param(name = "sender", contextual = true) CommandSender sender) {
    if (!checkPermission(sender)) {
      return;
    }
    EditorSyncService service = availableService(sender);
    if (service == null) {
      return;
    }
    List<EditorSyncSessionInfo> sessions = service.sessions();
    if (sessions.isEmpty()) {
      send(sender, GlossMessages.WEB_SESSION_LIST_EMPTY);
      return;
    }
    DirectorMiniMenu.ContentPage window = GlossCommandPager.window(sessions.size(), page, GlossCommandPager.TEXT_PAGE_SIZE);
    DirectorMiniMenu.Theme theme = GlossCommandService.menuTheme();
    List<String> lines = new ArrayList<>();
    GlossCommandPager.appendHeader(lines, LIST_COMMAND + " · " + sessions.size(), window, theme);
    for (EditorSyncSessionInfo session : sessions.subList(window.startIndex(), window.endIndex())) {
      long seconds = Math.max(0L, Duration.between(Instant.now(), session.expiresAt()).toSeconds());
      String details = session.kind().wireName() + "=" + session.subjectId()
          + " expires=" + seconds + "s publication=" + session.lastPublicationRevision()
          + " pending=" + (session.pendingStatus() == null ? "-" : session.pendingStatus());
      lines.add(GlossCommandPager.entry(EditorSyncService.abbreviate(session.sessionId()), details, theme));
    }
    GlossCommandPager.appendFooter(lines, window, LIST_COMMAND, theme);
    DirectorMiniMenu.deliver(sender, lines);
  }

  @Director(name = "status", description = "Show one web editor session",
      descriptionKey = "command.help.web.sessions.status")
  public void status(
      @Param(name = "session", description = "Web editor session id",
          descriptionKey = "command.help.arg.web_session", customHandler = SessionIdHandler.class)
      String sessionId,
      @Param(name = "sender", contextual = true) CommandSender sender) {
    if (!checkPermission(sender)) {
      return;
    }
    EditorSyncService service = availableService(sender);
    if (service == null) {
      return;
    }
    String resolved = resolveForSender(sender, sessionId, service);
    if (resolved == null) {
      return;
    }
    EditorSyncSessionInfo session = service.session(resolved).orElse(null);
    if (session == null) {
      unknown(sender, sessionId);
      return;
    }
    send(sender, GlossMessages.WEB_SESSION_STATUS, infoArguments(session));
  }

  @Director(name = "revoke", description = "Revoke a web editor capability",
      descriptionKey = "command.help.web.sessions.revoke")
  public void revoke(
      @Param(name = "session", description = "Web editor session id",
          descriptionKey = "command.help.arg.web_session", customHandler = SessionIdHandler.class)
      String sessionId,
      @Param(name = "sender", contextual = true) CommandSender sender) {
    if (!checkPermission(sender)) {
      return;
    }
    EditorSyncService service = availableService(sender);
    if (service == null) {
      return;
    }
    String resolved = resolveForSender(sender, sessionId, service);
    if (resolved == null) {
      return;
    }
    CompletableFuture<Void> revocation;
    try {
      revocation = service.revoke(resolved);
    } catch (RuntimeException failure) {
      reportInvocationFailure(sender, resolved, service, failure);
      return;
    }
    revocation.whenComplete((ignored, failure) -> runForSender(sender, () -> {
      if (failure == null) {
        send(sender, GlossMessages.WEB_SESSION_REVOKED,
            MessageArgs.builder().untrusted("session", EditorSyncService.abbreviate(resolved)).build());
      } else {
        reportFailure(sender, resolved, failure);
      }
    }));
  }

  @Director(name = "pull", description = "Poll a web editor session now",
      descriptionKey = "command.help.web.sessions.pull")
  public void pull(
      @Param(name = "session", description = "Web editor session id",
          descriptionKey = "command.help.arg.web_session", customHandler = SessionIdHandler.class)
      String sessionId,
      @Param(name = "sender", contextual = true) CommandSender sender) {
    if (!checkPermission(sender)) {
      return;
    }
    EditorSyncService service = availableService(sender);
    if (service == null) {
      return;
    }
    String resolved = resolveForSender(sender, sessionId, service);
    if (resolved == null) {
      return;
    }
    CompletableFuture<Void> pull;
    try {
      pull = service.pullNow(resolved);
    } catch (RuntimeException failure) {
      reportInvocationFailure(sender, resolved, service, failure);
      return;
    }
    pull.whenComplete((ignored, failure) -> runForSender(sender, () -> {
      if (failure == null) {
        send(sender, GlossMessages.WEB_SESSION_PULLED,
            MessageArgs.builder().untrusted("session", EditorSyncService.abbreviate(resolved)).build());
      } else {
        reportFailure(sender, resolved, failure);
      }
    }));
  }

  private boolean checkPermission(CommandSender sender) {
    if (sender.hasPermission(PERMISSION)) {
      return true;
    }
    send(sender, GlossMessages.PERMISSION_DENIED,
        MessageArgs.builder().untrusted("permission", PERMISSION).build());
    return false;
  }

  private MessageArgs infoArguments(EditorSyncSessionInfo session) {
    long seconds = Math.max(0L, Duration.between(Instant.now(), session.expiresAt()).toSeconds());
    return MessageArgs.builder()
        .untrusted("session", EditorSyncService.abbreviate(session.sessionId()))
        .untrusted("kind", session.kind().wireName())
        .untrusted("subject", session.subjectId())
        .untrusted("seconds", seconds)
        .untrusted("revision", session.lastPublicationRevision())
        .untrusted("pending", session.pendingStatus() == null ? "-" : session.pendingStatus())
        .build();
  }

  private void reportFailure(CommandSender sender, String sessionId, Throwable failure) {
    Throwable cause = rootCause(failure);
    if (cause instanceof NoSuchElementException) {
      unknown(sender, sessionId);
      return;
    }
    Gloss.logExceptionStack(false, cause, "Editor sync command failed for session %s.",
        EditorSyncService.abbreviate(sessionId));
    send(sender, GlossMessages.WEB_SESSION_FAILED,
        MessageArgs.builder()
            .untrusted("session", EditorSyncService.abbreviate(sessionId))
            .untrusted("reason", safeMessage(cause))
            .build());
  }

  private void reportInvocationFailure(CommandSender sender, String sessionId,
                                       EditorSyncService service, RuntimeException failure) {
    if (!service.isAvailable()) {
      send(sender, GlossMessages.WEB_UNAVAILABLE);
      return;
    }
    reportFailure(sender, sessionId, failure);
  }

  private void unknown(CommandSender sender, String sessionId) {
    send(sender, GlossMessages.WEB_SESSION_UNKNOWN,
        MessageArgs.builder().untrusted("session", EditorSyncService.abbreviate(sessionId)).build());
  }

  private String resolveForSender(CommandSender sender, String supplied,
                                  EditorSyncService service) {
    try {
      Optional<String> resolved = resolveSessionId(supplied, service.sessions());
      if (resolved.isPresent()) {
        return resolved.get();
      }
      unknown(sender, supplied);
      return null;
    } catch (IllegalArgumentException failure) {
      send(sender, GlossMessages.WEB_SESSION_FAILED,
          MessageArgs.builder()
              .untrusted("session", EditorSyncService.abbreviate(supplied))
              .untrusted("reason", safeMessage(failure))
              .build());
      return null;
    }
  }

  static Optional<String> resolveSessionId(String supplied,
                                           List<EditorSyncSessionInfo> sessions) {
    String input = supplied == null ? "" : supplied.strip();
    for (EditorSyncSessionInfo session : sessions) {
      if (session.sessionId().equals(input)) {
        return Optional.of(input);
      }
    }
    if (input.length() < 12) {
      return Optional.empty();
    }
    String match = null;
    for (EditorSyncSessionInfo session : sessions) {
      if (!session.sessionId().startsWith(input)) {
        continue;
      }
      if (match != null) {
        throw new IllegalArgumentException("session prefix is ambiguous");
      }
      match = session.sessionId();
    }
    return Optional.ofNullable(match);
  }

  private void runForSender(CommandSender sender, Runnable task) {
    boolean accepted = sender instanceof Player player
        ? SchedulerUtils.runEntity(plugin(), player, task)
        : SchedulerUtils.runGlobal(plugin(), task);
    if (!accepted) {
      Gloss.warnThrottled("editor-sync-feedback-scheduling",
          "Unable to schedule editor sync feedback for %s.", sender.getName());
    }
  }

  private void send(CommandSender sender, art.arcane.volmlib.util.localization.TextKey key) {
    send(sender, key, MessageArgs.empty());
  }

  private void send(CommandSender sender, art.arcane.volmlib.util.localization.TextKey key,
                    MessageArgs arguments) {
    plugin().getLocalization().send(sender, key, arguments);
  }

  private EditorSyncService availableService(CommandSender sender) {
    EditorSyncService service = plugin().getEditorSyncService();
    if (service != null && service.isAvailable()) {
      return service;
    }
    send(sender, GlossMessages.WEB_UNAVAILABLE);
    return null;
  }

  private Gloss plugin() {
    Gloss plugin = Gloss.instance;
    if (plugin == null) {
      throw new IllegalStateException("Gloss is not enabled");
    }
    return plugin;
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

  public static final class SessionIdHandler implements DirectorParameterHandler<String> {
    @Override
    public KList<String> getPossibilities() {
      KList<String> ids = new KList<>();
      Gloss plugin = Gloss.instance;
      if (plugin == null) {
        return ids;
      }
      EditorSyncService service = plugin.getEditorSyncService();
      if (service == null || !service.isAvailable()) {
        return ids;
      }
      service.sessions().stream()
          .map(EditorSyncSessionInfo::sessionId)
          .forEach(ids::add);
      return ids;
    }

    @Override
    public String toString(String value) {
      return value == null ? "" : value;
    }

    @Override
    public String parse(String in, boolean force) throws DirectorParsingException {
      if (in == null || in.isBlank()) {
        throw new DirectorParsingException(
            GlossLocalization.globalText(GlossMessages.ERROR_WEB_SESSION_REQUIRED));
      }
      return in.strip();
    }

    @Override
    public boolean supports(Class<?> type) {
      return type == String.class;
    }
  }
}
