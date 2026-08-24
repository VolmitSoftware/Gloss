package art.arcane.gloss.command;

import art.arcane.gloss.Gloss;
import art.arcane.gloss.config.menu.MenuIds;
import art.arcane.gloss.doc.DocumentRevisionConflictException;
import art.arcane.gloss.locale.GlossLocalization;
import art.arcane.gloss.locale.GlossMessages;
import art.arcane.volmlib.util.collection.KList;
import art.arcane.volmlib.util.director.DirectorParameterHandler;
import art.arcane.volmlib.util.director.exceptions.DirectorParsingException;
import art.arcane.volmlib.util.localization.MessageArgs;
import art.arcane.volmlib.util.localization.TextKey;
import art.arcane.volmlib.util.scheduling.SchedulerUtils;
import com.google.gson.JsonObject;
import com.google.gson.JsonParseException;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;

import java.io.IOException;
import java.nio.file.FileAlreadyExistsException;
import java.nio.file.NoSuchFileException;
import java.util.List;
import java.util.Locale;
import java.util.NoSuchElementException;
import java.util.concurrent.CancellationException;
import java.util.function.UnaryOperator;

/**
 * Shared persistence plumbing for the menu-content commands: {@code /gloss menu addrow|...|image}
 * mutates a loaded menu document and {@code /gloss panel addrow|...|image} routes through the
 * same mutations against a panel's root menu.
 */
final class MenuRowCommandSupport {

  private MenuRowCommandSupport() {
  }

  static void mutate(CommandSender sender, String menuId, String operation,
                     UnaryOperator<JsonObject> mutation, String permission) {
    if (!checkPermission(sender, permission)) {
      return;
    }
    Gloss.instance.getMenuCatalog().mutate(menuId, mutation)
        .whenComplete((document, failure) -> {
          if (failure != null) {
            reportFailure(sender, menuId, failure);
            return;
          }
          sendLater(sender, GlossMessages.MENU_CONTENT_UPDATED,
              MessageArgs.builder()
                  .untrusted("operation", operation)
                  .untrusted("menu", document.id())
                  .untrusted("revision", shortRevision(document.revision()))
                  .build());
        });
  }

  static JsonObject setIconMutation(JsonObject document, int row, String type, String value) {
    validateIconImages(type, value);
    return art.arcane.gloss.config.menu.MenuRowMutations.setIcon(document, row, type, value);
  }

  static JsonObject replaceWithImageMutation(JsonObject document, String path) {
    requireImageFile(path);
    return art.arcane.gloss.config.menu.MenuRowMutations.replaceWithImage(document, path);
  }

  private static void validateIconImages(String type, String value) {
    if (type == null) {
      return;
    }
    String normalized = type.strip().toLowerCase(Locale.ROOT).replace("-", "").replace("_", "");
    if (normalized.equals("image") || normalized.equals("textimage")) {
      requireImageFile(value);
      return;
    }
    if (!normalized.equals("animated")
        && !normalized.equals("animatedimage")
        && !normalized.equals("animatedtextimage")) {
      return;
    }
    if (value == null) {
      requireImageFile(null);
      return;
    }
    for (String frame : value.split(",", -1)) {
      requireImageFile(frame.strip());
    }
  }

  private static void requireImageFile(String path) {
    try {
      Gloss.instance.getImageAssets().get(path);
    } catch (IOException | RuntimeException failure) {
      throw new IllegalArgumentException(
          "image must be a readable file inside plugins/Gloss/images: " + String.valueOf(path), failure);
    }
  }

  static boolean checkPermission(CommandSender sender, String permission) {
    if (sender.hasPermission(permission)) {
      return true;
    }
    send(sender, GlossMessages.PERMISSION_DENIED,
        MessageArgs.builder().untrusted("permission", permission).build());
    return false;
  }

