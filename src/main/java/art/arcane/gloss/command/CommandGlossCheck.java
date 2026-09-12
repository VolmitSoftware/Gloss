package art.arcane.gloss.command;

import art.arcane.gloss.Gloss;
import art.arcane.gloss.lint.Diagnostic;
import art.arcane.gloss.lint.WorkspaceLint;
import art.arcane.gloss.locale.GlossMessages;
import art.arcane.volmlib.util.director.annotations.Director;
import art.arcane.volmlib.util.director.annotations.Param;
import art.arcane.volmlib.util.director.help.DirectorMiniMenu;
import art.arcane.volmlib.util.localization.MessageArgument;
import org.bukkit.command.CommandSender;

import java.util.List;
import java.util.Locale;
import java.util.Optional;

@Director(name = "check", aliases = {"lint"}, descriptionKey = "command.help.check.root",
    description = "Check the workspace for broken references")
public final class CommandGlossCheck {
  public static final String PERMISSION = "gloss.check";

  private final Gloss plugin;

  public CommandGlossCheck(Gloss plugin) {
    this.plugin = plugin;
  }

  @Director(name = "workspace", aliases = {"run"}, sync = true,
      descriptionKey = "command.help.check.root",
      description = "Check the workspace for broken references")
  public void check(
      @Param(name = "kind", defaultValue = "*", descriptionKey = "command.help.check.arg_kind",
          description = "Limit the check to one document folder") String kind,
      @Param(name = "id", defaultValue = "*", descriptionKey = "command.help.check.arg_id",
          description = "Limit the check to one document") String id,
      @Param(name = "page", defaultValue = "1", descriptionKey = "command.help.history.arg_page",
          description = "One-based list page") int page,
      @Param(name = "sender", contextual = true) CommandSender sender) {
    if (GlossCommandMessages.denied(sender, PERMISSION)) {
      return;
    }
    WorkspaceLint lint = plugin == null ? null : plugin.service(WorkspaceLint.class);
    if (lint == null) {
      GlossCommandMessages.send(sender, GlossMessages.CHECK_CLEAN);
      return;
    }
    List<Diagnostic> diagnostics = lint.run(filter(kind), filter(id));
    if (diagnostics.isEmpty()) {
      GlossCommandMessages.send(sender, GlossMessages.CHECK_CLEAN);
      return;
    }
    DirectorMiniMenu.ContentPage window = GlossCommandPager.window(diagnostics.size(), page,
        GlossCommandPager.TEXT_PAGE_SIZE);
    for (Diagnostic diagnostic : diagnostics.subList(window.startIndex(), window.endIndex())) {
      GlossCommandMessages.send(sender, GlossMessages.CHECK_ENTRY,
          MessageArgument.untrusted("state",
              diagnostic.severity().name().toLowerCase(Locale.ROOT)),
          MessageArgument.untrusted("key", diagnostic.code()),
          MessageArgument.untrusted("kind", diagnostic.kind()),
          MessageArgument.untrusted("id", diagnostic.id()),
          MessageArgument.untrusted("path", diagnostic.pointer()),
          MessageArgument.untrusted("message", diagnostic.message()));
    }
    GlossCommandMessages.send(sender, GlossMessages.CHECK_SUMMARY,
        MessageArgument.trusted("count", diagnostics.size()));
  }

  private static Optional<String> filter(String value) {
    return value == null || value.isBlank() || value.equals("*")
        ? Optional.empty()
        : Optional.of(value);
  }
}
