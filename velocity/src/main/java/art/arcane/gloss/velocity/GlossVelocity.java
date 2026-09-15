package art.arcane.gloss.velocity;

import art.arcane.gloss.expr.ExpressionScope;
import art.arcane.gloss.proxy.OwnershipProtocol;
import com.google.inject.Inject;
import com.github.retrooper.packetevents.protocol.score.ScoreFormat;
import com.github.retrooper.packetevents.PacketEvents;
import com.github.retrooper.packetevents.protocol.ConnectionState;
import com.github.retrooper.packetevents.protocol.player.User;
import net.kyori.adventure.text.format.Style;
import com.velocitypowered.api.command.SimpleCommand;
import com.velocitypowered.api.event.PostOrder;
import com.velocitypowered.api.event.Subscribe;
import com.velocitypowered.api.event.connection.DisconnectEvent;
import com.velocitypowered.api.event.connection.PluginMessageEvent;
import com.velocitypowered.api.event.player.ServerPostConnectEvent;
import com.velocitypowered.api.event.proxy.ProxyInitializeEvent;
import com.velocitypowered.api.event.proxy.ProxyPingEvent;
import com.velocitypowered.api.event.proxy.ProxyShutdownEvent;
import com.velocitypowered.api.plugin.Dependency;
import com.velocitypowered.api.plugin.Plugin;
import com.velocitypowered.api.plugin.annotation.DataDirectory;
import com.velocitypowered.api.proxy.Player;
import com.velocitypowered.api.proxy.ProxyServer;
import com.velocitypowered.api.proxy.ServerConnection;
import com.velocitypowered.api.scheduler.ScheduledTask;
import net.kyori.adventure.text.Component;
import org.slf4j.Logger;

import java.io.IOException;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;

@Plugin(id = "gloss", name = "Gloss", version = "@VERSION@", description = "Network tablists, scoreboards and MOTD",
    authors = {"VolmitSoftware"}, dependencies = {@Dependency(id = "packetevents")})
public final class GlossVelocity {
    private final ProxyServer proxy;
    private final Logger logger;
    private final Path directory;
    private final Set<UUID> hiddenBoards = new HashSet<>();
    private final Set<UUID> failedPlayers = new HashSet<>();
    private volatile RuntimeConfig config;
    private ProxyText text;
    private ProxyTablists tabs;
    private ProxyScoreboards boards;
    private ProxyOwnership ownership;
    private ScheduledTask refresh;
    private volatile boolean pingFailed;

    @Inject
    public GlossVelocity(ProxyServer proxy, Logger logger, @DataDirectory Path directory) {
        this.proxy = proxy;
        this.logger = logger;
        this.directory = directory;
    }

    @Subscribe(order = PostOrder.LAST)
    public synchronized void initialize(ProxyInitializeEvent event) {
        try {
            text = new ProxyText(proxy);
            ProxyDocuments.seed(directory);
            RuntimeConfig loaded = load();
            ownership = new ProxyOwnership(proxy, ProxyOwnership.loadKey(new ProxyOwnership.KeySource(proxy, logger, directory)));
            tabs = new ProxyTablists(proxy, text, logger);
            boards = new ProxyScoreboards(logger);
            config = loaded;
            schedule();
            proxy.getCommandManager().register(proxy.getCommandManager().metaBuilder("gloss").plugin(this).build(),
                new GlossCommand());
            logger.info("Gloss enabled: Velocity MOTD, tablists and scoreboards.");
        } catch (IOException | RuntimeException failure) {
            logger.error("Gloss could not initialize its Velocity services.", failure);
            shutdownServices();
        }
    }

    @Subscribe(order = PostOrder.LAST)
    public void ping(ProxyPingEvent event) {
        RuntimeConfig current = config;
        if (current == null) {
            return;
        }
        try {
            event.setPing(current.motd().render(event.getPing(), current.documents()));
        } catch (RuntimeException failure) {
            if (!pingFailed) {
                pingFailed = true;
                logger.error("Gloss MOTD failed; retaining the proxy ping response.", failure);
            }
        }
    }

    @Subscribe
    public synchronized void connected(ServerPostConnectEvent event) {
        if (config == null) {
            return;
        }
        Player player = event.getPlayer();
        ownership.forget(player.getUniqueId());
        tabs.forget(player.getUniqueId());
        boards.reset(player);
        failedPlayers.remove(player.getUniqueId());
    }

    @Subscribe
    public synchronized void disconnected(DisconnectEvent event) {
        UUID id = event.getPlayer().getUniqueId();
        hiddenBoards.remove(id);
        failedPlayers.remove(id);
        if (tabs != null) {
            tabs.forget(id);
        }
        if (boards != null) {
            boards.forget(id);
        }
        if (ownership != null) {
            ownership.forget(id);
        }
    }

    @Subscribe
    public synchronized void ownershipMessage(PluginMessageEvent event) {
        if (ownership == null || config == null) {
            return;
        }
        ProxyDocuments.Settings settings = config.documents().settings();
        int mask = (settings.motd() ? OwnershipProtocol.MOTD : 0)
            | (settings.tablist() ? OwnershipProtocol.TABLIST : 0)
            | (settings.scoreboards() ? OwnershipProtocol.SCOREBOARD : 0);
        int restored = ownership.handle(event, mask);
        if (restored == 0 || !(event.getSource() instanceof ServerConnection connection)) {
            return;
        }
        Player player = connection.getPlayer();
        if ((restored & OwnershipProtocol.TABLIST) != 0) {
            tabs.forget(player.getUniqueId());
        }
        if ((restored & OwnershipProtocol.SCOREBOARD) != 0) {
            boards.reset(player);
        }
    }

