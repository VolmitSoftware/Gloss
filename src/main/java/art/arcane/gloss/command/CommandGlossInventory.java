package art.arcane.gloss.command;

import art.arcane.gloss.Gloss;
import art.arcane.gloss.inventory.InventoryRuntime;
import art.arcane.gloss.inventory.InventoryMenuService;
import art.arcane.gloss.locale.GlossMessages;
import art.arcane.gloss.menu.MenuArguments;
import art.arcane.volmlib.util.collection.KList;
import art.arcane.volmlib.util.director.DirectorParameterHandler;
import art.arcane.volmlib.util.director.annotations.Director;
import art.arcane.volmlib.util.director.annotations.Param;
import art.arcane.volmlib.util.director.exceptions.DirectorParsingException;
import art.arcane.volmlib.util.director.help.DirectorMiniMenu;
import art.arcane.volmlib.util.localization.MessageArgs;
import org.bukkit.Bukkit;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/** {@code /gloss inventory} — lists, inspects and opens the {@code inventories/} documents. */
@Director(name = "inventory", aliases = {"inventories"}, description = "Inspect and open inventory menus",
    descriptionKey = "command.help.inventory.root")
public class CommandGlossInventory {
  private static final String LIST_COMMAND = "/gloss inventory list";

  private final Gloss plugin;

  public CommandGlossInventory(Gloss plugin) {
    this.plugin = plugin;
  }

  @Director(name = "list", description = "List configured inventory menus", descriptionKey = "command.help.inventory.list")
  public void list(
      @Param(name = "page", defaultValue = "1", description = "One-based list page",
          descriptionKey = "command.help.inventory.list.page")
      int page,
      @Param(name = "sender", contextual = true)
      CommandSender sender
  ) {
    if (GlossCommandMessages.denied(sender, "gloss.inventories")) {
      return;
    }
    InventoryMenuService service = service();
    List<String> ids = service == null ? List.of() : service.ids();
    if (ids.isEmpty()) {
      plugin.getLocalization().send(sender, GlossMessages.FORMS_NO_INVENTORIES);
      return;
    }
    DirectorMiniMenu.ContentPage window = GlossCommandPager.window(ids.size(), page, GlossCommandPager.TEXT_PAGE_SIZE);
    DirectorMiniMenu.Theme theme = GlossCommandService.menuTheme();
    List<String> lines = new ArrayList<>();
    GlossCommandPager.appendHeader(lines, plugin.getLocalization().text(GlossMessages.FORMS_INVENTORY_LIST_HEADER),
        window, theme);
    for (String id : ids.subList(window.startIndex(), window.endIndex())) {
      lines.add(GlossCommandPager.entry(id, plugin.getLocalization().text(GlossMessages.FORMS_INVENTORY_LIST_ENTRY,
          MessageArgs.builder().untrusted("id", id).build()), theme));
    }
    GlossCommandPager.appendFooter(lines, window, LIST_COMMAND, theme);
    DirectorMiniMenu.deliver(sender, lines);
  }

  @Director(name = "info", description = "Show an inventory menu's size and slots",
      descriptionKey = "command.help.inventory.info")
  public void info(
      @Param(name = "inventory", description = "Inventory menu id", descriptionKey = "command.help.inventory.info.id",
          customHandler = InventoryIdHandler.class)
      String id,
      @Param(name = "sender", contextual = true)
      CommandSender sender
  ) {
    if (GlossCommandMessages.denied(sender, "gloss.inventories.info")) {
      return;
    }
    InventoryRuntime runtime = runtime(sender, id);
    if (runtime == null) {
      return;
    }
    plugin.getLocalization().send(sender, GlossMessages.FORMS_INVENTORY_INFO, MessageArgs.builder()
        .untrusted("id", id)
        .untrusted("kind", runtime.doc().resolution())
        .untrusted("count", String.valueOf(runtime.doc().resolveSlots().size()))
        .build());
  }

