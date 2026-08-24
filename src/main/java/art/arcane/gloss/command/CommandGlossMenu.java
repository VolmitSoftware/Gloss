package art.arcane.gloss.command;

import art.arcane.gloss.Gloss;
import art.arcane.gloss.panel.PanelIds;
import art.arcane.gloss.panel.PanelTransform;
import art.arcane.gloss.config.MenuDefinitionData;
import art.arcane.gloss.config.menu.MenuRowMutations;
import art.arcane.gloss.locale.GlossLocalization;
import art.arcane.gloss.locale.GlossMessages;
import art.arcane.gloss.service.PanelCreationService;
import art.arcane.volmlib.util.collection.KList;
import art.arcane.volmlib.util.director.DirectorParameterHandler;
import art.arcane.volmlib.util.director.annotations.Director;
import art.arcane.volmlib.util.director.annotations.Param;
import art.arcane.volmlib.util.director.exceptions.DirectorParsingException;
import art.arcane.volmlib.util.director.help.DirectorMiniMenu;
import art.arcane.volmlib.util.director.theme.DirectorProduct;
import art.arcane.volmlib.util.director.theme.DirectorTheme;
import art.arcane.volmlib.util.director.theme.DirectorThemes;
import art.arcane.volmlib.util.localization.MessageArgs;
import art.arcane.volmlib.util.scheduling.SchedulerUtils;
import org.bukkit.Location;
import org.bukkit.SoundCategory;
import org.bukkit.World;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;

import java.nio.file.FileAlreadyExistsException;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CancellationException;

@Director(name = "menu", aliases = {"menus"}, description = "Create, open and edit hologram menus", descriptionKey = "command.help.menu")
public class CommandGlossMenu {
  public static final String PERMISSION = "gloss.menus";
  static final String EDIT_PERMISSION = "gloss.menus.edit";
  static final String CREATE_PERMISSION = "gloss.menus.create";
  private static final String OMITTED_HOLOGRAM_TEXT = "⁣";
  private static final String LIST_COMMAND = "/gloss menu list";

  private final Gloss plugin;

  public CommandGlossMenu(Gloss plugin) {
    this.plugin = plugin;
  }

  @Director(name = "list", description = "List all configured menus you can open", descriptionKey = "command.help.menu.list")
  public void list(
      @Param(name = "page", defaultValue = "1", description = "One-based list page", descriptionKey = "command.help.arg.list_page")
      int page,
      @Param(name = "sender", contextual = true)
      CommandSender sender
  ) {
    String permission = "gloss.menus.list";
    if (!sender.hasPermission(permission)) {
      sendPermissionDenied(sender, permission);
      return;
    }

    List<String> menus = new ArrayList<>(plugin.getMenuCatalog().keys());
    if (menus.isEmpty()) {
      plugin.getLocalization().send(sender, GlossMessages.NO_MENUS);
      return;
    }

    DirectorMiniMenu.ContentPage window = GlossCommandPager.window(menus.size(), page, GlossCommandPager.TEXT_PAGE_SIZE);
    DirectorMiniMenu.Theme theme = GlossCommandService.menuTheme();
    List<String> lines = new ArrayList<>();
    GlossCommandPager.appendHeader(lines, plugin.getLocalization().text(GlossMessages.MENU_LIST_HEADER), window, theme);
    for (String menu : menus.subList(window.startIndex(), window.endIndex())) {
      String hover = plugin.getLocalization().text(
          GlossMessages.MENU_LIST_ENTRY,
          MessageArgs.builder().untrusted("menu", menu).build()
      );
      lines.add(menuEntryLine(menu, hover, theme));
    }
    GlossCommandPager.appendFooter(lines, window, LIST_COMMAND, theme);
    DirectorMiniMenu.deliver(sender, lines);
  }

