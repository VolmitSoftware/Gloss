package art.arcane.gloss.command;

import art.arcane.gloss.Gloss;
import art.arcane.gloss.integration.ProviderStatus;
import art.arcane.gloss.integration.catalog.CustomItemCatalogResult;
import art.arcane.gloss.integration.catalog.CustomItemCatalogWriter;
import art.arcane.gloss.locale.GlossLocalization;
import art.arcane.gloss.locale.GlossMessages;
import art.arcane.volmlib.util.director.annotations.Director;
import art.arcane.volmlib.util.director.annotations.Param;
import art.arcane.volmlib.util.director.help.DirectorMiniMenu;
import art.arcane.volmlib.util.director.theme.DirectorProduct;
import art.arcane.volmlib.util.director.theme.DirectorTheme;
import art.arcane.volmlib.util.director.theme.DirectorThemes;
import art.arcane.volmlib.util.localization.MessageArgs;
import art.arcane.volmlib.util.localization.TextKey;
import art.arcane.volmlib.util.scheduling.SchedulerUtils;
import org.bukkit.SoundCategory;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;

import java.util.ArrayList;
import java.util.List;

@Director(name = "item", aliases = {"items"}, description = "Custom item provider tools", descriptionKey = "command.help.item")
public class CommandGlossItem {
  private static final String STATUS_COMMAND = "/gloss item status";

  private volatile CustomItemCatalogWriter writer;

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

  @Director(name = "status", description = "Show which custom item providers are active", descriptionKey = "command.help.item.status")
  public void status(
      @Param(name = "page", defaultValue = "1", description = "One-based list page", descriptionKey = "command.help.arg.list_page")
      int page,
      @Param(name = "sender", contextual = true)
      CommandSender sender
  ) {
    String permission = "gloss.items";
    if (!sender.hasPermission(permission)) {
      sendPermissionDenied(sender, permission);
      return;
    }

    if (!Gloss.instance.cfg().customItems().enabled()) {
      sender.sendMessage(Gloss.instance.getLocalization().legacy(GlossMessages.ITEMS_DISABLED));
      return;
    }

    List<ProviderStatus> statuses = Gloss.instance.getItemProviders().providerStatuses();
    GlossCommandPager.Window window = GlossCommandPager.window(statuses.size(), page, GlossCommandPager.ITEM_STATUS_PAGE_SIZE);
    DirectorMiniMenu.Theme theme = GlossCommandService.menuTheme();
    List<String> lines = new ArrayList<>();
    lines.add(DirectorMiniMenu.banner(Gloss.instance.getLocalization().text(GlossMessages.ITEMS_STATUS_HEADER), theme));

    int active = 0;
    for (ProviderStatus status : statuses) {
      if (status.active()) {
        active++;
      }
    }

    String summary = Gloss.instance.getLocalization().text(
        GlossMessages.ITEMS_STATUS_SUMMARY,
        MessageArgs.builder().untrusted("active", active).untrusted("total", statuses.size()).build()
    );
    lines.add("<" + theme.description() + ">" + DirectorMiniMenu.escapeText(summary) + "</" + theme.description() + ">");

    for (ProviderStatus status : statuses.subList(window.startIndex(), window.endIndex())) {
      String state = stateText(Gloss.instance.getLocalization(), status);
      String hover = Gloss.instance.getLocalization().text(
          GlossMessages.ITEMS_STATUS_ENTRY,
          MessageArgs.builder().untrusted("provider", status.id()).untrusted("plugin", status.pluginName()).build()
      );
      String color = stateColor(status, theme);
      lines.add("<hover:show_text:'" + DirectorMiniMenu.escapeText(hover).replace("\\", "\\\\").replace("'", "\\'") + "'>"
          + "<" + theme.muted() + ">⇀</" + theme.muted() + "> "
          + "<gradient:" + theme.primaryLeft() + ":" + theme.primaryRight() + ">" + DirectorMiniMenu.escapeText(status.id()) + "</gradient> "
          + "<" + color + ">" + DirectorMiniMenu.escapeText(state) + "</" + color + ">"
          + "</hover>");
    }

    GlossCommandPager.appendFooter(lines, window, STATUS_COMMAND, theme);

    if (sender.hasPermission("gloss.items.export")) {
      String hint = Gloss.instance.getLocalization().text(GlossMessages.ITEMS_STATUS_HINT);
      lines.add("<hover:show_text:'" + DirectorMiniMenu.escapeText(hint).replace("\\", "\\\\").replace("'", "\\'") + "'>"
          + "<click:run_command:/gloss item export>"
          + "<" + theme.muted() + ">⇀</" + theme.muted() + "> "
          + "<" + theme.optional() + ">/gloss item export</" + theme.optional() + ">"
          + "</click></hover>");
    }

    lines.add(DirectorMiniMenu.bar(theme));
    DirectorMiniMenu.deliver(sender, lines);
  }

