package art.arcane.gloss.command;

import art.arcane.gloss.Gloss;
import art.arcane.gloss.locale.GlossMessages;
import art.arcane.volmlib.util.director.annotations.Director;
import art.arcane.volmlib.util.director.annotations.Param;
import art.arcane.volmlib.util.localization.MessageArgument;
import org.bukkit.command.CommandSender;

@Director(name = "gloss", aliases = {"gl", "glo", "gg"}, descriptionKey = "command.help.root", description = "Gloss command root")
public class CommandGloss {
    private static final String SUPPORTED_MC_VERSION = "26.1.2 - 26.2";

    private final Gloss plugin;
    private CommandGlossHologram hologram;
    private CommandGlossBoard board;
    private CommandGlossEmoji emoji;
    private CommandGlossAnimations animations;
    private CommandGlossBubbles bubbles;
    private CommandGlossTablist tablist;
    private CommandGlossMotd motd;
    private CommandGlossMenu menu;
    private CommandGlossPanel panel;
    private CommandGlossPreview preview;
    private CommandGlossItem item;
    private CommandGlossSync sync;
    private CommandGlossImport legacyImport;

    public CommandGloss(Gloss plugin) {
        this.plugin = plugin;
        this.hologram = new CommandGlossHologram(plugin);
        this.board = new CommandGlossBoard(plugin);
        this.emoji = new CommandGlossEmoji(plugin);
        this.animations = new CommandGlossAnimations(plugin);
        this.bubbles = new CommandGlossBubbles(plugin);
        this.tablist = new CommandGlossTablist(plugin);
        this.motd = new CommandGlossMotd(plugin);
        this.menu = new CommandGlossMenu(plugin);
        this.panel = new CommandGlossPanel();
        this.preview = new CommandGlossPreview();
        this.item = new CommandGlossItem();
        this.sync = new CommandGlossSync();
        this.legacyImport = new CommandGlossImport();
    }

    public void shutdown() {
        if (panel != null) {
            panel.shutdown();
        }
    }

    @Director(name = "status", descriptionKey = "command.help.status", description = "Show terse runtime counts")
    public void status(@Param(name = "sender", contextual = true) CommandSender sender) {
        if (GlossCommandMessages.denied(sender, "gloss.admin")) {
            return;
        }

        GlossCommandMessages.send(sender, GlossMessages.STATUS_HOLOGRAMS,
                MessageArgument.trusted("count", plugin.holograms().hologramCount()),
                MessageArgument.trusted("temporary", plugin.holograms().temporaryCount()),
                MessageArgument.trusted("entities", plugin.holograms().activeEntityCount()));
        GlossCommandMessages.send(sender, GlossMessages.STATUS_BOARDS,
                MessageArgument.trusted("count", plugin.boards().boards().size()));
        GlossCommandMessages.send(sender, GlossMessages.STATUS_EMOJI,
                MessageArgument.trusted("enabled", CommandGlossEmoji.enabledEmoji(plugin).size()),
                MessageArgument.trusted("total", plugin.emoji().all().size()));
        GlossCommandMessages.send(sender, GlossMessages.STATUS_ANIMATIONS,
                MessageArgument.trusted("count", plugin.animations().names().size()));
        GlossCommandMessages.send(sender, GlossMessages.STATUS_EFFECTS,
                MessageArgument.trusted("bubbles", plugin.bubbles().activeCount()),
                MessageArgument.trusted("indicators", plugin.indicators().activeCount()),
                MessageArgument.trusted("drops", plugin.drops().activeCount()));
    }

    @Director(name = "reload", sync = true, descriptionKey = "command.help.reload", description = "Reload Gloss configuration and services")
    public void reload(@Param(name = "sender", contextual = true) CommandSender sender) {
        if (GlossCommandMessages.denied(sender, "gloss.admin")) {
            return;
        }

        plugin.reloadAll();
        GlossCommandMessages.send(sender, GlossMessages.RELOAD_DONE);
    }

    @Director(name = "version", descriptionKey = "command.help.version", description = "Show the Gloss version and supported Minecraft range")
    public void version(@Param(name = "sender", contextual = true) CommandSender sender) {
        GlossCommandMessages.send(sender, GlossMessages.VERSION_LINE,
                MessageArgument.trusted("version", plugin.getDescription().getVersion()),
                MessageArgument.trusted("mc", SUPPORTED_MC_VERSION));
    }
}
