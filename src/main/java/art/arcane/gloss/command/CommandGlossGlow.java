package art.arcane.gloss.command;

import art.arcane.gloss.Gloss;
import art.arcane.gloss.glow.GlowService;
import art.arcane.gloss.locale.GlossMessages;
import art.arcane.volmlib.util.director.annotations.Director;
import art.arcane.volmlib.util.director.annotations.Param;
import art.arcane.volmlib.util.localization.MessageArgument;
import org.bukkit.Bukkit;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;

@Director(name = "glow", descriptionKey = "command.help.glow.root", description = "Entity glow tools")
public final class CommandGlossGlow {
    private static final String PURPOSE = "command";
    private static final int PRIORITY = 100;

    private final Gloss plugin;

    public CommandGlossGlow(Gloss plugin) {
        this.plugin = plugin;
    }

    @Director(name = "set", sync = true, descriptionKey = "command.help.glow.set",
        description = "Make a player glow for you")
    public void set(@Param(name = "sender", contextual = true) CommandSender sender,
                    @Param(name = "player", descriptionKey = "command.help.glow.arg.player",
                        description = "Player to outline") String player,
                    @Param(name = "value", defaultValue = "red", descriptionKey = "command.help.glow.arg.color",
                        description = "Named text color") String value,
                    @Param(name = "count", defaultValue = "0", descriptionKey = "command.help.glow.arg.ticks",
                        description = "Ticks the glow lasts, or 0 to keep it") int count) {
        if (GlossCommandMessages.denied(sender, "gloss.glow.set")) {
            return;
        }
        Player viewer = requirePlayer(sender);
        GlowService glow = viewer == null ? null : service(sender);
        if (glow == null) {
            return;
        }
        Player target = Bukkit.getPlayerExact(player);
        if (target == null) {
            GlossCommandMessages.send(sender, GlossMessages.WORLD_GLOW_MISSING,
                MessageArgument.untrusted("player", player));
            return;
        }
        glow.tag(viewer, target, value, PURPOSE, PRIORITY, Math.max(0, count));
        GlossCommandMessages.send(sender, GlossMessages.WORLD_GLOW_SET,
            MessageArgument.untrusted("target", target.getName()),
            MessageArgument.untrusted("value", value));
    }

    @Director(name = "clear", sync = true, descriptionKey = "command.help.glow.clear",
        description = "Clear a player's glow for you")
    public void clear(@Param(name = "sender", contextual = true) CommandSender sender,
                      @Param(name = "player", descriptionKey = "command.help.glow.arg.cleared",
                          description = "Player to stop outlining") String player) {
        if (GlossCommandMessages.denied(sender, "gloss.glow.clear")) {
            return;
        }
        Player viewer = requirePlayer(sender);
        GlowService glow = viewer == null ? null : service(sender);
        if (glow == null) {
            return;
        }
        Player target = Bukkit.getPlayerExact(player);
        if (target == null) {
            GlossCommandMessages.send(sender, GlossMessages.WORLD_GLOW_MISSING,
                MessageArgument.untrusted("player", player));
            return;
        }
        glow.untag(viewer, target, PURPOSE);
        GlossCommandMessages.send(sender, GlossMessages.WORLD_GLOW_CLEARED,
            MessageArgument.untrusted("target", target.getName()));
    }

    private GlowService service(CommandSender sender) {
        GlowService glow = plugin.service(GlowService.class);
        if (glow == null || !glow.enabled()) {
            GlossCommandMessages.send(sender, GlossMessages.WORLD_DISABLED);
            return null;
        }
        return glow;
    }

    private static Player requirePlayer(CommandSender sender) {
        if (sender instanceof Player player) {
            return player;
        }
        GlossCommandMessages.send(sender, GlossMessages.WORLD_PLAYERS_ONLY);
        return null;
    }
}
