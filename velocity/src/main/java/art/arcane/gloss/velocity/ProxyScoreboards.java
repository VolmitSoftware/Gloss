package art.arcane.gloss.velocity;

import com.github.retrooper.packetevents.PacketEvents;
import com.github.retrooper.packetevents.event.PacketListenerAbstract;
import com.github.retrooper.packetevents.event.PacketListenerPriority;
import com.github.retrooper.packetevents.event.PacketSendEvent;
import com.github.retrooper.packetevents.netty.channel.ChannelHelper;
import com.github.retrooper.packetevents.protocol.ConnectionState;
import com.github.retrooper.packetevents.protocol.packettype.PacketType;
import com.github.retrooper.packetevents.protocol.player.ClientVersion;
import com.github.retrooper.packetevents.protocol.player.User;
import com.github.retrooper.packetevents.protocol.score.ScoreFormat;
import com.github.retrooper.packetevents.protocol.score.FixedScoreFormat;
import com.github.retrooper.packetevents.protocol.score.StyledScoreFormat;
import com.github.retrooper.packetevents.wrapper.play.server.WrapperPlayServerDisplayScoreboard;
import com.github.retrooper.packetevents.wrapper.play.server.WrapperPlayServerResetScore;
import com.github.retrooper.packetevents.wrapper.play.server.WrapperPlayServerScoreboardObjective;
import com.github.retrooper.packetevents.wrapper.play.server.WrapperPlayServerUpdateScore;
import com.velocitypowered.api.proxy.Player;
import net.kyori.adventure.text.Component;
import org.slf4j.Logger;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

public final class ProxyScoreboards extends PacketListenerAbstract implements AutoCloseable {
    private static final String OBJECTIVE = "gloss_proxy";
    private static final int MAX_LINES = 15;

    private final Logger logger;
    private final Map<UUID, BoardState> states = new ConcurrentHashMap<>();
    private volatile boolean closed;

    public ProxyScoreboards(Logger logger) {
        super(PacketListenerPriority.HIGHEST);
        this.logger = Objects.requireNonNull(logger);
        PacketEvents.getAPI().getEventManager().registerListener(this);
    }

    @Override
    public void close() {
        closed = true;
        PacketEvents.getAPI().getEventManager().unregisterListener(this);
        for (BoardState state : states.values()) {
            execute(state.user, () -> {
                state.desired = null;
                flush(state);
            });
        }
        states.clear();
    }

    public void render(Player player, Component title, List<Line> lines, boolean hideNumbers) {
        if (closed) {
            return;
        }
        BoardFrame frame = new BoardFrame(Objects.requireNonNull(title),
                List.copyOf(lines.subList(0, Math.min(MAX_LINES, lines.size()))), hideNumbers);
        User user = PacketEvents.getAPI().getPlayerManager().getUser(player);
        if (user != null) {
            execute(user, () -> {
                if (closed || !player.isActive()) {
                    return;
                }
                BoardState state = states.computeIfAbsent(player.getUniqueId(), ignored -> new BoardState(user));
                if (user.getClientVersion().isOlderThan(ClientVersion.V_1_20_3)) {
                    if (!state.unsupportedReported) {
                        state.unsupportedReported = true;
                        logger.warn("Proxy scoreboards require Minecraft 1.20.3 or newer; skipping {}", player.getUsername());
                    }
                    return;
                }
                state.desired = frame;
                flush(state);
            });
        }
    }

    public void clear(Player player) {
        BoardState state = states.get(player.getUniqueId());
        if (state != null) {
            execute(state.user, () -> {
                state.desired = null;
                flush(state);
            });
        }
    }

    public void reset(Player player) {
        BoardState state = states.get(player.getUniqueId());
        if (state != null) {
            execute(state.user, () -> {
                state.reset = true;
                flush(state);
            });
        }
    }

    public void forget(UUID playerId) {
        states.remove(playerId);
    }

    @Override
    public void onPacketSend(PacketSendEvent event) {
        if (closed || event.isCancelled() || event.getUser().getUUID() == null) {
            return;
        }
        User user = event.getUser();
        try {
            if (event.getPacketType() == PacketType.Play.Server.DISPLAY_SCOREBOARD) {
                interceptDisplay(event, user);
                return;
            }
            BoardState state = states.get(user.getUUID());
            if (state == null) {
                return;
            }
            if (event.getPacketType() == PacketType.Play.Server.JOIN_GAME) {
                state.reset = true;
                state.backendDisplays.clear();
                event.getTasksAfterSend().add(() -> flushSafely(state));
            } else {
                if (event.getPacketType() == PacketType.Play.Server.SCOREBOARD_OBJECTIVE) {
                    WrapperPlayServerScoreboardObjective packet = new WrapperPlayServerScoreboardObjective(event);
                    if (packet.getMode() == WrapperPlayServerScoreboardObjective.ObjectiveMode.REMOVE) {
                        state.backendDisplays.replaceAll((slot, name) -> name.equals(packet.getName()) ? "" : name);
                    }
                }
                if (state.desired != null) {
                    protectObjective(event);
                }
            }
        } catch (RuntimeException exception) {
            report(user, exception);
        }
    }

    private void interceptDisplay(PacketSendEvent event, User user) {
        WrapperPlayServerDisplayScoreboard packet = new WrapperPlayServerDisplayScoreboard(event);
        if (!sidebarSlot(packet.getPosition())) {
            return;
        }
        BoardState state = states.computeIfAbsent(user.getUUID(), ignored -> new BoardState(user));
        state.backendDisplays.put(packet.getPosition(), packet.getScoreName());
        if (state.desired != null) {
            event.setCancelled(true);
        }
    }