  @Director(name = "create", description = "Create a persistent hologram at your current position", descriptionKey = "command.help.menu.create")
  public void create(
      @Param(name = "hologram", description = "New hologram and root-menu id", descriptionKey = "command.help.arg.hologram_id", customHandler = HologramIdHandler.class)
      String id,
      @Param(name = "text", description = "Optional MiniMessage text", descriptionKey = "command.help.arg.hologram_text", defaultValue = OMITTED_HOLOGRAM_TEXT)
      String text,
      @Param(name = "sender", contextual = true)
      CommandSender sender
  ) {
    if (!sender.hasPermission(CREATE_PERMISSION)) {
      sendPermissionDenied(sender, CREATE_PERMISSION);
      playCreateOutcome(sender, false);
      return;
    }
    if (!sender.hasPermission(CommandGlossPanel.PERMISSION)) {
      sendPermissionDenied(sender, CommandGlossPanel.PERMISSION);
      playCreateOutcome(sender, false);
      return;
    }
    if (!(sender instanceof Player player)) {
      plugin.getLocalization().send(sender, GlossMessages.COMMAND_PLAYER_ONLY);
      playCreateOutcome(sender, false);
      return;
    }

    Location location = player.getLocation().clone();
    World world = location.getWorld();
    if (world == null) {
      plugin.getLocalization().send(sender,
          GlossMessages.PANELS_WORLD_UNAVAILABLE,
          MessageArgs.builder().untrusted("world", "unknown").build()
      );
      playCreateOutcome(sender, false);
      return;
    }
    PanelTransform transform = new PanelTransform(
        world.getKey().toString(),
        world.getUID(),
        location.getX(),
        location.getY(),
        location.getZ(),
        location.getYaw(),
        location.getPitch(),
        0.0D,
        1.0D
    );
    String initialText = OMITTED_HOLOGRAM_TEXT.equals(text) ? null : text;
    plugin.getPanelCreationService().create(id, initialText, transform)
        .whenComplete((creation, failure) -> sendCreateResult(sender, id, creation, failure));
  }

  @Director(name = "open", description = "Open a menu by id, or show the menu list when set to *", descriptionKey = "command.help.menu.open")
  public void open(
      @Param(name = "menu", description = "Menu id to open (* shows all menus)", descriptionKey = "command.help.arg.menu", defaultValue = "*", customHandler = MenuNameHandler.class)
      String menuName,
      @Param(name = "sender", contextual = true)
      CommandSender sender
  ) {
    String permission = "gloss.menus.open";
    if (!sender.hasPermission(permission)) {
      sendPermissionDenied(sender, permission);
      return;
    }

    if ("*".equals(menuName.trim())) {
      list(1, sender);
      return;
    }

    if (!(sender instanceof Player player)) {
      plugin.getLocalization().send(sender, GlossMessages.MENUS_PLAYER_ONLY);
      return;
    }

    openMenu(player, sender, menuName, true);
  }

  @Director(name = "back", description = "Reopen your previous menu session", descriptionKey = "command.help.menu.back")
  public void back(@Param(name = "sender", contextual = true) CommandSender sender) {
    String permission = "gloss.menus.back";
    if (!sender.hasPermission(permission)) {
      sendPermissionDenied(sender, permission);
      return;
    }

    if (!(sender instanceof Player player)) {
      plugin.getLocalization().send(sender, GlossMessages.COMMAND_PLAYER_ONLY);
      return;
    }

    if (!plugin.getSessionManager().openLastSession(player)) {
      plugin.getLocalization().send(player, GlossMessages.NO_PREVIOUS_MENU);
    }
  }

  @Director(name = "close", description = "Close your currently open menu session", descriptionKey = "command.help.menu.close")
  public void close(@Param(name = "sender", contextual = true) CommandSender sender) {
    String permission = "gloss.menus.close";
    if (!sender.hasPermission(permission)) {
      sendPermissionDenied(sender, permission);
      return;
    }

    if (!(sender instanceof Player player)) {
      plugin.getLocalization().send(sender, GlossMessages.COMMAND_PLAYER_ONLY);
      return;
    }

    if (plugin.getSessionManager().destroySession(player, false)) {
      plugin.getLocalization().send(player, GlossMessages.MENU_CLOSED);
    } else {
      plugin.getLocalization().send(player, GlossMessages.NO_OPEN_MENU);
    }
  }

