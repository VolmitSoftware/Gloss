package art.arcane.gloss.board;

import art.arcane.gloss.service.GlossTelemetry;
import com.github.retrooper.packetevents.PacketEvents;
import com.github.retrooper.packetevents.event.PacketListenerAbstract;
import com.github.retrooper.packetevents.event.PacketListenerPriority;
import com.github.retrooper.packetevents.event.PacketSendEvent;
import com.github.retrooper.packetevents.netty.channel.ChannelHelper;
import com.github.retrooper.packetevents.protocol.packettype.PacketType;
import com.github.retrooper.packetevents.protocol.score.FixedScoreFormat;
import com.github.retrooper.packetevents.protocol.score.ScoreFormat;
import com.github.retrooper.packetevents.protocol.score.StyledScoreFormat;
import com.github.retrooper.packetevents.wrapper.play.server.WrapperPlayServerResetScore;
import com.github.retrooper.packetevents.wrapper.play.server.WrapperPlayServerScoreboardObjective;
import com.github.retrooper.packetevents.wrapper.play.server.WrapperPlayServerUpdateScore;
import net.kyori.adventure.text.Component;
import org.bukkit.entity.Player;

import java.util.HashMap;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Consumer;

public final class ScoreFormatDecorator extends PacketListenerAbstract {
    private final Map<UUID, ViewerState> viewers = new ConcurrentHashMap<>();

    public ScoreFormatDecorator() {
        super(PacketListenerPriority.NORMAL);
    }

    public void publish(Player player, BoardFormatIndex index) {
        UUID viewerId = player.getUniqueId();
        Object channel = PacketEvents.getAPI().getPlayerManager().getChannel(player);
        if (channel == null) {
            return;
        }
        ViewerState existing = viewers.get(viewerId);
        ViewerState state = existing == null
            ? viewers.computeIfAbsent(viewerId, ignored -> new ViewerState(index)) : existing;
        if (sameIndex(state.requested, index)) {
            return;
        }
        state.requested = index;
        ChannelHelper.runInEventLoop(channel, () -> publishOnChannel(viewerId, state, channel, index));
    }

    public void forget(UUID viewerId) {
        viewers.remove(viewerId);
    }

    public void clear() {
        viewers.clear();
    }

    @Override
    public void onPacketSend(PacketSendEvent event) {
        if (event.getPacketType() != PacketType.Play.Server.UPDATE_SCORE
            && event.getPacketType() != PacketType.Play.Server.RESET_SCORE
            && event.getPacketType() != PacketType.Play.Server.SCOREBOARD_OBJECTIVE) {
            return;
        }
        if (event.isCancelled() || event.getUser() == null || event.getUser().getUUID() == null) {
            return;
        }
        UUID viewerId = event.getUser().getUUID();
        if (!viewers.containsKey(viewerId)) {
            return;
        }
        if (event.getPacketType() == PacketType.Play.Server.UPDATE_SCORE) {
            if (decorate(viewerId, new WrapperPlayServerUpdateScore(event))) {
                event.markForReEncode(true);
            }
        } else if (event.getPacketType() == PacketType.Play.Server.RESET_SCORE) {
            WrapperPlayServerResetScore packet = new WrapperPlayServerResetScore(event);
            removeScore(viewerId, packet.getObjective(), packet.getTargetName());
        } else if (event.getPacketType() == PacketType.Play.Server.SCOREBOARD_OBJECTIVE) {
            WrapperPlayServerScoreboardObjective packet = new WrapperPlayServerScoreboardObjective(event);
            if (packet.getMode() == WrapperPlayServerScoreboardObjective.ObjectiveMode.REMOVE) {
                removeObjective(viewerId, packet.getName());
            }
        }
    }

    void publish(UUID viewerId, BoardFormatIndex index, Consumer<WrapperPlayServerUpdateScore> sink) {
        ViewerState existing = viewers.get(viewerId);
        ViewerState state = existing == null
            ? viewers.computeIfAbsent(viewerId, ignored -> new ViewerState(index)) : existing;
        update(state, index, sink);
    }

