package art.arcane.gloss.command;

import art.arcane.gloss.Gloss;
import art.arcane.gloss.locale.GlossMessages;
import art.arcane.volmlib.util.director.annotations.Director;
import art.arcane.volmlib.util.director.annotations.Param;
import art.arcane.volmlib.util.localization.MessageArgument;
import org.bukkit.command.CommandSender;

@Director(name = "gloss", aliases = {"gl", "glo", "gg"}, descriptionKey = "command.help.root", description = "Gloss command root")
public class CommandGloss {
    private final Gloss plugin;
    private CommandGlossHologram hologram;
    private CommandGlossBoard board;
    private CommandGlossEmoji emoji;
    private CommandGlossAnimations animations;
    private CommandGlossBubbles bubbles;
    private CommandGlossTablist tablist;
    private CommandGlossMotd motd;
    private CommandGlossDrops drops;
    private CommandGlossIndicators indicators;
    private CommandGlossMenu menu;
    private CommandGlossPanel panel;
    private CommandGlossPreview preview;
    private CommandGlossItem item;
    private CommandGlossWeb web;
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
        this.drops = new CommandGlossDrops(plugin);
        this.indicators = new CommandGlossIndicators(plugin);
        this.menu = new CommandGlossMenu(plugin);
        this.panel = new CommandGlossPanel();
        this.preview = new CommandGlossPreview();
        this.item = new CommandGlossItem();
        this.web = new CommandGlossWeb(plugin);
        this.legacyImport = new CommandGlossImport();
    }

    /** Runs on the tree the director cache kept, never on one a racing builder threw away. */
    public void enable() {
        if (panel != null) {
            panel.enable();
        }
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
}