  @Director(name = "move", description = "Move your open menu to your current position", descriptionKey = "command.help.menu.move")
  public void move(@Param(name = "sender", contextual = true) CommandSender sender) {
    String permission = "gloss.menus.move";
    if (!sender.hasPermission(permission)) {
      sendPermissionDenied(sender, permission);
      return;
    }

    if (!(sender instanceof Player player)) {
      plugin.getLocalization().send(sender, GlossMessages.COMMAND_PLAYER_ONLY);
      return;
    }

    if (plugin.getSessionManager().moveSession(player)) {
      plugin.getLocalization().send(player, GlossMessages.MENU_MOVED);
    } else {
      plugin.getLocalization().send(player, GlossMessages.NO_OPEN_MENU);
    }
  }

  @Director(name = "addrow", description = "Append a text decoration row", descriptionKey = "command.help.menu.addrow")
  public void addRow(
      @Param(name = "menu", description = "Loaded menu id", descriptionKey = "command.help.arg.content_menu", customHandler = ExistingMenuHandler.class)
      String menuId,
      @Param(name = "text", description = "MiniMessage row text", descriptionKey = "command.help.arg.row_text")
      String text,
      @Param(name = "sender", contextual = true)
      CommandSender sender
  ) {
    MenuRowCommandSupport.mutate(sender, menuId, "addrow",
        document -> MenuRowMutations.addTextRow(document, text), EDIT_PERMISSION);
  }

  @Director(name = "insertrow", description = "Insert a text decoration row", descriptionKey = "command.help.menu.insertrow")
  public void insertRow(
      @Param(name = "menu", description = "Loaded menu id", descriptionKey = "command.help.arg.content_menu", customHandler = ExistingMenuHandler.class)
      String menuId,
      @Param(name = "row", description = "One-based component row", descriptionKey = "command.help.arg.row_index")
      int row,
      @Param(name = "text", description = "MiniMessage row text", descriptionKey = "command.help.arg.row_text")
      String text,
      @Param(name = "sender", contextual = true)
      CommandSender sender
  ) {
    MenuRowCommandSupport.mutate(sender, menuId, "insertrow",
        document -> MenuRowMutations.insertTextRow(document, row, text), EDIT_PERMISSION);
  }

  @Director(name = "setrow", description = "Set a button or decoration row's text", descriptionKey = "command.help.menu.setrow")
  public void setRow(
      @Param(name = "menu", description = "Loaded menu id", descriptionKey = "command.help.arg.content_menu", customHandler = ExistingMenuHandler.class)
      String menuId,
      @Param(name = "row", description = "One-based component row", descriptionKey = "command.help.arg.row_index")
      int row,
      @Param(name = "text", description = "MiniMessage row text", descriptionKey = "command.help.arg.row_text")
      String text,
      @Param(name = "sender", contextual = true)
      CommandSender sender
  ) {
    MenuRowCommandSupport.mutate(sender, menuId, "setrow",
        document -> MenuRowMutations.setTextRow(document, row, text), EDIT_PERMISSION);
  }

  @Director(name = "removerow", description = "Remove a component row", descriptionKey = "command.help.menu.removerow")
  public void removeRow(
      @Param(name = "menu", description = "Loaded menu id", descriptionKey = "command.help.arg.content_menu", customHandler = ExistingMenuHandler.class)
      String menuId,
      @Param(name = "row", description = "One-based component row", descriptionKey = "command.help.arg.row_index")
      int row,
      @Param(name = "sender", contextual = true)
      CommandSender sender
  ) {
    MenuRowCommandSupport.mutate(sender, menuId, "removerow",
        document -> MenuRowMutations.removeRow(document, row), EDIT_PERMISSION);
  }