    boolean decorate(UUID viewerId, WrapperPlayServerUpdateScore packet) {
        ViewerState state = viewers.get(viewerId);
        if (state == null) {
            return false;
        }
        synchronized (state) {
            if (!state.index.objectiveName().equals(packet.getObjectiveName())) {
                return false;
            }
            if (packet.getAction() == WrapperPlayServerUpdateScore.Action.REMOVE_ITEM) {
                state.rows.remove(packet.getEntityName());
                return false;
            }
            if (packet.getValue().isEmpty()) {
                return false;
            }
            ScoreFormat format = state.index.format(packet.getObjectiveName(), packet.getEntityName());
            state.rows.put(packet.getEntityName(), new Row(packet.getValue().get(),
                packet.getEntityDisplayName(), format));
            boolean changed = !sameFormat(format, packet.getScoreFormat());
            packet.setScoreFormat(format);
            return changed;
        }
    }

    void removeScore(UUID viewerId, String objective, String entry) {
        ViewerState state = viewers.get(viewerId);
        if (state == null) {
            return;
        }
        synchronized (state) {
            if (objective == null || state.index.objectiveName().equals(objective)) {
                state.rows.remove(entry);
            }
        }
    }

    void removeObjective(UUID viewerId, String objective) {
        ViewerState state = viewers.get(viewerId);
        if (state == null) {
            return;
        }
        synchronized (state) {
            if (state.index.objectiveName().equals(objective)) {
                state.rows.clear();
            }
        }
    }

    private void publishOnChannel(UUID viewerId, ViewerState state, Object channel, BoardFormatIndex index) {
        if (viewers.get(viewerId) != state || !ChannelHelper.isOpen(channel)) {
            return;
        }
        try {
            if (update(state, index, packet -> {
                PacketEvents.getAPI().getProtocolManager().writePacketSilently(channel, packet);
                GlossTelemetry.countPackets(1L);
            })) {
                ChannelHelper.flush(channel);
            }
        } catch (RuntimeException failure) {
            state.requested = null;
            throw failure;
        }
    }

    private static boolean update(ViewerState state, BoardFormatIndex index,
                               Consumer<WrapperPlayServerUpdateScore> sink) {
        synchronized (state) {
            if (!state.index.objectiveName().equals(index.objectiveName())) {
                state.rows.clear();
            }
            state.index = index;
            boolean changed = false;
            for (Map.Entry<String, Row> entry : state.rows.entrySet()) {
                Row previous = entry.getValue();
                ScoreFormat format = index.format(index.objectiveName(), entry.getKey());
                if (sameFormat(previous.format(), format)) {
                    continue;
                }
                sink.accept(new WrapperPlayServerUpdateScore(entry.getKey(),
                    WrapperPlayServerUpdateScore.Action.CREATE_OR_UPDATE_ITEM, index.objectiveName(),
                    previous.score(), previous.displayName(), format));
                entry.setValue(new Row(previous.score(), previous.displayName(), format));
                changed = true;
            }
            return changed;
        }
    }

    private static boolean sameIndex(BoardFormatIndex left, BoardFormatIndex right) {
        if (left == null || !left.objectiveName().equals(right.objectiveName())
            || left.hideNumbers() != right.hideNumbers() || left.formats().size() != right.formats().size()) {
            return false;
        }
        for (Map.Entry<String, ScoreFormat> entry : left.formats().entrySet()) {
            if (!sameFormat(entry.getValue(), right.formats().get(entry.getKey()))) {
                return false;
            }
        }
        return true;
    }

    private static boolean sameFormat(ScoreFormat left, ScoreFormat right) {
        if (left == right) {
            return true;
        }
        if (left == null || right == null || left.getType() != right.getType()) {
            return false;
        }
        if (left instanceof FixedScoreFormat fixed && right instanceof FixedScoreFormat other) {
            return Objects.equals(fixed.getValue(), other.getValue());
        }
        if (left instanceof StyledScoreFormat styled && right instanceof StyledScoreFormat other) {
            return Objects.equals(styled.getStyle(), other.getStyle());
        }
        return true;
    }

    private static final class ViewerState {
        private final Map<String, Row> rows = new HashMap<>();
        private BoardFormatIndex index;
        private volatile BoardFormatIndex requested;

        private ViewerState(BoardFormatIndex index) {
            this.index = index;
        }
    }

    private record Row(int score, Component displayName, ScoreFormat format) {
    }
}
