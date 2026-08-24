package art.arcane.gloss.command;

import art.arcane.gloss.Gloss;
import art.arcane.gloss.panel.PanelDefinition;
import art.arcane.gloss.panel.PanelFollow;
import art.arcane.gloss.panel.PanelFollowMode;
import art.arcane.gloss.panel.PanelFollowPose;
import art.arcane.gloss.panel.PanelFollowRotation;
import art.arcane.gloss.panel.PanelFollowTransform;
import art.arcane.gloss.panel.PanelIds;
import art.arcane.gloss.doc.DocumentRevisionConflictException;
import art.arcane.gloss.panel.PanelRuntimeManager;
import art.arcane.gloss.panel.PanelService;
import art.arcane.gloss.panel.PanelTransform;
import art.arcane.gloss.panel.PanelVisibility;
import art.arcane.gloss.panel.PanelVisibilityMode;
import art.arcane.gloss.config.MenuDefinitionData;
import art.arcane.gloss.config.menu.MenuRowMutations;
import art.arcane.gloss.locale.GlossLocalization;
import art.arcane.gloss.locale.GlossMessages;
import art.arcane.gloss.util.common.Teleports;
import art.arcane.volmlib.util.collection.KList;
import art.arcane.volmlib.util.director.DirectorParameterHandler;
import art.arcane.volmlib.util.director.annotations.Director;
import art.arcane.volmlib.util.director.annotations.Param;
import art.arcane.volmlib.util.director.exceptions.DirectorParsingException;
import art.arcane.volmlib.util.director.help.DirectorMiniMenu;
import art.arcane.volmlib.util.localization.MessageArgs;
import art.arcane.volmlib.util.localization.TextKey;
import art.arcane.volmlib.util.bukkit.Events;
import art.arcane.volmlib.util.scheduling.SchedulerUtils;
import com.google.gson.JsonObject;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;
import org.bukkit.event.EventPriority;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.event.player.PlayerTeleportEvent;

import java.nio.file.FileAlreadyExistsException;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.NoSuchElementException;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CancellationException;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Consumer;
import java.util.function.UnaryOperator;

@Director(name = "panel", aliases = {"panels"}, description = "Persistent world panel tools", descriptionKey = "command.help.panel")
public final class CommandGlossPanel {
  public static final String PERMISSION = "gloss.panels";
  private static final String LIST_COMMAND = "/gloss panel list";
  private static final String NEAR_COMMAND = "/gloss panel near";

  private final Map<UUID, PanelEditSession> editSessions = new ConcurrentHashMap<>();

  private volatile Events quitListener;

  /**
   * Listener registration is deliberately not done here. The director engine is built through a
   * compare-and-set cache, so two callers racing the first command each construct a command tree;
   * only one wins the cache and the loser is dropped. A listener registered in this constructor
   * would outlive that dropped tree, feeding quit events into edit sessions nobody can reach. The
   * enable hook below runs once, on the tree the cache actually kept.
   */
  public CommandGlossPanel() {
  }

  public void enable() {
    Gloss plugin = Gloss.instance;
    if (plugin == null || quitListener != null) {
      return;
    }
    quitListener = Events.listen(plugin, PlayerQuitEvent.class, EventPriority.MONITOR,
        event -> discardEdit(event.getPlayer()));
  }

  public void shutdown() {
    Events listener = quitListener;
    quitListener = null;
    if (listener != null) {
      listener.unregister();
    }
    editSessions.clear();
  }

  @Director(name = "list", description = "List persistent panels by page", descriptionKey = "command.help.panel.list")
  public void list(
      @Param(name = "page", description = "One-based list page", descriptionKey = "command.help.arg.list_page", defaultValue = "1")
      int page,
      @Param(name = "sender", contextual = true)
      CommandSender sender
  ) {
    if (!checkPermission(sender)) {
      return;
    }
    List<PanelDefinition> boards = service().list();
    if (boards.isEmpty()) {
      send(sender, GlossMessages.PANELS_LIST_EMPTY);
      return;
    }
    DirectorMiniMenu.ContentPage window = GlossCommandPager.window(boards.size(), page, GlossCommandPager.TEXT_PAGE_SIZE);
    DirectorMiniMenu.Theme theme = GlossCommandService.menuTheme();
    List<String> lines = new ArrayList<>();
    GlossCommandPager.appendHeader(lines, LIST_COMMAND + " · " + boards.size(), window, theme);
    for (PanelDefinition board : boards.subList(window.startIndex(), window.endIndex())) {
      lines.add(GlossCommandPager.entry(
          board.id(),
          "menu=" + board.rootMenuId() + " revision=" + board.revision(),
          theme));
    }
    GlossCommandPager.appendFooter(lines, window, LIST_COMMAND, theme);
    DirectorMiniMenu.deliver(sender, lines);
  }

  @Director(name = "reload", description = "Reload persistent panel files", descriptionKey = "command.help.panel.reload")
  public void reload(
      @Param(name = "sender", contextual = true)
      CommandSender sender
  ) {
    if (!checkPermission(sender)) {
      return;
    }
    service().reload().whenComplete((result, failure) -> {
      if (failure != null) {
        reportFailure(sender, "reload", failure);
        return;
      }
      sendLater(sender, GlossMessages.PANELS_RELOADED,
          MessageArgs.builder()
              .untrusted("loaded", result.loaded())
              .untrusted("retained", result.retained())
              .untrusted("removed", result.removed())
              .untrusted("failures", result.failures().size())
              .build());
    });
  }

  @Director(name = "near", description = "List panels near your current position", descriptionKey = "command.help.panel.near")
  public void near(
      @Param(name = "radius", description = "Horizontal search radius", descriptionKey = "command.help.arg.panel_radius", defaultValue = "64")
      double radius,
      @Param(name = "page", description = "One-based list page", descriptionKey = "command.help.arg.list_page", defaultValue = "1")
      int page,
      @Param(name = "sender", contextual = true)
      CommandSender sender
  ) {
    if (!checkPermission(sender)) {
      return;
    }
    Player player = requirePlayer(sender);
    if (player == null) {
      return;
    }

    try {
      PanelCommandSupport.nonNegativeFinite(radius, "radius");
    } catch (IllegalArgumentException exception) {
      invalid(sender, exception);
      return;
    }

    Location location = player.getLocation();
    World world = location.getWorld();
    if (world == null) {
      send(sender, GlossMessages.PANELS_WORLD_UNAVAILABLE,
          MessageArgs.builder().untrusted("world", "unknown").build());
      return;
    }
    List<PanelDefinition> boards = runtime().queryEffective(
        world.getUID(), location.getX(), location.getZ(), radius);
    if (boards.isEmpty()) {
      send(sender, GlossMessages.PANELS_NEAR_EMPTY);
      return;
    }
    String command = NEAR_COMMAND + " radius=" + format(radius);
    DirectorMiniMenu.ContentPage window = GlossCommandPager.window(boards.size(), page, GlossCommandPager.TEXT_PAGE_SIZE);
    DirectorMiniMenu.Theme theme = GlossCommandService.menuTheme();
    List<String> lines = new ArrayList<>();
    GlossCommandPager.appendHeader(lines, command + " · " + boards.size(), window, theme);
    for (PanelDefinition board : boards.subList(window.startIndex(), window.endIndex())) {
      PanelTransform transform = board.transform();
      double distance = Math.hypot(transform.x() - location.getX(), transform.z() - location.getZ());
      lines.add(GlossCommandPager.entry(
          board.id(),
          "distance=" + format(distance) + " menu=" + board.rootMenuId(),
          theme));
    }
    GlossCommandPager.appendFooter(lines, window, command, theme);
    DirectorMiniMenu.deliver(sender, lines);
  }