  @Director(name = "offsetrow", description = "Move a row with absolute or ~relative offsets", descriptionKey = "command.help.menu.offsetrow")
  public void offsetRow(
      @Param(name = "menu", description = "Loaded menu id", descriptionKey = "command.help.arg.content_menu", customHandler = ExistingMenuHandler.class)
      String menuId,
      @Param(name = "row", description = "One-based component row", descriptionKey = "command.help.arg.row_index")
      int row,
      @Param(name = "x", description = "Absolute or ~relative row X", descriptionKey = "command.help.arg.row_x")
      String x,
      @Param(name = "y", description = "Absolute or ~relative row Y", descriptionKey = "command.help.arg.row_y")
      String y,
      @Param(name = "z", description = "Absolute or ~relative row Z", descriptionKey = "command.help.arg.row_z")
      String z,
      @Param(name = "sender", contextual = true)
      CommandSender sender
  ) {
    MenuRowCommandSupport.mutate(sender, menuId, "offsetrow",
        document -> MenuRowMutations.offsetRow(document, row, x, y, z), EDIT_PERMISSION);
  }

  @Director(name = "seticon", description = "Replace a button or decoration row icon", descriptionKey = "command.help.menu.seticon")
  public void setIcon(
      @Param(name = "menu", description = "Loaded menu id", descriptionKey = "command.help.arg.content_menu", customHandler = ExistingMenuHandler.class)
      String menuId,
      @Param(name = "row", description = "One-based component row", descriptionKey = "command.help.arg.row_index")
      int row,
      @Param(name = "type", description = "Icon type", descriptionKey = "command.help.arg.row_icon_type", customHandler = MenuRowCommandSupport.IconTypeHandler.class)
      String type,
      @Param(name = "value", description = "Icon content or registry id", descriptionKey = "command.help.arg.row_icon_value")
      String value,
      @Param(name = "sender", contextual = true)
      CommandSender sender
  ) {
    MenuRowCommandSupport.mutate(sender, menuId, "seticon",
        document -> MenuRowCommandSupport.setIconMutation(document, row, type, value), EDIT_PERMISSION);
  }

  @Director(name = "style", description = "Set or clear one row display-style property", descriptionKey = "command.help.menu.style")
  public void style(
      @Param(name = "menu", description = "Loaded menu id", descriptionKey = "command.help.arg.content_menu", customHandler = ExistingMenuHandler.class)
      String menuId,
      @Param(name = "row", description = "One-based component row", descriptionKey = "command.help.arg.row_index")
      int row,
      @Param(name = "property", description = "Display-style property", descriptionKey = "command.help.arg.row_style_property", customHandler = MenuRowCommandSupport.StylePropertyHandler.class)
      String property,
      @Param(name = "value", description = "Style value, or * to clear", descriptionKey = "command.help.arg.row_style_value")
      String value,
      @Param(name = "sender", contextual = true)
      CommandSender sender
  ) {
    MenuRowCommandSupport.mutate(sender, menuId, "style",
        document -> MenuRowMutations.setStyle(document, row, property, value), EDIT_PERMISSION);
  }

  @Director(name = "image", description = "Replace menu content with one centered image file", descriptionKey = "command.help.menu.image")
  public void image(
      @Param(name = "menu", description = "Loaded menu id", descriptionKey = "command.help.arg.content_menu", customHandler = ExistingMenuHandler.class)
      String menuId,
      @Param(name = "path", description = "File beneath the Gloss images folder", descriptionKey = "command.help.arg.image_path")
      String path,
      @Param(name = "sender", contextual = true)
      CommandSender sender
  ) {
    MenuRowCommandSupport.mutate(sender, menuId, "image",
        document -> MenuRowCommandSupport.replaceWithImageMutation(document, path), EDIT_PERMISSION);
  }

  @Director(name = "new", description = "Create a blank hologram menu from the shipped baseline", descriptionKey = "command.help.menu.new")
  public void newMenu(
      @Param(name = "menu", description = "New nested menu id", descriptionKey = "command.help.arg.new_menu", customHandler = MenuRowCommandSupport.NewMenuIdHandler.class)
      String menuId,
      @Param(name = "sender", contextual = true)
      CommandSender sender
  ) {
    if (!MenuRowCommandSupport.checkPermission(sender, EDIT_PERMISSION)) {
      return;
    }
    Gloss.instance.getMenuCatalog().create(menuId, art.arcane.gloss.config.menu.MenuBaselines.blankHologramSource())
        .whenComplete((document, failure) -> {
          if (failure != null) {
            MenuRowCommandSupport.reportFailure(sender, menuId, failure);
            return;
          }
          MenuRowCommandSupport.sendLater(sender, GlossMessages.MENU_CONTENT_CREATED,
              MessageArgs.builder()
                  .untrusted("menu", document.id())
                  .untrusted("revision", MenuRowCommandSupport.shortRevision(document.revision()))
                  .build());
        });
  }

