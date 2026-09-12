package art.arcane.gloss.interaction;

import com.github.retrooper.packetevents.event.PacketListenerAbstract;
import com.github.retrooper.packetevents.event.PacketListenerPriority;
import com.github.retrooper.packetevents.event.PacketReceiveEvent;
import com.github.retrooper.packetevents.protocol.packettype.PacketType;
import com.github.retrooper.packetevents.protocol.player.InteractionHand;
import com.github.retrooper.packetevents.wrapper.play.client.WrapperPlayClientInteractEntity;
import org.bukkit.entity.Player;

final class InteractionListener extends PacketListenerAbstract {
    private final InteractionHitboxService service;

    InteractionListener(InteractionHitboxService service) {
        super(PacketListenerPriority.HIGH);
        this.service = service;
    }

    @Override
    public void onPacketReceive(PacketReceiveEvent event) {
        if (event.getPacketType() != PacketType.Play.Client.INTERACT_ENTITY) {
            return;
        }
        Player player = event.getPlayer();
        if (player == null) {
            return;
        }
        WrapperPlayClientInteractEntity packet = new WrapperPlayClientInteractEntity(event);
        if (packet.getHand() == InteractionHand.OFF_HAND || service.target(packet.getEntityId()) == null) {
            return;
        }
        boolean sneaking = packet.isSneaking().orElse(player.isSneaking());
        if (service.handleInteract(player, packet.getEntityId(), packet.getAction(), sneaking, packet.getTarget())) {
            event.setCancelled(true);
        }
    }
}