  static void reportFailure(CommandSender sender, String menuId, Throwable failure) {
    Throwable cause = PanelCommandSupport.rootCause(failure);
    if (cause instanceof DocumentRevisionConflictException conflict) {
      sendLater(sender, GlossMessages.MENU_CONTENT_REVISION_CONFLICT,
          MessageArgs.builder()
              .untrusted("menu", conflict.id())
              .untrusted("expected", shortRevision(conflict.expectedRevision()))
              .untrusted("actual", shortRevision(conflict.actualRevision()))
              .build());
      return;
    }
    if (cause instanceof FileAlreadyExistsException) {
      sendLater(sender, GlossMessages.MENU_CONTENT_ALREADY_EXISTS,
          MessageArgs.builder().untrusted("menu", menuId).build());
      return;
    }
    if (cause instanceof NoSuchElementException || cause instanceof NoSuchFileException) {
      sendLater(sender, GlossMessages.MENU_UNAVAILABLE,
          MessageArgs.builder().untrusted("menu", menuId).build());
      return;
    }
    if (cause instanceof IllegalArgumentException || cause instanceof JsonParseException) {
      sendLater(sender, GlossMessages.MENU_CONTENT_INVALID,
          MessageArgs.builder().untrusted("reason", safeReason(cause)).build());
      return;
    }
    if (!(cause instanceof CancellationException)) {
      Gloss.logExceptionStack(true, cause, "Persistent menu content command failed for menu \"%s\".", menuId);
    }
    sendLater(sender, GlossMessages.MENU_CONTENT_FAILED,
        MessageArgs.builder()
            .untrusted("menu", menuId)
            .untrusted("reason", safeReason(cause))
            .build());
  }

  static void sendLater(CommandSender sender, TextKey key, MessageArgs arguments) {
    Runnable feedback = () -> send(sender, key, arguments);
    boolean accepted = sender instanceof Player player
        ? SchedulerUtils.runEntity(Gloss.instance, player, feedback)
        : SchedulerUtils.runGlobal(Gloss.instance, feedback);
    if (!accepted) {
      Gloss.warnThrottled("menu-command-feedback-scheduling",
          "Unable to schedule persistent menu command feedback for %s.", sender.getName());
    }
  }

  private static void send(CommandSender sender, TextKey key, MessageArgs arguments) {
    sender.sendMessage(Gloss.instance.getLocalization().legacy(key, arguments));
  }

  static String shortRevision(String revision) {
    if (revision == null) {
      return "unknown";
    }
    return revision.length() <= 12 ? revision : revision.substring(0, 12);
  }

  private static String safeReason(Throwable failure) {
    String message = failure == null ? null : failure.getMessage();
    return message == null || message.isBlank()
        ? failure == null ? "unknown failure" : failure.getClass().getSimpleName()
        : message;
  }

  public static final class NewMenuIdHandler implements DirectorParameterHandler<String> {
    @Override
    public KList<String> getPossibilities() {
      return new KList<>();
    }

    @Override
    public String toString(String value) {
      return value == null ? "" : value;
    }

    @Override
    public String parse(String in, boolean force) throws DirectorParsingException {
      if (in == null || in.isBlank()) {
        throw new DirectorParsingException(
            GlossLocalization.globalText(GlossMessages.ERROR_MENU_NAME_REQUIRED));
      }
      try {
        return MenuIds.require(in);
      } catch (IllegalArgumentException failure) {
        throw new DirectorParsingException(failure.getMessage());
      }
    }

    @Override
    public boolean supports(Class<?> type) {
      return type == String.class;
    }
  }

  public static final class IconTypeHandler implements DirectorParameterHandler<String> {
    @Override
    public KList<String> getPossibilities() {
      return new KList<>(List.of("text", "image", "animated", "item", "block", "customItem", "entity"));
    }

    @Override
    public String toString(String value) {
      return value == null ? "" : value;
    }

    @Override
    public String parse(String in, boolean force) throws DirectorParsingException {
      if (in == null || in.isBlank()) {
        throw new DirectorParsingException(
            GlossLocalization.globalText(GlossMessages.ERROR_ROW_ICON_TYPE_REQUIRED));
      }
      return in.strip();
    }

    @Override
    public boolean supports(Class<?> type) {
      return type == String.class;
    }
  }

  public static final class StylePropertyHandler implements DirectorParameterHandler<String> {
    @Override
    public KList<String> getPossibilities() {
      return new KList<>(List.of(
          "billboard", "shadow", "seeThrough", "textAlignment", "backgroundArgb",
          "textOpacity", "lineWidth", "brightness", "viewRange", "shadowRadius",
          "shadowStrength", "cullingWidth", "cullingHeight", "glowColor", "scale",
          "scaleX", "scaleY", "scaleZ"));
    }

    @Override
    public String toString(String value) {
      return value == null ? "" : value;
    }

    @Override
    public String parse(String in, boolean force) throws DirectorParsingException {
      if (in == null || in.isBlank()) {
        throw new DirectorParsingException(
            GlossLocalization.globalText(GlossMessages.ERROR_ROW_STYLE_PROPERTY_REQUIRED));
      }
      return in.strip();
    }

    @Override
    public boolean supports(Class<?> type) {
      return type == String.class;
    }
  }
}
