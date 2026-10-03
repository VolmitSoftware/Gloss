package art.arcane.gloss.demo;

import art.arcane.gloss.Gloss;
import art.arcane.gloss.api.BeamSpec;
import art.arcane.gloss.beam.BeamService;
import art.arcane.gloss.nameplate.NameplateRuntime;
import art.arcane.gloss.nameplate.NameplateService;
import art.arcane.gloss.nametag.NametagRuntime;
import art.arcane.gloss.nametag.NametagService;
import org.bukkit.Location;
import org.bukkit.command.Command;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.permissions.PermissionAttachment;

import org.bukkit.Bukkit;
import java.util.HashSet;
import java.util.HashMap;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

public final class GlossDemoPlugin extends JavaPlugin {
  private final Map<UUID, PermissionAttachment> permissions = new HashMap<>();
  @Override
  public boolean onCommand(CommandSender sender, Command command, String label, String[] arguments) {
    if (arguments.length == 3 && arguments[0].equals("loaded")) {
      if (arguments[1].equals("nameplates")) {
        for (NameplateRuntime document : Gloss.instance.service(NameplateService.class).documents()) {
          if (document.id().equals(arguments[2])) {
            sender.sendMessage("loaded=" + document.doc().revision() + " show=" + document.doc().show().expression());
            return true;
          }
        }
      } else if (arguments[1].equals("nametags")) {
        for (NametagRuntime document : Gloss.instance.service(NametagService.class).runtimes()) {
          if (document.id().equals(arguments[2])) {
            sender.sendMessage("loaded=" + document.doc().revision() + " show=" + document.doc().show().expression());
            return true;
          }
        }
      }
      sender.sendMessage("Document not loaded.");
      return true;
    }
    if (!(sender instanceof Player player) || arguments.length != 1) {
      sender.sendMessage("/glossdemo beam|identity");
      return true;
    }
    if (arguments[0].equals("identity")) {
      PermissionAttachment attachment = permissions.computeIfAbsent(player.getUniqueId(), ignored -> player.addAttachment(this));
      boolean enabled = !Boolean.TRUE.equals(attachment.getPermissions().get("gloss.demo.staff"));
      attachment.setPermission("gloss.demo.staff", enabled);
      sender.sendMessage("Staff identity " + (enabled ? "enabled" : "disabled") + ".");
      return true;
    }
    if (!arguments[0].equals("beam")) {
      sender.sendMessage("/glossdemo beam|identity");
      return true;
    }
    BeamService beams = Gloss.instance.service(BeamService.class);
    Location from = player.getLocation().clone().add(-2D, 1D, 4D);
    Location to = player.getLocation().clone().add(2D, 3D, 8D);
    Set<UUID> viewers = new HashSet<>();
    for (Player viewer : Bukkit.getOnlinePlayers()) {
      viewers.add(viewer.getUniqueId());
    }
    beams.link(() -> from, () -> to, BeamSpec.ofMaterial("minecraft:yellow_stained_glass", 0.18D),
        240L, viewers);
    beams.trail(player, from, to, "end_rod", 0.2D, 64);
    sender.sendMessage("Beam and trail active for twelve seconds.");
    return true;
  }
}
