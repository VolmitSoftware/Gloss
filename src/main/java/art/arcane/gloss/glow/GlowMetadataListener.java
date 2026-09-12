package art.arcane.gloss.glow;

import com.github.retrooper.packetevents.event.PacketListenerAbstract;
import com.github.retrooper.packetevents.event.PacketListenerPriority;
import com.github.retrooper.packetevents.event.PacketSendEvent;
import com.github.retrooper.packetevents.protocol.entity.data.EntityData;
import com.github.retrooper.packetevents.protocol.packettype.PacketType;
import com.github.retrooper.packetevents.wrapper.play.server.WrapperPlayServerEntityMetadata;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.UUID;

/**
 * Keeps a per-viewer glow alive. The server sends its own metadata for an entity whenever it
 * sneaks, burns or sprints, and that packet carries the whole flags byte with the glow bit clear;
 * this puts the bit back for the pairs that are tagged. Metadata is one of the highest volume
 * packets there is, so a viewer with nothing tagged is answered before the packet is decoded.
 */
public final class GlowMetadataListener extends PacketListenerAbstract {
    private final GlowService service;

    public GlowMetadataListener(GlowService service) {
        super(PacketListenerPriority.HIGH);
        this.service = Objects.requireNonNull(service, "service");
    }

    @Override
    public void onPacketSend(PacketSendEvent event) {
        if (event.getPacketType() != PacketType.Play.Server.ENTITY_METADATA
            || event.getUser() == null || event.getUser().getUUID() == null) {
            return;
        }
        UUID viewerId = event.getUser().getUUID();
        if (!service.hasTags(viewerId)) {
            return;
        }
        WrapperPlayServerEntityMetadata packet = new WrapperPlayServerEntityMetadata(event);
        if (!service.tagged(viewerId, packet.getEntityId())) {
            return;
        }
        List<EntityData<?>> metadata = new ArrayList<>(packet.getEntityMetadata());
        if (service.reassert(viewerId, packet.getEntityId(), metadata)) {
            packet.setEntityMetadata(metadata);
            event.markForReEncode(true);
        }
    }
}
