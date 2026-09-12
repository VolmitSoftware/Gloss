package art.arcane.gloss.board;

import com.github.retrooper.packetevents.event.PacketListenerAbstract;
import com.github.retrooper.packetevents.event.PacketListenerPriority;
import com.github.retrooper.packetevents.event.PacketSendEvent;
import com.github.retrooper.packetevents.protocol.packettype.PacketType;
import com.github.retrooper.packetevents.protocol.score.ScoreFormat;
import com.github.retrooper.packetevents.wrapper.play.server.WrapperPlayServerUpdateScore;

import java.util.UUID;
import java.util.function.Function;

/**
 * Writes the authored value column onto the sidebar's own outbound score packets. Nothing extra
 * leaves the server: a line with no authored format passes through untouched.
 */
public final class ScoreFormatDecorator extends PacketListenerAbstract {
    private final Function<UUID, BoardFormatIndex> index;

    public ScoreFormatDecorator(Function<UUID, BoardFormatIndex> index) {
        super(PacketListenerPriority.NORMAL);
        this.index = index;
    }

    @Override
    public void onPacketSend(PacketSendEvent event) {
        if (event.getPacketType() != PacketType.Play.Server.UPDATE_SCORE || event.getUser() == null
            || event.getUser().getUUID() == null) {
            return;
        }
        if (decorate(event.getUser().getUUID(), new WrapperPlayServerUpdateScore(event))) {
            event.markForReEncode(true);
        }
    }

    boolean decorate(UUID viewerId, WrapperPlayServerUpdateScore packet) {
        BoardFormatIndex published = index.apply(viewerId);
        if (published == null) {
            return false;
        }
        ScoreFormat format = published.format(packet.getObjectiveName(), packet.getEntityName());
        if (format == null) {
            return false;
        }
        packet.setScoreFormat(format);
        return true;
    }
}