  @Director(name = "info", description = "Show one panel's complete state", descriptionKey = "command.help.panel.info")
  public void info(
      @Param(name = "panel", description = "Persistent panel id", descriptionKey = "command.help.arg.panel_id", customHandler = PanelIdHandler.class)
      String id,
      @Param(name = "sender", contextual = true)
      CommandSender sender
  ) {
    if (!checkPermission(sender)) {
      return;
    }
    CommandBoardState state = commandState(sender, id);
    if (state == null) {
      return;
    }
    sendInfo(sender, state.definition(), state.effectiveTransform(), state.edit() != null);
  }

  @Director(name = "create", description = "Create a panel at your current position", descriptionKey = "command.help.panel.create")
  public void create(
      @Param(name = "panel", description = "New persistent panel id", descriptionKey = "command.help.arg.new_panel_id")
      String id,
      @Param(name = "menu", description = "Root menu id; defaults to the panel id", descriptionKey = "command.help.arg.panel_menu", defaultValue = "*", customHandler = CommandGlossMenu.ExistingMenuHandler.class)
      String menuId,
      @Param(name = "sender", contextual = true)
      CommandSender sender
  ) {
    if (!checkPermission(sender)) {
      return;
    }
    Player player = requirePlayer(sender);
    if (player == null) {
      return;
    }
    try {
      Location location = player.getLocation().clone();
      World world = location.getWorld();
      if (world == null) {
        send(sender, GlossMessages.PANELS_WORLD_UNAVAILABLE,
            MessageArgs.builder().untrusted("world", "unknown").build());
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
      createAt(sender, id, menuId, transform);
    } catch (IllegalArgumentException exception) {
      invalid(sender, exception);
    }
  }

  @Director(name = "delete", aliases = {"remove"}, description = "Delete a persistent panel", descriptionKey = "command.help.panel.delete")
  public void delete(
      @Param(name = "panel", description = "Persistent panel id", descriptionKey = "command.help.arg.panel_id", customHandler = PanelIdHandler.class)
      String id,
      @Param(name = "sender", contextual = true)
      CommandSender sender
  ) {
    if (!checkPermission(sender)) {
      return;
    }
    PanelDefinition board = publishedBoard(sender, id);
    if (board == null || rejectIdentityMutation(sender, board.id())) {
      return;
    }
    service().delete(board.id(), board.revision()).whenComplete((deleted, failure) -> {
      if (failure != null) {
        reportFailure(sender, board.id(), failure);
        return;
      }
      sendLater(sender, GlossMessages.PANELS_DELETED,
          MessageArgs.builder().untrusted("board", deleted.id()).build());
    });
  }

  @Director(name = "rename", description = "Rename a persistent panel", descriptionKey = "command.help.panel.rename")
  public void rename(
      @Param(name = "panel", description = "Persistent panel id", descriptionKey = "command.help.arg.panel_id", customHandler = PanelIdHandler.class)
      String id,
      @Param(name = "newPanel", description = "New persistent panel id", descriptionKey = "command.help.arg.panel_new_id")
      String newId,
      @Param(name = "sender", contextual = true)
      CommandSender sender
  ) {
    if (!checkPermission(sender)) {
      return;
    }
    PanelDefinition board = publishedBoard(sender, id);
    if (board == null || rejectIdentityMutation(sender, board.id())) {
      return;
    }
    try {
      String canonicalNewId = PanelIds.canonicalize(newId);
      service().rename(board.id(), canonicalNewId, board.revision()).whenComplete((renamed, failure) -> {
        if (failure != null) {
          reportFailure(sender, board.id(), failure);
          return;
        }
        sendLater(sender, GlossMessages.PANELS_RENAMED,
            MessageArgs.builder()
                .untrusted("oldBoard", board.id())
                .untrusted("board", renamed.id())
                .untrusted("revision", renamed.revision())
                .build());
      });
    } catch (IllegalArgumentException exception) {
      invalid(sender, exception);
    }
  }

  @Director(name = "copy", description = "Copy a persistent panel under a new id", descriptionKey = "command.help.panel.copy")
  public void copy(
      @Param(name = "panel", description = "Persistent panel id", descriptionKey = "command.help.arg.panel_id", customHandler = PanelIdHandler.class)
      String id,
      @Param(name = "newPanel", description = "New persistent panel id", descriptionKey = "command.help.arg.panel_new_id")
      String newId,
      @Param(name = "sender", contextual = true)
      CommandSender sender
  ) {
    if (!checkPermission(sender)) {
      return;
    }
    PanelDefinition board = visibleBoard(sender, id);
    if (board == null) {
      return;
    }
    try {
      PanelDefinition copy = PanelCommandSupport.copy(board, newId);
      service().create(copy).whenComplete((created, failure) -> {
        if (failure != null) {
          reportFailure(sender, copy.id(), failure);
          return;
        }
        sendLater(sender, GlossMessages.PANELS_COPIED,
            MessageArgs.builder()
                .untrusted("source", board.id())
                .untrusted("board", created.id())
                .build());
      });
    } catch (IllegalArgumentException exception) {
      invalid(sender, exception);
    }
  }

  @Director(name = "move", description = "Move a panel to absolute or ~relative coordinates", descriptionKey = "command.help.panel.move")
  public void move(
      @Param(name = "panel", description = "Persistent panel id", descriptionKey = "command.help.arg.panel_id", customHandler = PanelIdHandler.class)
      String id,
      @Param(name = "x", description = "Absolute or ~relative X", descriptionKey = "command.help.arg.panel_x")
      String x,
      @Param(name = "y", description = "Absolute or ~relative Y", descriptionKey = "command.help.arg.panel_y")
      String y,
      @Param(name = "z", description = "Absolute or ~relative Z", descriptionKey = "command.help.arg.panel_z")
      String z,
      @Param(name = "sender", contextual = true)
      CommandSender sender
  ) {
    if (!checkPermission(sender)) {
      return;
    }
    mutateEffectiveTransform(sender, id, "move",
        transform -> PanelCommandSupport.move(transform, x, y, z));
  }

  @Director(name = "here", aliases = {"movehere", "tphere"}, description = "Move a panel to your current position", descriptionKey = "command.help.panel.here")
  public void moveHere(
      @Param(name = "panel", description = "Persistent panel id", descriptionKey = "command.help.arg.panel_id", customHandler = PanelIdHandler.class)
      String id,
      @Param(name = "sender", contextual = true)
      CommandSender sender
  ) {
    if (!checkPermission(sender)) {
      return;
    }
    Player player = requirePlayer(sender);
    if (player == null) {
      return;
    }
    Location location = player.getLocation().clone();
    World world = location.getWorld();
    if (world == null) {
      send(sender, GlossMessages.PANELS_WORLD_UNAVAILABLE,
          MessageArgs.builder().untrusted("world", "unknown").build());
      return;
    }
    mutateEffectiveTransform(sender, id, "movehere",
        transform -> PanelCommandSupport.moveTo(
            transform,
            world.getKey().toString(),
            world.getUID(),
            location.getX(),
            location.getY(),
            location.getZ()
        ));
  }

  @Director(name = "teleport", aliases = {"tp"}, description = "Teleport to a persistent panel", descriptionKey = "command.help.panel.teleport")
  public void teleport(
      @Param(name = "panel", description = "Persistent panel id", descriptionKey = "command.help.arg.panel_id", customHandler = PanelIdHandler.class)
      String id,
      @Param(name = "sender", contextual = true)
      CommandSender sender
  ) {
    if (!checkPermission(sender)) {
      return;
    }
    Player player = requirePlayer(sender);
    if (player == null) {
      return;
    }
    CommandBoardState state = commandState(sender, id);
    if (state == null) {
      return;
    }
    PanelDefinition board = state.definition();
    PanelTransform transform = state.effectiveTransform();
    World world = Bukkit.getWorld(transform.worldUuid());
    if (world == null || !world.getKey().toString().equals(transform.worldKey())) {
      send(sender, GlossMessages.PANELS_WORLD_UNAVAILABLE,
          MessageArgs.builder().untrusted("world", transform.worldKey()).build());
      return;
    }

    Location destination = new Location(world, transform.x(), transform.y(), transform.z(),
        (float) transform.yaw(), (float) transform.pitch());
    Teleports.teleportAsync(player, destination, PlayerTeleportEvent.TeleportCause.PLUGIN)
        .whenComplete((success, failure) -> {
          if (failure != null || !Boolean.TRUE.equals(success)) {
            Throwable cause = failure == null ? new IllegalStateException("teleport was rejected") : failure;
            reportFailure(sender, board.id(), cause);
            return;
          }
          sendLater(sender, GlossMessages.PANELS_TELEPORTED,
              MessageArgs.builder().untrusted("board", board.id()).build());
        });
  }

  @Director(name = "rotate", description = "Set absolute or ~relative yaw, pitch, and roll", descriptionKey = "command.help.panel.rotate")
  public void rotate(
      @Param(name = "panel", description = "Persistent panel id", descriptionKey = "command.help.arg.panel_id", customHandler = PanelIdHandler.class)
      String id,
      @Param(name = "yaw", description = "Absolute or ~relative yaw", descriptionKey = "command.help.arg.panel_yaw")
      String yaw,
      @Param(name = "pitch", description = "Absolute or ~relative pitch", descriptionKey = "command.help.arg.panel_pitch")
      String pitch,
      @Param(name = "roll", description = "Absolute or ~relative roll", descriptionKey = "command.help.arg.panel_roll")
      String roll,
      @Param(name = "sender", contextual = true)
      CommandSender sender
  ) {
    if (!checkPermission(sender)) {
      return;
    }
    mutateEffectiveTransform(sender, id, "rotate",
        transform -> PanelCommandSupport.rotate(transform, yaw, pitch, roll));
  }

  @Director(name = "scale", description = "Set an absolute or ~relative panel scale", descriptionKey = "command.help.panel.scale")
  public void scale(
      @Param(name = "panel", description = "Persistent panel id", descriptionKey = "command.help.arg.panel_id", customHandler = PanelIdHandler.class)
      String id,
      @Param(name = "scale", description = "Absolute or ~relative scale", descriptionKey = "command.help.arg.panel_scale")
      String scale,
      @Param(name = "sender", contextual = true)
      CommandSender sender
  ) {
    if (!checkPermission(sender)) {
      return;
    }
    mutateScale(sender, id, scale);
  }

  @Director(name = "align", description = "Align panel position axes to another panel", descriptionKey = "command.help.panel.align")
  public void align(
      @Param(name = "panel", description = "Persistent panel id", descriptionKey = "command.help.arg.panel_id", customHandler = PanelIdHandler.class)
      String id,
      @Param(name = "reference", description = "Reference panel id", descriptionKey = "command.help.arg.panel_reference", customHandler = PanelIdHandler.class)
      String referenceId,
      @Param(name = "axes", description = "Axes: x, y, z, xy, xz, yz, or xyz", descriptionKey = "command.help.arg.panel_axes", customHandler = AxesHandler.class)
      String axes,
      @Param(name = "sender", contextual = true)
      CommandSender sender
  ) {
    if (!checkPermission(sender)) {
      return;
    }
    CommandBoardState reference = commandState(sender, referenceId);
    if (reference == null) {
      return;
    }
    mutateEffectiveTransform(sender, id, "align",
        transform -> PanelCommandSupport.align(transform, reference.effectiveTransform(), axes));
  }

  @Director(name = "menu", aliases = {"root"}, description = "Set a panel's root menu", descriptionKey = "command.help.panel.menu")
  public void menu(
      @Param(name = "panel", description = "Persistent panel id", descriptionKey = "command.help.arg.panel_id", customHandler = PanelIdHandler.class)
      String id,
      @Param(name = "menu", description = "Root menu id", descriptionKey = "command.help.arg.panel_root_menu", customHandler = CommandGlossMenu.ExistingMenuHandler.class)
      String menuId,
      @Param(name = "sender", contextual = true)
      CommandSender sender
  ) {
    if (!checkPermission(sender)) {
      return;
    }
    String rootMenu = resolveMenu(sender, menuId);
    if (rootMenu == null) {
      return;
    }
    mutate(sender, id, "menu", board -> board.withRootMenu(rootMenu));
  }

  @Director(name = "addrow", description = "Append a text row to a panel's root menu", descriptionKey = "command.help.panel.addrow")
  public void addRow(
      @Param(name = "panel", description = "Persistent panel id", descriptionKey = "command.help.arg.panel_id", customHandler = PanelIdHandler.class)
      String id,
      @Param(name = "text", description = "MiniMessage row text", descriptionKey = "command.help.arg.row_text")
      String text,
      @Param(name = "sender", contextual = true)
      CommandSender sender
  ) {
    mutateRootMenu(sender, id, "addrow", document -> MenuRowMutations.addTextRow(document, text));
  }

  @Director(name = "insertrow", description = "Insert a text row into a panel's root menu", descriptionKey = "command.help.panel.insertrow")
  public void insertRow(
      @Param(name = "panel", description = "Persistent panel id", descriptionKey = "command.help.arg.panel_id", customHandler = PanelIdHandler.class)
      String id,
      @Param(name = "row", description = "One-based component row", descriptionKey = "command.help.arg.row_index")
      int row,
      @Param(name = "text", description = "MiniMessage row text", descriptionKey = "command.help.arg.row_text")
      String text,
      @Param(name = "sender", contextual = true)
      CommandSender sender
  ) {
    mutateRootMenu(sender, id, "insertrow",
        document -> MenuRowMutations.insertTextRow(document, row, text));
  }

  @Director(name = "setrow", description = "Set text on a panel root-menu row", descriptionKey = "command.help.panel.setrow")
  public void setRow(
      @Param(name = "panel", description = "Persistent panel id", descriptionKey = "command.help.arg.panel_id", customHandler = PanelIdHandler.class)
      String id,
      @Param(name = "row", description = "One-based component row", descriptionKey = "command.help.arg.row_index")
      int row,
      @Param(name = "text", description = "MiniMessage row text", descriptionKey = "command.help.arg.row_text")
      String text,
      @Param(name = "sender", contextual = true)
      CommandSender sender
  ) {
    mutateRootMenu(sender, id, "setrow",
        document -> MenuRowMutations.setTextRow(document, row, text));
  }

  @Director(name = "removerow", description = "Remove a panel root-menu row", descriptionKey = "command.help.panel.removerow")
  public void removeRow(
      @Param(name = "panel", description = "Persistent panel id", descriptionKey = "command.help.arg.panel_id", customHandler = PanelIdHandler.class)
      String id,
      @Param(name = "row", description = "One-based component row", descriptionKey = "command.help.arg.row_index")
      int row,
      @Param(name = "sender", contextual = true)
      CommandSender sender
  ) {
    mutateRootMenu(sender, id, "removerow", document -> MenuRowMutations.removeRow(document, row));
  }

  @Director(name = "offsetrow", description = "Move a panel root-menu row with absolute or ~relative offsets", descriptionKey = "command.help.panel.offsetrow")
  public void offsetRow(
      @Param(name = "panel", description = "Persistent panel id", descriptionKey = "command.help.arg.panel_id", customHandler = PanelIdHandler.class)
      String id,
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
    mutateRootMenu(sender, id, "offsetrow",
        document -> MenuRowMutations.offsetRow(document, row, x, y, z));
  }

  @Director(name = "seticon", description = "Replace a row icon in the panel root menu", descriptionKey = "command.help.panel.seticon")
  public void setIcon(
      @Param(name = "panel", description = "Persistent panel id", descriptionKey = "command.help.arg.panel_id", customHandler = PanelIdHandler.class)
      String id,
      @Param(name = "row", description = "One-based component row", descriptionKey = "command.help.arg.row_index")
      int row,
      @Param(name = "type", description = "Icon type", descriptionKey = "command.help.arg.row_icon_type", customHandler = MenuRowCommandSupport.IconTypeHandler.class)
      String type,
      @Param(name = "value", description = "Icon content or registry id", descriptionKey = "command.help.arg.row_icon_value")
      String value,
      @Param(name = "sender", contextual = true)
      CommandSender sender
  ) {
    mutateRootMenu(sender, id, "seticon",
        document -> MenuRowCommandSupport.setIconMutation(document, row, type, value));
  }

  @Director(name = "style", description = "Set or clear one row display-style property", descriptionKey = "command.help.panel.style")
  public void style(
      @Param(name = "panel", description = "Persistent panel id", descriptionKey = "command.help.arg.panel_id", customHandler = PanelIdHandler.class)
      String id,
      @Param(name = "row", description = "One-based component row", descriptionKey = "command.help.arg.row_index")
      int row,
      @Param(name = "property", description = "Display-style property", descriptionKey = "command.help.arg.row_style_property", customHandler = MenuRowCommandSupport.StylePropertyHandler.class)
      String property,
      @Param(name = "value", description = "Style value, or * to clear", descriptionKey = "command.help.arg.row_style_value")
      String value,
      @Param(name = "sender", contextual = true)
      CommandSender sender
  ) {
    mutateRootMenu(sender, id, "style",
        document -> MenuRowMutations.setStyle(document, row, property, value));
  }

  @Director(name = "image", description = "Replace the panel root menu with one centered image file", descriptionKey = "command.help.panel.image")
  public void image(
      @Param(name = "panel", description = "Persistent panel id", descriptionKey = "command.help.arg.panel_id", customHandler = PanelIdHandler.class)
      String id,
      @Param(name = "path", description = "File beneath the Gloss images folder", descriptionKey = "command.help.arg.image_path")
      String path,
      @Param(name = "sender", contextual = true)
      CommandSender sender
  ) {
    mutateRootMenu(sender, id, "image",
        document -> MenuRowCommandSupport.replaceWithImageMutation(document, path));
  }

  @Director(name = "ranges", description = "Set panel view and interaction ranges", descriptionKey = "command.help.panel.ranges")
  public void ranges(
      @Param(name = "panel", description = "Persistent panel id", descriptionKey = "command.help.arg.panel_id", customHandler = PanelIdHandler.class)
      String id,
      @Param(name = "viewRange", description = "Positive view range", descriptionKey = "command.help.arg.panel_view_range")
      double viewRange,
      @Param(name = "interactionRange", description = "Positive interaction range", descriptionKey = "command.help.arg.panel_interaction_range")
      double interactionRange,
      @Param(name = "sender", contextual = true)
      CommandSender sender
  ) {
    if (!checkPermission(sender)) {
      return;
    }
    mutate(sender, id, "ranges", board -> board.withVisibility(
        board.visibility().withRanges(viewRange, interactionRange)));
  }

  @Director(name = "visibility", description = "Set visibility mode and permission nodes", descriptionKey = "command.help.panel.visibility")
  public void visibility(
      @Param(name = "panel", description = "Persistent panel id", descriptionKey = "command.help.arg.panel_id", customHandler = PanelIdHandler.class)
      String id,
      @Param(name = "mode", description = "Visibility mode: public, permission, or hidden", descriptionKey = "command.help.arg.panel_visibility")
      PanelVisibilityMode mode,
      @Param(name = "viewPermission", description = "View permission, or -", descriptionKey = "command.help.arg.panel_view_permission")
      String viewPermission,
      @Param(name = "interactPermission", description = "Interaction permission, or -", descriptionKey = "command.help.arg.panel_interact_permission")
      String interactPermission,
      @Param(name = "sender", contextual = true)
      CommandSender sender
  ) {
    if (!checkPermission(sender)) {
      return;
    }
    mutate(sender, id, "visibility", board -> board.withVisibility(
        PanelCommandSupport.visibility(board.visibility(), mode, viewPermission, interactPermission)));
  }

  @Director(name = "permissions", description = "Change panel view and interaction permissions", descriptionKey = "command.help.panel.permissions")
  public void permissions(
      @Param(name = "panel", description = "Persistent panel id", descriptionKey = "command.help.arg.panel_id", customHandler = PanelIdHandler.class)
      String id,
      @Param(name = "viewPermission", description = "View permission, or -", descriptionKey = "command.help.arg.panel_view_permission")
      String viewPermission,
      @Param(name = "interactPermission", description = "Interaction permission, or -", descriptionKey = "command.help.arg.panel_interact_permission")
      String interactPermission,
      @Param(name = "sender", contextual = true)
      CommandSender sender
  ) {
    if (!checkPermission(sender)) {
      return;
    }
    mutate(sender, id, "permissions", board -> board.withVisibility(
        PanelCommandSupport.permissions(board.visibility(), viewPermission, interactPermission)));
  }

  @Director(name = "follow", description = "Follow an online player with fixed, yaw, or full rotation", descriptionKey = "command.help.panel.follow")
  public void follow(
      @Param(name = "panel", description = "Persistent panel id", descriptionKey = "command.help.arg.panel_id", customHandler = PanelIdHandler.class)
      String id,
      @Param(name = "player", description = "Online target player", descriptionKey = "command.help.arg.panel_player", customHandler = OnlinePlayerHandler.class)
      String playerName,
      @Param(name = "rotation", description = "Follow rotation: fixed, yaw, or full", descriptionKey = "command.help.arg.panel_follow_rotation")
      PanelFollowRotation rotation,
      @Param(name = "sender", contextual = true)
      CommandSender sender
  ) {
    if (!checkPermission(sender)) {
      return;
    }
    Player target = onlinePlayer(playerName);
    if (target == null) {
      send(sender, GlossMessages.PANELS_PLAYER_UNAVAILABLE,
          MessageArgs.builder().untrusted("player", playerName).build());
      return;
    }
    CommandBoardState state = commandState(sender, id);
    if (state == null) {
      return;
    }
    captureLocation(sender, target, location -> {
      try {
        PanelDefinition changed = PanelCommandSupport.follow(
            state.definition(),
            state.effectiveTransform(),
            location,
            target.getUniqueId(),
            rotation
        );
        applyMutation(sender, state, "follow", changed, state.effectiveTransform());
      } catch (IllegalArgumentException exception) {
        invalid(sender, exception);
      }
    });
  }

  @Director(name = "unfollow", description = "Stop a panel following a player", descriptionKey = "command.help.panel.unfollow")
  public void unfollow(
      @Param(name = "panel", description = "Persistent panel id", descriptionKey = "command.help.arg.panel_id", customHandler = PanelIdHandler.class)
      String id,
      @Param(name = "sender", contextual = true)
      CommandSender sender
  ) {
    if (!checkPermission(sender)) {
      return;
    }
    CommandBoardState state = commandState(sender, id);
    if (state == null) {
      return;
    }
    PanelDefinition changed = PanelCommandSupport.unfollow(
        state.definition(), state.effectiveTransform());
    applyMutation(sender, state, "unfollow", changed, state.effectiveTransform());
  }

  @Director(name = "edit", description = "Start a staged panel edit session", descriptionKey = "command.help.panel.edit")
  public void edit(
      @Param(name = "panel", description = "Persistent panel id", descriptionKey = "command.help.arg.panel_id", customHandler = PanelIdHandler.class)
      String id,
      @Param(name = "sender", contextual = true)
      CommandSender sender
  ) {
    if (!checkPermission(sender)) {
      return;
    }
    Player player = requirePlayer(sender);
    if (player == null) {
      return;
    }
    PanelDefinition board = publishedBoard(sender, id);
    if (board == null) {
      return;
    }
    PanelTransform effectiveTransform = effectiveTransform(sender, board);
    if (effectiveTransform == null) {
      return;
    }
    PanelEditSession session = new PanelEditSession(board, effectiveTransform);
    PanelEditSession previous = editSessions.putIfAbsent(player.getUniqueId(), session);
    if (previous != null) {
      send(sender, GlossMessages.PANELS_EDIT_ALREADY_ACTIVE,
          MessageArgs.builder().untrusted("board", previous.id()).build());
      return;
    }
    runtime().previewBoard(player, board, effectiveTransform);
    send(sender, GlossMessages.PANELS_EDIT_STARTED,
        MessageArgs.builder()
            .untrusted("board", board.id())
            .untrusted("revision", board.revision())
            .build());
    sendInfo(sender, board, effectiveTransform, true);
  }

  @Director(name = "web", aliases = {"editweb", "webedit"},
      description = "Open a panel project in the web editor",
      descriptionKey = "command.help.panel.web")
  public void editWeb(
      @Param(name = "panel", description = "Persistent panel id",
          descriptionKey = "command.help.arg.panel_id", customHandler = PanelIdHandler.class)
      String id,
      @Param(name = "sender", contextual = true) CommandSender sender
  ) {
    String permission = "gloss.panels.editweb";
    if (!sender.hasPermission(permission)) {
      send(sender, GlossMessages.PERMISSION_DENIED,
          MessageArgs.builder().untrusted("permission", permission).build());
      return;
    }
    PanelDefinition board = publishedBoard(sender, id);
    if (board == null) {
      return;
    }
    String source = plugin().getMenuCatalog().source(board.rootMenuId()).orElse(null);
    if (source == null) {
      send(sender, GlossMessages.PANELS_MENU_UNAVAILABLE,
          MessageArgs.builder().untrusted("menu", board.rootMenuId()).build());
      return;
    }
    if (!plugin().cfg().editorSync().enabled()
        || plugin().getEditorSyncService() == null
        || !plugin().getEditorSyncService().isAvailable()
        || !sender.hasPermission(CommandGlossSync.PERMISSION)) {
      CommandGlossMenu.deliverLegacyEditorLink(plugin(), sender, board.rootMenuId(), source, true);
      return;
    }
    send(sender, GlossMessages.SYNC_PREPARING,
        MessageArgs.builder().untrusted("subject", board.id()).build());
    plugin().getEditorSyncService().openBoard(board.id()).whenComplete((result, failure) ->
        runForSender(sender, () -> {
          if (failure == null) {
            CommandGlossMenu.deliverSyncLink(plugin(), sender, result);
            return;
          }
          Gloss.logExceptionStack(false, PanelCommandSupport.rootCause(failure),
              "Unable to create editor sync session for panel \"%s\"; using its root-menu handoff.",
              board.id());
          CommandGlossMenu.deliverLegacyEditorLink(plugin(), sender, board.rootMenuId(), source, true);
        }));
  }

  @Director(name = "save", description = "Persist your staged panel edit", descriptionKey = "command.help.panel.save")
  public void save(
      @Param(name = "sender", contextual = true)
      CommandSender sender
  ) {
    if (!checkPermission(sender)) {
      return;
    }
    Player player = requirePlayer(sender);
    if (player == null) {
      return;
    }
    UUID playerId = player.getUniqueId();
    PanelEditSession session = editSessions.get(playerId);
    if (session == null) {
      send(sender, GlossMessages.PANELS_EDIT_NONE);
      return;
    }
    PanelDefinition staged = session.beginSave();
    if (staged == null) {
      send(sender, GlossMessages.PANELS_EDIT_BUSY,
          MessageArgs.builder().untrusted("board", session.id()).build());
      return;
    }

    service().update(session.id(), session.expectedRevision(), ignored -> staged)
        .whenComplete((saved, failure) -> {
          if (failure != null) {
            session.retrySave();
            reportFailure(sender, session.id(), failure);
            return;
          }
          editSessions.remove(playerId, session);
          runForSender(sender, () -> {
            runtime().clearBoardPreview(player, saved.uuid());
            send(sender, GlossMessages.PANELS_EDIT_SAVED,
                MessageArgs.builder()
                    .untrusted("board", saved.id())
                    .untrusted("revision", saved.revision())
                    .build());
          });
        });
  }

  @Director(name = "cancel", description = "Discard your staged panel edit", descriptionKey = "command.help.panel.cancel")
  public void cancel(
      @Param(name = "sender", contextual = true)
      CommandSender sender
  ) {
    if (!checkPermission(sender)) {
      return;
    }
    Player player = requirePlayer(sender);
    if (player == null) {
      return;
    }
    PanelEditSession active = editSessions.get(player.getUniqueId());
    if (active != null && active.cancel() == null) {
      send(sender, GlossMessages.PANELS_EDIT_BUSY,
          MessageArgs.builder().untrusted("board", active.id()).build());
      return;
    }
    PanelEditSession removed = editSessions.remove(player.getUniqueId());
    if (removed == null) {
      send(sender, GlossMessages.PANELS_EDIT_NONE);
      return;
    }
    runtime().clearBoardPreview(player, removed.snapshot().definition().uuid());
    send(sender, GlossMessages.PANELS_EDIT_CANCELLED,
        MessageArgs.builder().untrusted("board", removed.id()).build());
  }

  private void createAt(CommandSender sender, String id, String menuId, PanelTransform transform) {
    String requestedMenu = menuId.equals("*") ? id : menuId;
    String rootMenu = resolveMenu(sender, requestedMenu);
    if (rootMenu == null) {
      return;
    }
    PanelDefinition definition = PanelDefinition.create(id, rootMenu, transform);
    service().create(definition).whenComplete((created, failure) -> {
      if (failure != null) {
        reportFailure(sender, definition.id(), failure);
        return;
      }
      sendLater(sender, GlossMessages.PANELS_CREATED,
          MessageArgs.builder()
              .untrusted("board", created.id())
              .untrusted("revision", created.revision())
              .build());
    });
  }

  private void mutate(CommandSender sender, String id, String operation,
                      UnaryOperator<PanelDefinition> update) {
    PanelDefinition published = publishedBoard(sender, id);
    if (published == null) {
      return;
    }
    PanelEditSession edit = matchingEdit(sender, published.id());
    if (edit != null) {
      try {
        PanelEditSession.Snapshot snapshot = edit.snapshot();
        PanelDefinition changed = update.apply(snapshot.definition());
        applyMutation(
            sender,
            new CommandBoardState(
                snapshot.definition(), snapshot.effectiveTransform(), edit, snapshot),
            operation,
            changed,
            snapshot.effectiveTransform()
        );
      } catch (IllegalArgumentException | IllegalStateException exception) {
        invalid(sender, exception);
      }
      return;
    }

    PanelDefinition candidate;
    try {
      candidate = update.apply(published);
    } catch (IllegalArgumentException exception) {
      invalid(sender, exception);
      return;
    }
    persistMutation(sender, published, operation, candidate);
  }

  private void mutateRootMenu(CommandSender sender, String id, String operation,
                              UnaryOperator<JsonObject> mutation) {
    if (!checkPermission(sender)) {
      return;
    }
    PanelDefinition board = visibleBoard(sender, id);
    if (board == null) {
      return;
    }
    MenuRowCommandSupport.mutate(sender, board.rootMenuId(), operation, mutation, PERMISSION);
  }

  private void mutateScale(CommandSender sender, String id, String scale) {
    PanelDefinition published = publishedBoard(sender, id);
    if (published == null) {
      return;
    }
    PanelEditSession edit = matchingEdit(sender, published.id());
    if (edit == null) {
      mutate(sender, id, "scale", board -> board.withTransform(
          PanelCommandSupport.scale(board.transform(), scale)));
      return;
    }

    try {
      PanelEditSession.Snapshot snapshot = edit.snapshot();
      PanelDefinition changed = snapshot.definition().withTransform(
          PanelCommandSupport.scale(snapshot.definition().transform(), scale));
      PanelTransform changedEffective = PanelCommandSupport.scale(
          snapshot.effectiveTransform(), scale);
      applyMutation(
          sender,
          new CommandBoardState(
              snapshot.definition(), snapshot.effectiveTransform(), edit, snapshot),
          "scale",
          changed,
          changedEffective
      );
    } catch (IllegalArgumentException | IllegalStateException exception) {
      invalid(sender, exception);
    }
  }

  private void mutateEffectiveTransform(CommandSender sender, String id, String operation,
                                        UnaryOperator<PanelTransform> update) {
    CommandBoardState state = commandState(sender, id);
    if (state == null) {
      return;
    }

    PanelTransform changedEffective;
    try {
      changedEffective = update.apply(state.effectiveTransform());
    } catch (IllegalArgumentException exception) {
      invalid(sender, exception);
      return;
    }

    if (state.definition().follow().mode() == PanelFollowMode.NONE) {
      PanelDefinition changed = state.definition().withTransform(changedEffective);
      applyMutation(sender, state, operation, changed, changedEffective);
      return;
    }

    Player target = followTarget(sender, state.definition());
    if (target == null) {
      return;
    }
    captureLocation(sender, target, location -> {
      try {
        PanelDefinition changed = PanelCommandSupport.reencodeEffectiveTransform(
            state.definition(), changedEffective, location);
        applyMutation(sender, state, operation, changed, changedEffective);
      } catch (IllegalArgumentException exception) {
        invalid(sender, exception);
      }
    });
  }

  private void applyMutation(CommandSender sender, CommandBoardState state, String operation,
                             PanelDefinition changed, PanelTransform changedEffective) {
    PanelEditSession edit = state.edit();
    if (edit == null) {
      persistMutation(sender, state.definition(), operation, changed);
      return;
    }

    try {
      PanelEditSession.Snapshot staged = edit.stage(
          state.editSnapshot(), changed, changedEffective);
      if (staged == null) {
        send(sender, GlossMessages.PANELS_EDIT_BUSY,
            MessageArgs.builder().untrusted("board", edit.id()).build());
        return;
      }
      if (sender instanceof Player player) {
        runtime().previewBoard(player, staged.definition(), staged.effectiveTransform());
      }
      send(sender, GlossMessages.PANELS_EDIT_STAGED,
          MessageArgs.builder()
              .untrusted("operation", operation)
              .untrusted("board", staged.definition().id())
              .build());
    } catch (IllegalArgumentException | IllegalStateException exception) {
      invalid(sender, exception);
    }
  }

  private void persistMutation(CommandSender sender, PanelDefinition expected, String operation,
                               PanelDefinition changed) {
    service().update(expected.id(), expected.revision(), ignored -> changed)
        .whenComplete((updated, failure) -> {
          if (failure != null) {
            reportFailure(sender, expected.id(), failure);
            return;
          }
          sendLater(sender, GlossMessages.PANELS_UPDATED,
              MessageArgs.builder()
                  .untrusted("operation", operation)
                  .untrusted("board", updated.id())
                  .untrusted("revision", updated.revision())
                  .build());
        });
  }

  private CommandBoardState commandState(CommandSender sender, String id) {
    PanelDefinition published = publishedBoard(sender, id);
    if (published == null) {
      return null;
    }
    PanelEditSession edit = matchingEdit(sender, published.id());
    if (edit != null) {
      PanelEditSession.Snapshot snapshot = edit.snapshot();
      if (snapshot.definition().follow().mode() == PanelFollowMode.PLAYER) {
        Optional<PanelFollowPose> pose = runtime().followPose(
            snapshot.definition().follow().targetPlayerUuid());
        if (pose.isEmpty()) {
          send(sender, GlossMessages.PANELS_PLAYER_UNAVAILABLE,
              MessageArgs.builder()
                  .untrusted("player", snapshot.definition().follow().targetPlayerUuid())
                  .build());
          return null;
        }
        snapshot = edit.synchronizeEffective(PanelFollowTransform.resolve(
            snapshot.definition(), pose.get()));
      }
      return new CommandBoardState(
          snapshot.definition(), snapshot.effectiveTransform(), edit, snapshot);
    }
    PanelTransform effectiveTransform = effectiveTransform(sender, published);
    return effectiveTransform == null
        ? null
        : new CommandBoardState(published, effectiveTransform, null, null);
  }

  private PanelTransform effectiveTransform(CommandSender sender, PanelDefinition board) {
    if (board.follow().mode() == PanelFollowMode.NONE) {
      return board.transform();
    }
    Optional<PanelDefinition> effective = runtime().effectiveBoard(board.uuid());
    if (effective.isPresent()) {
      return effective.get().transform();
    }
    send(sender, GlossMessages.PANELS_PLAYER_UNAVAILABLE,
        MessageArgs.builder().untrusted("player", board.follow().targetPlayerUuid()).build());
    return null;
  }

  private PanelDefinition visibleBoard(CommandSender sender, String id) {
    PanelDefinition published = publishedBoard(sender, id);
    if (published == null) {
      return null;
    }
    PanelEditSession edit = matchingEdit(sender, published.id());
    return edit == null ? published : edit.snapshot().definition();
  }

  private PanelDefinition publishedBoard(CommandSender sender, String id) {
    try {
      String canonicalId = PanelIds.canonicalize(id);
      PanelDefinition board = service().get(canonicalId).orElse(null);
      if (board == null) {
        send(sender, GlossMessages.PANELS_UNKNOWN,
            MessageArgs.builder().untrusted("board", canonicalId).build());
      }
      return board;
    } catch (IllegalArgumentException exception) {
      invalid(sender, exception);
      return null;
    }
  }

  private void sendInfo(CommandSender sender, PanelDefinition board, PanelTransform effectiveTransform,
                        boolean staged) {
    PanelTransform transform = effectiveTransform;
    PanelVisibility visibility = board.visibility();
    PanelFollow follow = board.follow();
    send(sender, GlossMessages.PANELS_INFO_HEADER,
        MessageArgs.builder()
            .untrusted("board", board.id())
            .untrusted("state", staged ? "staged" : "published")
            .build());
    send(sender, GlossMessages.PANELS_INFO_IDENTITY,
        MessageArgs.builder()
            .untrusted("uuid", board.uuid())
            .untrusted("revision", board.revision())
            .untrusted("menu", board.rootMenuId())
            .build());
    send(sender, GlossMessages.PANELS_INFO_TRANSFORM,
        MessageArgs.builder()
            .untrusted("world", transform.worldKey())
            .untrusted("x", format(transform.x()))
            .untrusted("y", format(transform.y()))
            .untrusted("z", format(transform.z()))
            .untrusted("yaw", format(transform.yaw()))
            .untrusted("pitch", format(transform.pitch()))
            .untrusted("roll", format(transform.roll()))
            .untrusted("scale", format(transform.scale()))
            .build());
    send(sender, GlossMessages.PANELS_INFO_VISIBILITY,
        MessageArgs.builder()
            .untrusted("mode", visibility.mode().name().toLowerCase(Locale.ROOT))
            .untrusted("viewPermission", fallback(visibility.viewPermission()))
            .untrusted("interactPermission", fallback(visibility.interactPermission()))
            .untrusted("viewRange", format(visibility.viewRange()))
            .untrusted("interactionRange", format(visibility.interactionRange()))
            .build());
    send(sender, GlossMessages.PANELS_INFO_FOLLOW,
        MessageArgs.builder()
            .untrusted("mode", follow.mode().name().toLowerCase(Locale.ROOT))
            .untrusted("player", follow.targetPlayerUuid() == null ? "-" : follow.targetPlayerUuid())
            .untrusted("rotation", follow.rotation().name().toLowerCase(Locale.ROOT))
            .build());
  }

  private boolean rejectIdentityMutation(CommandSender sender, String id) {
    PanelEditSession edit = matchingEdit(sender, id);
    if (edit == null) {
      return false;
    }
    send(sender, GlossMessages.PANELS_EDIT_IDENTITY_BLOCKED,
        MessageArgs.builder().untrusted("board", edit.id()).build());
    return true;
  }

  private PanelEditSession matchingEdit(CommandSender sender, String id) {
    if (!(sender instanceof Player player)) {
      return null;
    }
    PanelEditSession edit = editSessions.get(player.getUniqueId());
    return edit != null && edit.id().equals(id) ? edit : null;
  }

  private Player followTarget(CommandSender sender, PanelDefinition board) {
    UUID targetId = board.follow().targetPlayerUuid();
    Player target = targetId == null ? null : Bukkit.getPlayer(targetId);
    if (target != null && target.isOnline()) {
      return target;
    }
    send(sender, GlossMessages.PANELS_PLAYER_UNAVAILABLE,
        MessageArgs.builder().untrusted("player", targetId == null ? "-" : targetId).build());
    return null;
  }

  private void captureLocation(CommandSender sender, Player target, Consumer<Location> callback) {
    Runnable capture = () -> {
      if (!target.isOnline()) {
        runForSender(sender, () -> send(sender, GlossMessages.PANELS_PLAYER_UNAVAILABLE,
            MessageArgs.builder().untrusted("player", target.getName()).build()));
        return;
      }
      Location location = target.getLocation().clone();
      runForSender(sender, () -> callback.accept(location));
    };
    if (!SchedulerUtils.runEntity(plugin(), target, capture)) {
      send(sender, GlossMessages.PANELS_PLAYER_UNAVAILABLE,
          MessageArgs.builder().untrusted("player", target.getName()).build());
    }
  }

  private void runForSender(CommandSender sender, Runnable task) {
    boolean accepted;
    if (sender instanceof Player player) {
      accepted = SchedulerUtils.runEntity(plugin(), player, task);
    } else {
      accepted = SchedulerUtils.runGlobal(plugin(), task);
    }
    if (!accepted) {
      Gloss.warnThrottled("panel-command-work-scheduling",
          "Unable to schedule persistent panel command work for %s.", sender.getName());
    }
  }

  private void discardEdit(Player player) {
    PanelEditSession removed = editSessions.remove(player.getUniqueId());
    if (removed == null) {
      return;
    }
    Gloss plugin = Gloss.instance;
    if (plugin != null && plugin.getPanelRuntime() != null) {
      plugin.getPanelRuntime().clearBoardPreview(
          player, removed.snapshot().definition().uuid());
    }
  }

  private String resolveMenu(CommandSender sender, String menuId) {
    MenuDefinitionData menu = plugin().getMenuCatalog().definition(menuId).orElse(null);
    if (menu == null) {
      send(sender, GlossMessages.PANELS_MENU_UNAVAILABLE,
          MessageArgs.builder().untrusted("menu", menuId).build());
      return null;
    }
    return menu.getId();
  }

  private Player onlinePlayer(String playerName) {
    if (playerName == null || playerName.isBlank()) {
      return null;
    }
    Player player = Bukkit.getPlayerExact(playerName.strip());
    if (player != null) {
      return player;
    }
    try {
      return Bukkit.getPlayer(UUID.fromString(playerName.strip()));
    } catch (IllegalArgumentException ignored) {
      return null;
    }
  }

  private Player requirePlayer(CommandSender sender) {
    if (sender instanceof Player player) {
      return player;
    }
    send(sender, GlossMessages.COMMAND_PLAYER_ONLY);
    return null;
  }

  private boolean checkPermission(CommandSender sender) {
    if (sender.hasPermission(PERMISSION)) {
      return true;
    }
    send(sender, GlossMessages.PERMISSION_DENIED,
        MessageArgs.builder().untrusted("permission", PERMISSION).build());
    return false;
  }

  private void reportFailure(CommandSender sender, String id, Throwable failure) {
    Throwable cause = PanelCommandSupport.rootCause(failure);
    if (cause instanceof DocumentRevisionConflictException conflict) {
      sendLater(sender, GlossMessages.PANELS_REVISION_CONFLICT,
          MessageArgs.builder()
              .untrusted("board", conflict.id())
              .untrusted("expected", conflict.expectedRevision())
              .untrusted("actual", conflict.actualRevision())
              .build());
      return;
    }
    if (cause instanceof FileAlreadyExistsException) {
      sendLater(sender, GlossMessages.PANELS_ALREADY_EXISTS,
          MessageArgs.builder().untrusted("board", id).build());
      return;
    }
    if (cause instanceof NoSuchElementException) {
      sendLater(sender, GlossMessages.PANELS_UNKNOWN,
          MessageArgs.builder().untrusted("board", id).build());
      return;
    }
    if (cause instanceof IllegalArgumentException) {
      sendLater(sender, GlossMessages.PANELS_INVALID,
          MessageArgs.builder().untrusted("reason", safeReason(cause)).build());
      return;
    }
    if (!(cause instanceof CancellationException)) {
      Gloss.logExceptionStack(true, cause, "Persistent panel command failed for panel \"%s\".", id);
    }
    sendLater(sender, GlossMessages.PANELS_FAILED,
        MessageArgs.builder()
            .untrusted("board", id)
            .untrusted("reason", safeReason(cause))
            .build());
  }

  private void invalid(CommandSender sender, RuntimeException exception) {
    send(sender, GlossMessages.PANELS_INVALID,
        MessageArgs.builder().untrusted("reason", safeReason(exception)).build());
  }

  private void sendLater(CommandSender sender, TextKey key,
                         MessageArgs arguments) {
    Runnable feedback = () -> send(sender, key, arguments);
    boolean accepted;
    if (sender instanceof Player player) {
      accepted = SchedulerUtils.runEntity(plugin(), player, feedback);
    } else {
      accepted = SchedulerUtils.runGlobal(plugin(), feedback);
    }
    if (!accepted) {
      Gloss.warnThrottled("panel-command-feedback-scheduling",
          "Unable to schedule persistent panel command feedback for %s.", sender.getName());
    }
  }

  private void send(CommandSender sender, TextKey key) {
    send(sender, key, MessageArgs.empty());
  }

  private void send(CommandSender sender, TextKey key,
                    MessageArgs arguments) {
    plugin().getLocalization().send(sender, key, arguments);
  }

  private PanelService service() {
    return plugin().getPanelService();
  }

  private PanelRuntimeManager runtime() {
    return plugin().getPanelRuntime();
  }

  private Gloss plugin() {
    return Gloss.instance;
  }

  private static String fallback(String value) {
    return value == null ? "-" : value;
  }

  private static String format(double value) {
    return String.format(Locale.ROOT, "%.3f", value);
  }

  private static String safeReason(Throwable failure) {
    String message = failure == null ? null : failure.getMessage();
    return message == null || message.isBlank()
        ? failure == null ? "unknown failure" : failure.getClass().getSimpleName()
        : message;
  }

  private record CommandBoardState(PanelDefinition definition, PanelTransform effectiveTransform,
                                   PanelEditSession edit,
                                   PanelEditSession.Snapshot editSnapshot) {
  }

  public static final class PanelIdHandler implements DirectorParameterHandler<String> {
    @Override
    public KList<String> getPossibilities() {
      KList<String> ids = new KList<>();
      Gloss plugin = Gloss.instance;
      if (plugin == null || plugin.getPanelService() == null) {
        return ids;
      }
      for (PanelDefinition board : plugin.getPanelService().list()) {
        ids.add(board.id());
      }
      return ids;
    }

    @Override
    public String toString(String value) {
      return value == null ? "" : value;
    }

    @Override
    public String parse(String in, boolean force) throws DirectorParsingException {
      if (in == null || in.isBlank()) {
        throw new DirectorParsingException(GlossLocalization.globalText(GlossMessages.ERROR_PANEL_ID_REQUIRED));
      }
      String value = in.strip();
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

  public static final class AxesHandler implements DirectorParameterHandler<String> {
    @Override
    public KList<String> getPossibilities() {
      return new KList<>(List.of("x", "y", "z", "xy", "xz", "yz", "xyz"));
    }

    @Override
    public String toString(String value) {
      return value == null ? "" : value;
    }

    @Override
    public String parse(String in, boolean force) throws DirectorParsingException {
      try {
        return PanelCommandSupport.axes(in);
      } catch (IllegalArgumentException exception) {
        throw new DirectorParsingException(exception.getMessage());
      }
    }

    @Override
    public boolean supports(Class<?> type) {
      return type == String.class;
    }
  }

  public static final class OnlinePlayerHandler implements DirectorParameterHandler<String> {
    @Override
    public KList<String> getPossibilities() {
      KList<String> players = new KList<>();
      for (Player player : Bukkit.getOnlinePlayers()) {
        players.add(player.getName());
      }
      return players;
    }

    @Override
    public String toString(String value) {
      return value == null ? "" : value;
    }

    @Override
    public String parse(String in, boolean force) throws DirectorParsingException {
      if (in == null || in.isBlank()) {
        throw new DirectorParsingException(GlossLocalization.globalText(GlossMessages.ERROR_PANEL_PLAYER_REQUIRED));
      }
      return in.strip();
    }

    @Override
    public boolean supports(Class<?> type) {
      return type == String.class;
    }
  }
}
