package art.arcane.gloss.command;

import art.arcane.gloss.Gloss;
import art.arcane.gloss.bedrock.BedrockService;
import art.arcane.gloss.doc.DocumentRegistry;
import art.arcane.gloss.locale.GlossMessages;
import art.arcane.gloss.menu.icon.TextImageRasterCache;
import art.arcane.volmlib.util.director.annotations.Director;
import art.arcane.volmlib.util.director.annotations.Param;
import art.arcane.volmlib.util.localization.MessageArgument;
import org.bukkit.Bukkit;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;

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
    private CommandGlossDebug debug;
    // --- lane:screen ---
    private CommandGlossSurface surface;
    private CommandGlossNametag nametag;
    private CommandGlossHud hud;

    // --- lane:chat ---
    private CommandGlossChannel channel;
    private CommandGlossStrings strings;
    private CommandGlossLeaderboard leaderboard;

    // --- lane:forms ---
    private CommandGlossInventory inventory;

    // --- lane:world ---
    private CommandGlossMarker marker;
    private CommandGlossWaypoint waypoint;
    private CommandGlossCamera camera;
    private CommandGlossNameplate nameplate;
    private CommandGlossGlow glow;

    // --- lane:behaviors ---
    private CommandGlossBehavior behavior;
    private CommandGlossState state;
    private CommandGlossDo doCommand;
    private CommandGlossExpr expr;
    private CommandGlossExplain explain;

    @Director(name = "do", sync = true, descriptionKey = "command.help.do.root",
            description = "Fire the command behavior entries with this name")
    public void runDo(@Param(name = "sender", contextual = true) CommandSender sender,
                      @Param(name = "name", descriptionKey = "command.help.do.name",
                              description = "Behavior command name") String name,
                      @Param(name = "args", defaultValue = "*", descriptionKey = "command.help.do.args",
                              description = "Arguments as key=value pairs; quote them to include spaces") String args) {
        doCommand.fire(sender, name, args);
    }

    @Director(name = "expr", sync = true, descriptionKey = "command.help.expr.root",
            description = "Evaluate one expression and show what answered it")
    public void runExpr(@Param(name = "sender", contextual = true) CommandSender sender,
                        @Param(name = "expression", descriptionKey = "command.help.expr.expression",
                                description = "Expression to evaluate; quote it to include spaces") String expression) {
        expr.expr(sender, expression);
    }

    @Director(name = "explain", sync = true, descriptionKey = "command.help.explain.root",
            description = "Show which conditions a runtime consulted and which won")
    public void runExplain(@Param(name = "sender", contextual = true) CommandSender sender,
                           @Param(name = "kind", descriptionKey = "command.help.explain.kind",
                                   description = "Runtime to explain, such as board or behavior") String kind,
                           @Param(name = "id", descriptionKey = "command.help.explain.id",
                                   description = "Document id to explain") String id,
                           @Param(name = "player", defaultValue = "*", descriptionKey = "command.help.explain.player",
                                   description = "Player to explain it for, or * for yourself") String player,
                           @Param(name = "page", defaultValue = "1", descriptionKey = "command.help.arg.list_page",
                                   description = "One-based list page") int page) {
        explain.explain(sender, kind, id, player, page);
    }

    // --- lane:authoring ---
    private CommandGlossPack pack;
    private CommandGlossHistory history;
    private CommandGlossRestore restore;
    private CommandGlossCheck check;
    private CommandGlossExport export;

    // --- lane:forge ---
    private CommandGlossForge forge;

    // --- lane:fixes ---

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
        this.debug = new CommandGlossDebug(plugin);
        // --- lane:screen ---
        this.surface = new CommandGlossSurface(plugin);
        this.nametag = new CommandGlossNametag(plugin);
        this.hud = new CommandGlossHud();

        // --- lane:chat ---
        this.channel = new CommandGlossChannel(plugin);
        this.strings = new CommandGlossStrings(plugin);
        this.leaderboard = new CommandGlossLeaderboard(plugin);

        // --- lane:forms ---
        this.inventory = new CommandGlossInventory(plugin);

        // --- lane:world ---
        this.marker = new CommandGlossMarker(plugin);
        this.waypoint = new CommandGlossWaypoint(plugin);
        this.camera = new CommandGlossCamera(plugin);
        this.nameplate = new CommandGlossNameplate(plugin);
        this.glow = new CommandGlossGlow(plugin);

        // --- lane:behaviors ---
        this.behavior = new CommandGlossBehavior(plugin);
        this.state = new CommandGlossState(plugin);
        this.doCommand = new CommandGlossDo(plugin);
        this.expr = new CommandGlossExpr(plugin);
        this.explain = new CommandGlossExplain();

        // --- lane:authoring ---
        this.pack = new CommandGlossPack(plugin);
        this.history = new CommandGlossHistory(plugin);
        this.restore = new CommandGlossRestore(plugin);
        this.check = new CommandGlossCheck(plugin);
        this.export = new CommandGlossExport(plugin);

        // --- lane:forge ---
        this.forge = new CommandGlossForge(plugin);

        // --- lane:fixes ---

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

    @Director(name = "version", hidden = true, descriptionKey = "command.help.version", description = "Show the installed plugin version")
    public void version(@Param(name = "sender", contextual = true) CommandSender sender) {
        debug.version(sender);
    }

    @Director(name = "language", sync = true, descriptionKey = "command.help.language", description = "Choose your language or the server default")
    public void language(@Param(name = "sender", contextual = true) CommandSender sender) {
        plugin.languageSwitcher().open(sender);
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
        // --- lane:fixes ---
        GlossCommandMessages.send(sender, GlossMessages.STATUS_SCHEMA_SKIPPED,
                MessageArgument.trusted("count", DocumentRegistry.unsupportedSchemaTotal()));
        GlossCommandMessages.send(sender, GlossMessages.STATUS_TEXTIMAGE_OVERSIZE,
                MessageArgument.trusted("count", TextImageRasterCache.oversizeCount()));
        BedrockService bedrock = plugin.bedrock();
        GlossCommandMessages.send(sender, GlossMessages.STATUS_BEDROCK,
                MessageArgument.trusted("name", bedrock == null ? "off" : bedrock.describe()),
                MessageArgument.trusted("count", onlineBedrockViewers(bedrock)));
    }

    /** Counted on demand: /gloss status is an operator command, not a tick. */
    private static int onlineBedrockViewers(BedrockService bedrock) {
        if (bedrock == null) {
            return 0;
        }
        int count = 0;
        for (Player player : Bukkit.getOnlinePlayers()) {
            if (bedrock.isBedrock(player)) {
                count++;
            }
        }
        return count;
    }
}
