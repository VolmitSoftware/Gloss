package art.arcane.gloss.command;

import art.arcane.gloss.Gloss;
import art.arcane.gloss.editor.EditorLink;
import art.arcane.gloss.editor.EditorPermissions;
import art.arcane.gloss.editor.EditorScope;
import art.arcane.gloss.editor.EditorService;
import art.arcane.gloss.locale.GlossLocalization;
import art.arcane.gloss.locale.GlossMessages;
import art.arcane.volmlib.util.collection.KList;
import art.arcane.volmlib.util.director.DirectorParameterHandler;
import art.arcane.volmlib.util.director.annotations.Director;
import art.arcane.volmlib.util.director.annotations.Param;
import art.arcane.volmlib.util.director.exceptions.DirectorParsingException;
import art.arcane.volmlib.util.director.help.DirectorMiniMenu;
import art.arcane.volmlib.util.editor.EditorTokens;
import art.arcane.volmlib.util.localization.MessageArgs;
import art.arcane.volmlib.util.localization.TextKey;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;

import java.io.IOException;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * {@code /gloss editor pair|status|tokens|revoke}: pairing links for the web editor, the same commands and keyed
 * options as {@code /animus editor}.
 */
@Director(name = "editor", description = "Pair the web editor with this server", descriptionKey = "command.help.editor")
public final class CommandGlossEditor {
  private static final String TOKENS_COMMAND = "/gloss editor tokens";

  private final Gloss plugin;

  public CommandGlossEditor(Gloss plugin) {
    this.plugin = plugin;
  }

  @Director(name = "pair", description = "Make a one-time link that pairs a browser",
      descriptionKey = "command.help.editor.pair")
  public void pair(
      @Param(name = "label", defaultValue = "", description = "Name shown in the token list",
          descriptionKey = "command.help.arg.editor_label") String label,
      @Param(name = "scopes", defaultValue = "", description = "What the editor may do, comma separated",
          descriptionKey = "command.help.arg.editor_scopes", customHandler = ScopesHandler.class) String scopes,
      @Param(name = "folders", defaultValue = "", description = "Limit to these folders, comma separated",
          descriptionKey = "command.help.arg.editor_folders") String folders,
      @Param(name = "expires", defaultValue = "", description = "Lifetime without use, such as 7d, 12h or 30m",
          descriptionKey = "command.help.arg.editor_expires") String expires,
      @Param(name = "sender", contextual = true) CommandSender sender) {
    EditorService editor = running(sender);
    if (editor == null) {
      return;
    }
    EditorTokens.Grant<EditorScope> grant;
    try {
      grant = grant(sender instanceof Player player ? Optional.of(player.getUniqueId()) : Optional.empty(),
          sender.hasPermission(EditorPermissions.COMMANDS) || !(sender instanceof Player), label, scopes, folders,
          expires, editor.settings().tokenDays());
    } catch (IllegalArgumentException invalid) {
      send(sender, GlossMessages.EDITOR_FAILED, MessageArgs.builder().untrusted("reason", invalid.getMessage()).build());
      return;
    }
    EditorService.PairingLink link;
    try {
      link = editor.pair(grant);
    } catch (IOException failure) {
      send(sender, GlossMessages.EDITOR_FAILED, MessageArgs.builder().untrusted("reason", failure.getMessage()).build());
      return;
    }
    long minutes = EditorTokens.PAIRING_LIFETIME.toMinutes();
    String token = link.pairing().tokenId();
    if (!(sender instanceof Player)) {
      send(sender, GlossMessages.EDITOR_LINK_CONSOLE, MessageArgs.builder().untrusted("minutes", minutes)
          .untrusted("url", link.link()).build());
      send(sender, GlossMessages.EDITOR_LINK_DETAILS, MessageArgs.builder()
          .untrusted("scopes", String.join(" ", grant.scopes().stream().map(EditorScope::wireName).toList()))
          .untrusted("folders", grant.folders().isEmpty() ? text(GlossMessages.EDITOR_ALL_FOLDERS, MessageArgs.empty())
              : text(GlossMessages.EDITOR_SOME_FOLDERS, MessageArgs.builder()
                  .untrusted("folders", String.join(",", grant.folders())).build()))
          .untrusted("lifetime", describe(grant.lifetime()))
          .untrusted("token", token).build());
      return;
    }
    DirectorMiniMenu.Theme theme = GlossCommandService.menuTheme();
    String ready = text(GlossMessages.EDITOR_LINK_READY, MessageArgs.builder().untrusted("minutes", minutes).build());
    String line = "<dark_gray>[<light_purple>Gloss</light_purple>]:</dark_gray> <green>" + DirectorMiniMenu.escapeText(ready)
        + "</green> " + button("open_url", link.link(), text(GlossMessages.EDITOR_OPEN_HOVER, MessageArgs.empty()),
        "<gradient:" + theme.primaryLeft() + ":" + theme.primaryRight() + ">[" + escape(GlossMessages.EDITOR_OPEN_LABEL)
            + "]</gradient>")
        + " " + button("copy_to_clipboard", link.link(), text(GlossMessages.EDITOR_COPY_HOVER, MessageArgs.empty()),
        "<" + theme.muted() + ">[" + escape(GlossMessages.EDITOR_COPY_LABEL) + "]</" + theme.muted() + ">")
        + " " + button("suggest_command", "/gloss editor revoke " + token,
        text(GlossMessages.EDITOR_REVOKE_HOVER, MessageArgs.empty()),
        "<red>[" + escape(GlossMessages.EDITOR_REVOKE_LABEL) + "]</red>");
    DirectorMiniMenu.deliver(sender, List.of(line));
  }