    @Subscribe
    public synchronized void shutdown(ProxyShutdownEvent event) {
        shutdownServices();
    }

    private RuntimeConfig load() throws IOException {
        ProxyDocuments.Snapshot documents = ProxyDocuments.load(directory);
        return new RuntimeConfig(documents, new ProxyMotd(text, directory, documents.motd()));
    }

    private void schedule() {
        if (refresh != null) {
            refresh.cancel();
        }
        refresh = proxy.getScheduler().buildTask(this, this::tick)
            .repeat(Duration.ofMillis(config.documents().settings().refreshMillis())).schedule();
    }

    private synchronized void tick() {
        RuntimeConfig current = config;
        if (current == null) {
            return;
        }
        for (Player player : proxy.getAllPlayers()) {
            if (!player.isActive() || player.getCurrentServer().isEmpty()) {
                continue;
            }
            User user = PacketEvents.getAPI().getPlayerManager().getUser(player);
            if (user == null || user.getEncoderState() != ConnectionState.PLAY) {
                continue;
            }
            try {
                tabs.render(player, current.documents());
                renderBoard(player, current.documents());
            } catch (RuntimeException failure) {
                if (failedPlayers.add(player.getUniqueId())) {
                    logger.error("Gloss display update failed for {}.", player.getUniqueId(), failure);
                }
            }
        }
    }

    private void renderBoard(Player player, ProxyDocuments.Snapshot documents) {
        if (!documents.settings().scoreboards() || hiddenBoards.contains(player.getUniqueId())) {
            boards.clear(player);
            return;
        }
        ExpressionScope scope = text.scope(player, player);
        ProxyDocuments.Board selected = null;
        for (ProxyDocuments.Board board : documents.boards()) {
            if (text.test(board.show(), scope) && text.test(board.when(), scope)
                && (selected == null || board.priority() > selected.priority())) {
                selected = board;
            }
        }
        if (selected == null) {
            boards.clear(player);
            return;
        }
        ProxyDocuments.BoardPresentation presentation = text.select(selected, scope);
        List<ProxyScoreboards.Line> lines = new ArrayList<>(presentation.lines().size());
        for (ProxyDocuments.BoardLine line : presentation.lines()) {
            lines.add(new ProxyScoreboards.Line(text.render(line.text(), scope),
                scoreFormat(line, scope)));
        }
        boards.render(player, text.render(presentation.title(), scope), lines, presentation.hideNumbers());
    }

    private ScoreFormat scoreFormat(ProxyDocuments.BoardLine line, ExpressionScope scope) {
        if (line.format() == null) {
            return line.value() == null ? null : ScoreFormat.fixedScore(text.render(line.value(), scope));
        }
        return switch (line.format()) {
            case "blank" -> ScoreFormat.blankScore();
            case "number" -> ScoreFormat.styledScore(Style.empty());
            case "styled" -> ScoreFormat.styledScore(text.render(line.value(), scope).style());
            case "fixed" -> ScoreFormat.fixedScore(text.render(line.value(), scope));
            default -> throw new IllegalArgumentException("Unknown score format " + line.format());
        };
    }

    private void shutdownServices() {
        config = null;
        if (ownership != null) {
            ownership.close();
            ownership = null;
        }
        if (refresh != null) {
            refresh.cancel();
            refresh = null;
        }
        if (tabs != null) {
            tabs.close();
            tabs = null;
        }
        if (boards != null) {
            boards.close();
            boards = null;
        }
        hiddenBoards.clear();
        failedPlayers.clear();
    }

    private record RuntimeConfig(ProxyDocuments.Snapshot documents, ProxyMotd motd) { }

    private final class GlossCommand implements SimpleCommand {
        @Override
        public void execute(Invocation invocation) {
            synchronized (GlossVelocity.this) {
                String[] arguments = invocation.arguments();
                if (arguments.length == 2 && arguments[0].equalsIgnoreCase("board")
                    && arguments[1].equalsIgnoreCase("toggle") && invocation.source() instanceof Player player) {
                    if (!hiddenBoards.add(player.getUniqueId())) {
                        hiddenBoards.remove(player.getUniqueId());
                    }
                    invocation.source().sendMessage(Component.text(hiddenBoards.contains(player.getUniqueId())
                        ? "Gloss scoreboard hidden." : "Gloss scoreboard shown."));
                    tick();
                    return;
                }
                if (!invocation.source().hasPermission("gloss.admin")) {
                    invocation.source().sendMessage(Component.text("You need gloss.admin to manage Gloss."));
                    return;
                }
                if (arguments.length == 1 && arguments[0].equalsIgnoreCase("reload")) {
                    try {
                        RuntimeConfig loaded = load();
                        byte[] loadedKey = ProxyOwnership.loadKey(new ProxyOwnership.KeySource(proxy, logger, directory));
                        config = loaded;
                        ownership.setKey(loadedKey);
                        failedPlayers.clear();
                        pingFailed = false;
                        schedule();
                        tick();
                        invocation.source().sendMessage(Component.text("Gloss proxy configuration reloaded."));
                    } catch (IOException | RuntimeException failure) {
                        logger.error("Gloss proxy reload failed; keeping the previous configuration.", failure);
                        invocation.source().sendMessage(Component.text("Gloss reload failed; see the proxy log. Previous configuration retained."));
                    }
                    return;
                }
                invocation.source().sendMessage(Component.text("Gloss Velocity: MOTD, tablists, scoreboards. /gloss reload | /gloss board toggle"));
            }
        }

        @Override
        public List<String> suggest(Invocation invocation) {
            return invocation.arguments().length <= 1 ? List.of("reload", "board") : List.of("toggle");
        }
    }
}
