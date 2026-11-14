package art.arcane.gloss.editor;

import art.arcane.gloss.Gloss;
import art.arcane.gloss.locale.GlossMessages;
import art.arcane.volmlib.util.localization.MessageArgs;
import art.arcane.volmlib.util.scheduling.SchedulerUtils;
import net.milkbowl.vault.permission.Permission;
import org.bukkit.Bukkit;
import org.bukkit.OfflinePlayer;
import org.bukkit.entity.Player;
import org.bukkit.permissions.PermissionDefault;
import org.bukkit.plugin.PluginManager;
import org.bukkit.plugin.RegisteredServiceProvider;

import java.nio.file.Path;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import java.util.function.Consumer;

/** {@link EditorHost} on Paper, Spigot and Folia. */
public final class BukkitEditorHost implements EditorHost {
  /** Permission plugins that can grant nodes to offline players without Vault. */
  private static final List<String> PERMISSION_PLUGINS = List.of("LuckPerms", "PermissionsEx", "GroupManager",
      "UltraPermissions", "PowerRanks", "bPermissions", "zPermissions");

  private final Gloss plugin;
  private final String platform;

  public BukkitEditorHost(Gloss plugin) {
    this.plugin = Objects.requireNonNull(plugin);
    this.platform = detectPlatform();
  }

  @Override
  public String platform() {
    return platform;
  }

  @Override
  public String minecraftVersion() {
    String bukkit = Bukkit.getBukkitVersion();
    int dash = bukkit.indexOf('-');
    return dash < 0 ? bukkit : bukkit.substring(0, dash);
  }

  @Override
  public String glossVersion() {
    return plugin.getDescription().getVersion();
  }

  @Override
  public String serverName() {
    return EditorServerName.fallback(Bukkit.getMotd(), Path.of("").toAbsolutePath());
  }

  @Override
  public String playerName(UUID player) {
    String name = Bukkit.getOfflinePlayer(player).getName();
    return name == null || name.isBlank() ? player.toString().substring(0, 8) : name;
  }

  @Override
  public void permission(UUID player, String node, Consumer<Admin> result) {
    Player online = Bukkit.getPlayer(player);
    if (online != null) {
      boolean scheduled = SchedulerUtils.runEntity(plugin, online,
          () -> result.accept(online.hasPermission(node) ? Admin.ADMIN : Admin.NOT_ADMIN));
      if (!scheduled) {
        result.accept(Admin.UNKNOWN);
      }
      return;
    }
    // Vault may load the player's data, so the offline check never runs on the server thread.
    Thread.ofVirtual().name("Gloss-editor-permission").start(() -> result.accept(offline(player, node)));
  }

  private Admin offline(UUID player, String node) {
    OfflinePlayer offline = Bukkit.getOfflinePlayer(player);
    PluginManager plugins = Bukkit.getPluginManager();
    org.bukkit.permissions.Permission declared = plugins.getPermission(node);
    boolean byDefault = (declared == null ? PermissionDefault.OP : declared.getDefault()).getValue(offline.isOp());
    Optional<Boolean> answer = Optional.empty();
    if (plugins.isPluginEnabled("Vault")) {
      try {
        RegisteredServiceProvider<Permission> registration = Bukkit.getServicesManager().getRegistration(Permission.class);
        if (registration != null && registration.getProvider() != null && registration.getProvider().isEnabled()) {
          answer = Optional.of(registration.getProvider().playerHas(null, offline, node));
        }
      } catch (RuntimeException | LinkageError unavailable) {
        answer = Optional.empty();
      }
    }
    return OfflinePermission.decide(byDefault, answer, PERMISSION_PLUGINS.stream().anyMatch(plugins::isPluginEnabled));
  }

  @Override
  public void log(String line) {
    Gloss.log(java.util.logging.Level.INFO, "%s", line);
  }

  @Override
  public void warn(String line) {
    Gloss.log(java.util.logging.Level.WARNING, "%s", line);
  }

  @Override
  public void notice(Optional<UUID> player, String line) {
    SchedulerUtils.runGlobal(plugin, () -> {
      MessageArgs arguments = MessageArgs.builder().untrusted("line", line).build();
      for (Player online : Bukkit.getOnlinePlayers()) {
        boolean owner = player.isPresent() && player.get().equals(online.getUniqueId());
        if (owner || online.hasPermission(EditorPermissions.NOTIFY)) {
          plugin.getLocalization().send(online, GlossMessages.EDITOR_NOTICE, arguments);
        }
      }
    });
  }

  /** The server software's own name: Paper, Purpur and Folia say so; Spigot reports CraftBukkit. */
  private static String detectPlatform() {
    String name = Bukkit.getName().toLowerCase(java.util.Locale.ROOT);
    return switch (name) {
      case "paper", "purpur", "folia", "spigot" -> name;
      case "craftbukkit" -> "spigot";
      default -> "other";
    };
  }
}