  @Director(name = "export", description = "Export the custom item catalog for the web editor", descriptionKey = "command.help.item.export")
  public void export(@Param(name = "sender", contextual = true) CommandSender sender) {
    String permission = "gloss.items.export";
    if (!sender.hasPermission(permission)) {
      sendPermissionDenied(sender, permission);
      return;
    }

    if (!Gloss.instance.cfg().customItems().enabled()) {
      sender.sendMessage(Gloss.instance.getLocalization().legacy(GlossMessages.ITEMS_DISABLED));
      return;
    }

    CustomItemCatalogWriter catalogWriter = writer();
    if (catalogWriter.isRunning()) {
      sender.sendMessage(Gloss.instance.getLocalization().legacy(GlossMessages.ITEMS_EXPORT_BUSY));
      return;
    }

    sender.sendMessage(Gloss.instance.getLocalization().legacy(GlossMessages.ITEMS_EXPORT_STARTED));
    if (!catalogWriter.exportAsync(result -> report(sender, result))) {
      sender.sendMessage(Gloss.instance.getLocalization().legacy(GlossMessages.ITEMS_EXPORT_BUSY));
    }
  }

  private CustomItemCatalogWriter writer() {
    CustomItemCatalogWriter local = writer;
    if (local != null) {
      return local;
    }

    synchronized (this) {
      if (writer == null) {
        writer = new CustomItemCatalogWriter(Gloss.instance, Gloss.instance.getItemProviders(), Gloss.instance.getDataFolder());
      }
      return writer;
    }
  }

  private void report(CommandSender sender, CustomItemCatalogResult result) {
    if (!result.success()) {
      sendOnSender(sender, Gloss.instance.getLocalization().legacy(GlossMessages.ITEMS_EXPORT_FAILED));
      playSound(sender, false);
      return;
    }

    if (result.itemCount() == 0) {
      sendOnSender(sender, Gloss.instance.getLocalization().legacy(
          GlossMessages.ITEMS_EXPORT_EMPTY,
          MessageArgs.builder().untrusted("path", result.path()).build()
      ));
      playSound(sender, true);
      return;
    }

    sendOnSender(sender, Gloss.instance.getLocalization().legacy(
        GlossMessages.ITEMS_EXPORT_DONE,
        MessageArgs.builder()
            .untrusted("count", result.itemCount())
            .untrusted("providers", result.providerCount())
            .untrusted("path", result.path())
            .build()
    ));
    playSound(sender, true);
  }

  // the export finishes long after Director reported the command as handled, so it feeds back its own sound
  private void playSound(CommandSender sender, boolean success) {
    if (!GlossCommandService.commandSoundsEnabled()) {
      return;
    }
    if (!(sender instanceof Player player)) {
      return;
    }

    DirectorTheme theme = DirectorThemes.forProduct(DirectorProduct.GLOSS);
    runOnSender(sender, () -> player.playSound(player.getLocation(),
        success ? theme.getSuccessSound() : theme.getErrorSound(),
        SoundCategory.MASTER, 0.8f, success ? 1.3f : 0.85f));
  }

  private static TextKey stateKey(ProviderStatus status) {
    if (!status.pluginPresent()) {
      return GlossMessages.ITEMS_STATE_MISSING;
    }
    if (!status.active()) {
      return GlossMessages.ITEMS_STATE_INACTIVE;
    }
    return status.ready() ? GlossMessages.ITEMS_STATE_READY : GlossMessages.ITEMS_STATE_LOADING;
  }

  static String stateText(GlossLocalization localization, ProviderStatus status) {
    TextKey key = stateKey(status);
    if (key != GlossMessages.ITEMS_STATE_READY) {
      return localization.text(key);
    }
    return localization.text(
        key,
        MessageArgs.builder().untrusted("count", status.itemCount()).build()
    );
  }

  private static String stateColor(ProviderStatus status, DirectorMiniMenu.Theme theme) {
    if (!status.pluginPresent()) {
      return theme.muted();
    }
    if (!status.active()) {
      return theme.required();
    }
    return status.ready() ? theme.optional() : theme.description();
  }

  private void sendPermissionDenied(CommandSender sender, String permission) {
    sender.sendMessage(Gloss.instance.getLocalization().legacy(
        GlossMessages.PERMISSION_DENIED,
        MessageArgs.builder().untrusted("permission", permission).build()
    ));
  }
}