  @Director(name = "copy", description = "Copy a loaded menu to a new nested id", descriptionKey = "command.help.menu.copy")
  public void copy(
      @Param(name = "menu", description = "Loaded source menu id", descriptionKey = "command.help.arg.source_menu", customHandler = ExistingMenuHandler.class)
      String menuId,
      @Param(name = "newMenu", description = "New nested menu id", descriptionKey = "command.help.arg.new_menu")
      String newMenuId,
      @Param(name = "sender", contextual = true)
      CommandSender sender
  ) {
    if (!MenuRowCommandSupport.checkPermission(sender, EDIT_PERMISSION)) {
      return;
    }
    Gloss.instance.getMenuCatalog().copy(menuId, newMenuId)
        .whenComplete((document, failure) -> {
          if (failure != null) {
            MenuRowCommandSupport.reportFailure(sender, newMenuId, failure);
            return;
          }
          MenuRowCommandSupport.sendLater(sender, GlossMessages.MENU_CONTENT_COPIED,
              MessageArgs.builder()
                  .untrusted("source", menuId)
                  .untrusted("menu", document.id())
                  .untrusted("revision", MenuRowCommandSupport.shortRevision(document.revision()))
                  .build());
        });
  }

  private static Throwable rootCause(Throwable failure) {
    Throwable current = failure;
    while (current instanceof java.util.concurrent.CompletionException && current.getCause() != null) {
      current = current.getCause();
    }
    return current;
  }

  static String menuEntryLine(String menu, String hover, DirectorMiniMenu.Theme theme) {
    return "<hover:show_text:'" + DirectorMiniMenu.escapeText(hover).replace("\\", "\\\\").replace("'", "\\'")
        + "'><click:run_command:/gloss menu open " + menu + ">"
        + "<" + theme.muted() + ">⇀</" + theme.muted() + "> "
        + "<gradient:" + theme.primaryLeft() + ":" + theme.primaryRight() + ">" + DirectorMiniMenu.escapeText(menu) + "</gradient>"
        + "</click></hover>";
  }

  private boolean openMenu(Player player, CommandSender feedback, String menuName, boolean includeRootPermission) {
    MenuDefinitionData ui = plugin.getMenuCatalog().definition(menuName).orElse(null);
    if (ui == null) {
      plugin.getLocalization().send(feedback,
          GlossMessages.MENU_UNAVAILABLE,
          MessageArgs.builder().untrusted("menu", menuName).build()
      );
      return false;
    }

    if (includeRootPermission && !player.hasPermission("gloss.menus.open")) {
      sendPermissionDenied(feedback, "gloss.menus.open");
      return false;
    }

    if (!player.hasPermission("gloss.open." + ui.getId())) {
      plugin.getLocalization().send(feedback,
          GlossMessages.MENU_PERMISSION_DENIED,
          MessageArgs.builder().untrusted("menu", ui.getId()).build()
      );
      return false;
    }

    try {
      plugin.getSessionManager().createNewSession(player, ui);
      return true;
    } catch (Throwable e) {
      Gloss.logExceptionStack(true, e, "Error opening menu \"%s\".", ui.getId());
      plugin.getLocalization().send(feedback,
          GlossMessages.MENU_OPEN_FAILED,
          MessageArgs.builder().untrusted("menu", ui.getId()).build()
      );
      return false;
    }
  }

  private void sendPermissionDenied(CommandSender sender, String permission) {
    plugin.getLocalization().send(sender,
        GlossMessages.PERMISSION_DENIED,
        MessageArgs.builder().untrusted("permission", permission).build()
    );
  }