    private void protectObjective(PacketSendEvent event) {
        if (event.getPacketType() == PacketType.Play.Server.SCOREBOARD_OBJECTIVE) {
            event.setCancelled(OBJECTIVE.equals(new WrapperPlayServerScoreboardObjective(event).getName()));
        } else if (event.getPacketType() == PacketType.Play.Server.UPDATE_SCORE) {
            event.setCancelled(OBJECTIVE.equals(new WrapperPlayServerUpdateScore(event).getObjectiveName()));
        } else if (event.getPacketType() == PacketType.Play.Server.RESET_SCORE) {
            WrapperPlayServerResetScore packet = new WrapperPlayServerResetScore(event);
            event.setCancelled(OBJECTIVE.equals(packet.getObjective())
                    || packet.getObjective() == null && packet.getTargetName().startsWith("gloss_line_"));
        }
    }

    private void execute(User user, Runnable action) {
        try {
            ChannelHelper.runInEventLoop(user.getChannel(), () -> {
                try {
                    action.run();
                } catch (RuntimeException exception) {
                    report(user, exception);
                }
            });
        } catch (RuntimeException exception) {
            report(user, exception);
        }
    }

    private void flushSafely(BoardState state) {
        try {
            flush(state);
        } catch (RuntimeException exception) {
            report(state.user, exception);
        }
    }

    private void report(User user, RuntimeException exception) {
        BoardState state = states.computeIfAbsent(user.getUUID(), ignored -> new BoardState(user));
        if (!state.failureReported) {
            state.failureReported = true;
            state.reset = true;
            logger.error("Could not update proxy scoreboard for " + user.getName(), exception);
        }
    }

    private void flush(BoardState state) {
        User user = state.user;
        if (user.getEncoderState() != ConnectionState.PLAY || !ChannelHelper.isOpen(user.getChannel())) {
            return;
        }
        BoardFrame frame = state.desired;
        if (frame == null) {
            if (state.sent != null) {
                removeObjective(user);
                for (Map.Entry<Integer, String> display : state.backendDisplays.entrySet()) {
                    user.sendPacketSilently(new WrapperPlayServerDisplayScoreboard(display.getKey(), display.getValue()));
                }
                state.sent = null;
            }
            return;
        }
        BoardFrame previous = state.sent;
        if (previous == null || state.reset) {
            removeObjective(user);
            user.sendPacketSilently(objective(WrapperPlayServerScoreboardObjective.ObjectiveMode.CREATE, frame));
            for (int slot = 3; slot <= 18; slot++) {
                user.sendPacketSilently(new WrapperPlayServerDisplayScoreboard(slot, ""));
            }
            user.sendPacketSilently(new WrapperPlayServerDisplayScoreboard(1, OBJECTIVE));
            previous = null;
            state.reset = false;
        } else if (!previous.title().equals(frame.title()) || previous.hideNumbers() != frame.hideNumbers()) {
            user.sendPacketSilently(objective(WrapperPlayServerScoreboardObjective.ObjectiveMode.UPDATE, frame));
        }
        for (int index = 0; index < frame.lines().size(); index++) {
            if (previous == null || index >= previous.lines().size()
                    || previous.lines().size() != frame.lines().size()
                    || !sameLine(previous.lines().get(index), frame.lines().get(index))) {
                user.sendPacketSilently(new WrapperPlayServerUpdateScore(lineName(index),
                        WrapperPlayServerUpdateScore.Action.CREATE_OR_UPDATE_ITEM, OBJECTIVE,
                        frame.lines().size() - index, frame.lines().get(index).text(), frame.lines().get(index).format()));
            }
        }
        if (previous != null) {
            for (int index = frame.lines().size(); index < previous.lines().size(); index++) {
                user.sendPacketSilently(new WrapperPlayServerResetScore(lineName(index), OBJECTIVE));
            }
        }
        state.sent = frame;
    }

    private static WrapperPlayServerScoreboardObjective objective(
            WrapperPlayServerScoreboardObjective.ObjectiveMode mode, BoardFrame frame) {
        return new WrapperPlayServerScoreboardObjective(OBJECTIVE, mode, frame.title(),
                WrapperPlayServerScoreboardObjective.RenderType.INTEGER,
                frame.hideNumbers() ? ScoreFormat.blankScore() : null);
    }

    private static void removeObjective(User user) {
        user.sendPacketSilently(new WrapperPlayServerScoreboardObjective(OBJECTIVE,
                WrapperPlayServerScoreboardObjective.ObjectiveMode.REMOVE, Component.empty(),
                WrapperPlayServerScoreboardObjective.RenderType.INTEGER));
    }

    private static boolean sidebarSlot(int position) {
        return position == 1 || position >= 3 && position <= 18;
    }

    private static String lineName(int index) {
        return "gloss_line_" + index;
    }

    private static boolean sameLine(Line previous, Line current) {
        if (!previous.text().equals(current.text())) {
            return false;
        }
        if (previous.format() == current.format()) {
            return true;
        }
        if (previous.format() instanceof FixedScoreFormat before && current.format() instanceof FixedScoreFormat after) {
            return before.getValue().equals(after.getValue());
        }
        if (previous.format() instanceof StyledScoreFormat before && current.format() instanceof StyledScoreFormat after) {
            return before.getStyle().equals(after.getStyle());
        }
        return false;
    }

    public record Line(Component text, ScoreFormat format) {
        public Line {
            Objects.requireNonNull(text);
        }
    }

    private record BoardFrame(Component title, List<Line> lines, boolean hideNumbers) {
    }

    private static final class BoardState {
        private final User user;
        private final Map<Integer, String> backendDisplays = new HashMap<>();
        private BoardFrame desired;
        private BoardFrame sent;
        private boolean reset;
        private boolean unsupportedReported;
        private boolean failureReported;

        private BoardState(User user) {
            this.user = user;
        }
    }
}