  @Director(name = "open", description = "Open an inventory menu for a player", descriptionKey = "command.help.inventory.open")
  public void open(
      @Param(name = "inventory", description = "Inventory menu id", descriptionKey = "command.help.inventory.open.id",
          customHandler = InventoryIdHandler.class)
      String id,
      @Param(name = "player", defaultValue = "", description = "Player to open it for; defaults to you",
          descriptionKey = "command.help.inventory.open.player")
      String playerName,
      @Param(name = "args", defaultValue = "", description = "Space-separated key=value arguments",
          descriptionKey = "command.help.inventory.open.args")
      String args,
      @Param(name = "sender", contextual = true)
      CommandSender sender
  ) {
    if (GlossCommandMessages.denied(sender, "gloss.inventories.open")) {
      return;
    }
    Player target = target(sender, playerName);
    if (target == null) {
      return;
    }
    Map<String, Object> arguments;
    try {
      arguments = MenuArguments.parse(args);
    } catch (IllegalArgumentException badArguments) {
      plugin.getLocalization().send(sender, GlossMessages.FORMS_BAD_ARGS,
          MessageArgs.builder().untrusted("value", badArguments.getMessage()).build());
      return;
    }
    InventoryMenuService service = service();
    if (service == null || runtime(sender, id) == null) {
      return;
    }
    if (!service.open(target, id, arguments)) {
      plugin.getLocalization().send(sender, GlossMessages.FORMS_UNKNOWN_INVENTORY);
      return;
    }
    plugin.getLocalization().send(sender, GlossMessages.FORMS_INVENTORY_OPENED, MessageArgs.builder()
        .untrusted("id", id)
        .untrusted("player", target.getName())
        .build());
  }

  @Director(name = "reset", description = "Restore shipped inventory menu defaults", descriptionKey = "command.help.inventory.reset")
  public void reset(
      @Param(name = "sender", contextual = true)
      CommandSender sender,
      @Param(name = "name", defaultValue = "*", description = "Name to reset, or * for every shipped default",
          descriptionKey = "command.help.inventory.reset.name")
      String name
  ) {
    if (GlossCommandMessages.denied(sender, "gloss.inventories.reset")) {
      return;
    }
    InventoryMenuService service = service();
    GlossCommandMessages.sendResetResult(sender, "inventory", name,
        service == null ? List.of() : service.resetToDefault(name));
  }

  private InventoryMenuService service() {
    return plugin.service(InventoryMenuService.class);
  }

  private InventoryRuntime runtime(CommandSender sender, String id) {
    InventoryMenuService service = service();
    InventoryRuntime runtime = service == null ? null : service.runtime(id);
    if (runtime == null) {
      plugin.getLocalization().send(sender, GlossMessages.FORMS_UNKNOWN_INVENTORY,
          MessageArgs.builder().untrusted("id", id).build());
    }
    return runtime;
  }

  private Player target(CommandSender sender, String playerName) {
    if (playerName != null && !playerName.isBlank()) {
      Player named = Bukkit.getPlayerExact(playerName.trim());
      if (named == null) {
        plugin.getLocalization().send(sender, GlossMessages.COMMAND_PLAYER_ONLY);
      }
      return named;
    }
    if (sender instanceof Player self) {
      return self;
    }
    plugin.getLocalization().send(sender, GlossMessages.COMMAND_PLAYER_ONLY);
    return null;
  }

  /** Completes on the inventory menu ids currently loaded. */
  public static final class InventoryIdHandler implements DirectorParameterHandler<String> {
    @Override
    public KList<String> getPossibilities() {
      KList<String> out = new KList<>();
      Gloss plugin = Gloss.instance;
      InventoryMenuService service = plugin == null ? null : plugin.service(InventoryMenuService.class);
      if (service != null) {
        out.addAll(service.ids());
      }
      return out;
    }

    @Override
    public String toString(String value) {
      return value == null ? "" : value;
    }

    @Override
    public String parse(String in, boolean force) throws DirectorParsingException {
      if (in == null || in.isBlank()) {
        throw new DirectorParsingException("inventory menu id is required");
      }
      return in.strip();
    }

    @Override
    public boolean supports(Class<?> type) {
      return type == String.class;
    }
  }
}