  private void sendCreateResult(CommandSender sender, String requestedId,
                                PanelCreationService.Creation creation, Throwable failure) {
    Runnable feedback = () -> {
      playCreateOutcome(sender, failure == null);
      if (failure == null) {
        plugin.getLocalization().send(sender,
            GlossMessages.HOLOGRAM_MENU_CREATED,
            MessageArgs.builder()
                .untrusted("hologram", creation.board().id())
                .untrusted("menu", creation.menu().id())
                .build()
        );
        return;
      }
      Throwable cause = rootCause(failure);
      if (cause instanceof FileAlreadyExistsException) {
        plugin.getLocalization().send(sender,
            GlossMessages.HOLOGRAM_ALREADY_EXISTS,
            MessageArgs.builder().untrusted("hologram", requestedId).build()
        );
        return;
      }
      if (cause instanceof PanelCreationService.DurabilityUncertainException) {
        plugin.getLocalization().send(sender,
            GlossMessages.HOLOGRAM_DURABILITY_UNCERTAIN,
            MessageArgs.builder().untrusted("hologram", requestedId).build()
        );
        return;
      }
      if (!(cause instanceof IllegalArgumentException)
          && !(cause instanceof CancellationException)) {
        Gloss.logExceptionStack(true, cause,
            "Persistent hologram creation failed for \"%s\".", requestedId);
      }
      plugin.getLocalization().send(sender,
          GlossMessages.HOLOGRAM_CREATE_FAILED,
          MessageArgs.builder().untrusted("hologram", requestedId).build()
      );
    };
    boolean accepted = sender instanceof Player player
        ? SchedulerUtils.runEntity(plugin, player, feedback)
        : SchedulerUtils.runGlobal(plugin, feedback);
    if (!accepted) {
      Gloss.warnThrottled("hologram-command-feedback-scheduling",
          "Unable to schedule hologram creation feedback for %s.",
          sender.getName());
    }
  }

  private static void playCreateOutcome(CommandSender sender, boolean successful) {
    if (!GlossCommandService.commandSoundsEnabled()) {
      return;
    }
    if (!(sender instanceof Player player) || !player.isOnline()) {
      return;
    }
    DirectorTheme theme = DirectorThemes.forProduct(DirectorProduct.GLOSS);
    player.playSound(player.getLocation(),
        successful ? theme.getSuccessSound() : theme.getErrorSound(),
        SoundCategory.MASTER, 0.8F, successful ? 1.3F : 0.85F);
  }

  public static class ExistingMenuHandler implements DirectorParameterHandler<String> {
    @Override
    public KList<String> getPossibilities() {
      KList<String> out = new KList<>();
      if (Gloss.instance == null || Gloss.instance.getMenuCatalog() == null) {
        return out;
      }

      out.addAll(Gloss.instance.getMenuCatalog().keys());
      out.removeDuplicates();
      return out;
    }

    @Override
    public String toString(String value) {
      return value == null ? "" : value;
    }

    @Override
    public String parse(String in, boolean force) throws DirectorParsingException {
      if (in == null || in.trim().isEmpty()) {
        throw new DirectorParsingException(GlossLocalization.globalText(GlossMessages.ERROR_MENU_NAME_REQUIRED));
      }

      String value = in.trim();
      for (String candidate : getPossibilities()) {
        if (candidate.equalsIgnoreCase(value)) {
          return candidate;
        }
      }

      return value;
    }

    @Override
    public boolean supports(Class<?> type) {
      return type == String.class;
    }
  }

  public static final class MenuNameHandler extends ExistingMenuHandler {
    @Override
    public KList<String> getPossibilities() {
      KList<String> menus = super.getPossibilities();
      menus.add(0, "*");
      return menus;
    }
  }

  public static final class HologramIdHandler implements DirectorParameterHandler<String> {
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
            GlossLocalization.globalText(GlossMessages.ERROR_HOLOGRAM_ID_REQUIRED));
      }
      try {
        return PanelIds.canonicalize(in);
      } catch (IllegalArgumentException failure) {
        throw new DirectorParsingException(failure.getMessage());
      }
    }

    @Override
    public boolean supports(Class<?> type) {
      return type == String.class;
    }
  }

}