  @Director(name = "status", description = "Show the editor listener, relay and fingerprint",
      descriptionKey = "command.help.editor.status")
  public void status(@Param(name = "sender", contextual = true) CommandSender sender) {
    EditorService editor = running(sender);
    if (editor == null) {
      return;
    }
    if (!(sender instanceof Player)) {
      for (String line : editor.status()) {
        send(sender, GlossMessages.EDITOR_LINE, MessageArgs.builder().untrusted("line", line).build());
      }
      return;
    }
    String details = String.join("\n", editor.status());
    String line = "<dark_gray>[<light_purple>Gloss</light_purple>]:</dark_gray> <gray>"
        + DirectorMiniMenu.escapeText(editor.headline()) + "</gray> <hover:show_text:'" + hover(details) + "'><"
        + GlossCommandService.menuTheme().muted() + ">[" + escape(GlossMessages.EDITOR_DETAILS_LABEL) + "]</"
        + GlossCommandService.menuTheme().muted() + "></hover>";
    DirectorMiniMenu.deliver(sender, List.of(line));
  }

  @Director(name = "tokens", description = "List paired browsers", descriptionKey = "command.help.editor.tokens")
  public void tokens(
      @Param(name = "page", defaultValue = "1", description = "One-based list page",
          descriptionKey = "command.help.arg.list_page") int page,
      @Param(name = "sender", contextual = true) CommandSender sender) {
    EditorService editor = running(sender);
    if (editor == null || editor.api().isEmpty()) {
      return;
    }
    List<EditorTokens.Token<EditorScope>> tokens = editor.api().get().parts().tokens().list();
    if (tokens.isEmpty()) {
      send(sender, GlossMessages.EDITOR_TOKENS_EMPTY, MessageArgs.empty());
      return;
    }
    DirectorMiniMenu.Theme theme = GlossCommandService.menuTheme();
    List<String> entries = new ArrayList<>();
    for (EditorTokens.Token<EditorScope> token : tokens) {
      String details = (token.label().isEmpty() ? "" : "\"" + token.label() + "\" | ")
          + String.join(" ", token.scopes().stream().map(EditorScope::wireName).toList())
          + (token.folders().isEmpty() ? "" : " | folders " + String.join(",", token.folders()))
          + " | expires " + token.expires()
          + " | last used " + token.lastUsed().map(Object::toString).orElse("never")
          + token.client().map((String client) -> " | " + client).orElse("")
          + " | streams " + editor.api().get().parts().events().open(token.id());
      entries.add(GlossCommandPager.entry(token.id(), details, theme));
    }
    DirectorMiniMenu.deliver(sender, GlossCommandPager.menu(TOKENS_COMMAND + " · " + tokens.size(), entries, page,
        TOKENS_COMMAND, theme));
  }

  @Director(name = "revoke", description = "End a pairing or cancel a link", descriptionKey = "command.help.editor.revoke")
  public void revoke(
      @Param(name = "token", description = "Token id, or all", descriptionKey = "command.help.arg.editor_token",
          customHandler = TokenHandler.class) String token,
      @Param(name = "sender", contextual = true) CommandSender sender) {
    EditorService editor = running(sender);
    if (editor == null) {
      return;
    }
    List<String> revoked;
    try {
      revoked = editor.revoke(token);
    } catch (IOException failure) {
      send(sender, GlossMessages.EDITOR_FAILED, MessageArgs.builder().untrusted("reason", failure.getMessage()).build());
      return;
    }
    if (revoked.isEmpty()) {
      send(sender, GlossMessages.EDITOR_UNKNOWN_TOKEN, MessageArgs.builder().untrusted("token", token).build());
    } else if (revoked.size() == 1) {
      send(sender, GlossMessages.EDITOR_REVOKED_ONE, MessageArgs.empty());
    } else {
      send(sender, GlossMessages.EDITOR_REVOKED_MANY, MessageArgs.builder().untrusted("count", revoked.size()).build());
    }
  }

  /** Reads the pairing options; a blank option keeps its default. */
  static EditorTokens.Grant<EditorScope> grant(Optional<UUID> admin, boolean mayRunCommands, String label, String scopes,
                                  String folders, String expires, int tokenDays) {
    List<EditorScope> granted = EditorScope.DEFAULT_PAIRING;
    if (!scopes.isBlank()) {
      List<EditorScope> parsed = new ArrayList<>();
      for (String name : scopes.split(",")) {
        if (name.isBlank()) {
          continue;
        }
        EditorScope scope = EditorScope.parse(name.strip()).orElseThrow(() -> new IllegalArgumentException(
            "Unknown scope " + name.strip() + "; scopes are " + String.join(", ", EditorScope.names())));
        if (!parsed.contains(scope)) {
          parsed.add(scope);
        }
      }
      granted = List.copyOf(parsed);
    }
    if (granted.contains(EditorScope.PUBLISH_COMMANDS) && !mayRunCommands) {
      throw new IllegalArgumentException("publish:commands lets the editor publish documents that run console "
          + "commands; it needs " + EditorPermissions.COMMANDS + " or the console");
    }
    List<String> limited = Arrays.stream(folders.split(",")).map(String::strip)
        .filter((String folder) -> !folder.isEmpty()).distinct().toList();
    for (String folder : limited) {
      if (!EditorLink.validFolder(folder)) {
        throw new IllegalArgumentException("folders take folder names such as shop or shop/sub; " + folder
            + " is not one");
      }
    }
    Duration lifetime = expires.isBlank() ? Duration.ofDays(tokenDays) : duration(expires.strip());
    return new EditorTokens.Grant<>(label.strip(), admin, granted, limited, lifetime);
  }

  static Duration duration(String text) {
    if (!text.matches("[1-9][0-9]{0,5}[dhm]")) {
      throw new IllegalArgumentException("expires takes a number with d, h or m, such as 7d");
    }
    long amount = Long.parseLong(text.substring(0, text.length() - 1));
    Duration duration = switch (text.charAt(text.length() - 1)) {
      case 'd' -> Duration.ofDays(amount);
      case 'h' -> Duration.ofHours(amount);
      default -> Duration.ofMinutes(amount);
    };
    if (duration.compareTo(Duration.ofDays(365)) > 0) {
      throw new IllegalArgumentException("expires is at most 365 days");
    }
    return duration;
  }

  static String describe(Duration lifetime) {
    if (lifetime.toDays() > 0 && lifetime.equals(Duration.ofDays(lifetime.toDays()))) {
      return lifetime.toDays() + "d";
    }
    if (lifetime.toHours() > 0 && lifetime.equals(Duration.ofHours(lifetime.toHours()))) {
      return lifetime.toHours() + "h";
    }
    return lifetime.toMinutes() + "m";
  }

  private EditorService running(CommandSender sender) {
    if (!sender.hasPermission(EditorPermissions.PAIR)) {
      send(sender, GlossMessages.PERMISSION_DENIED, MessageArgs.builder().untrusted("permission", EditorPermissions.PAIR)
          .build());
      return null;
    }
    EditorService editor = plugin.getEditorService();
    if (editor == null || editor.api().isEmpty()) {
      send(sender, GlossMessages.EDITOR_NOT_RUNNING, MessageArgs.empty());
      return null;
    }
    return editor;
  }

  private static String button(String action, String value, String hover, String label) {
    return "<hover:show_text:'" + hover(hover) + "'><click:" + action + ":'" + value.replace("'", "\\'") + "'>" + label
        + "</click></hover>";
  }

  private static String hover(String text) {
    return DirectorMiniMenu.escapeText(text).replace("\\", "\\\\").replace("'", "\\'");
  }

  private String escape(TextKey key) {
    return DirectorMiniMenu.escapeText(text(key, MessageArgs.empty()));
  }

  private String text(TextKey key, MessageArgs arguments) {
    return plugin.getLocalization().text(key, arguments);
  }

  private void send(CommandSender sender, TextKey key, MessageArgs arguments) {
    plugin.getLocalization().send(sender, key, arguments);
  }

  /** Completes {@code scopes=} with every scope name. */
  public static final class ScopesHandler implements DirectorParameterHandler<String> {
    @Override
    public KList<String> getPossibilities() {
      KList<String> names = new KList<>();
      names.addAll(EditorScope.names());
      return names;
    }

    @Override
    public String toString(String value) {
      return value == null ? "" : value;
    }

    @Override
    public String parse(String in, boolean force) {
      return in == null ? "" : in.strip();
    }

    @Override
    public boolean supports(Class<?> type) {
      return type == String.class;
    }
  }

  /** Completes {@code revoke} with {@code all} and the token ids. */
  public static final class TokenHandler implements DirectorParameterHandler<String> {
    @Override
    public KList<String> getPossibilities() {
      KList<String> options = new KList<>();
      options.add("all");
      Gloss plugin = Gloss.instance;
      if (plugin != null && plugin.getEditorService() != null && plugin.getEditorService().api().isPresent()) {
        plugin.getEditorService().api().get().parts().tokens().list()
            .forEach((EditorTokens.Token<EditorScope> token) -> options.add(token.id()));
      }
      return options;
    }

    @Override
    public String toString(String value) {
      return value == null ? "" : value;
    }

    @Override
    public String parse(String in, boolean force) throws DirectorParsingException {
      if (in == null || in.isBlank()) {
        throw new DirectorParsingException(GlossLocalization.globalText(GlossMessages.ERROR_EDITOR_TOKEN_REQUIRED));
      }
      return in.strip();
    }

    @Override
    public boolean supports(Class<?> type) {
      return type == String.class;
    }
  }
}
