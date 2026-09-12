package art.arcane.gloss.dialog;

import com.github.retrooper.packetevents.PacketEvents;
import com.github.retrooper.packetevents.event.PacketListenerAbstract;
import com.github.retrooper.packetevents.event.PacketListenerCommon;
import com.github.retrooper.packetevents.event.PacketListenerPriority;
import com.github.retrooper.packetevents.event.PacketReceiveEvent;
import com.github.retrooper.packetevents.protocol.nbt.NBT;
import com.github.retrooper.packetevents.protocol.nbt.NBTCompound;
import com.github.retrooper.packetevents.protocol.packettype.PacketType;
import com.github.retrooper.packetevents.wrapper.play.client.WrapperPlayClientCustomClickAction;
import org.bukkit.entity.Player;

import java.util.Objects;

/**
 * Routes {@code gloss:dialog} custom click actions into {@link DialogService}. Every other custom
 * click action on the server belongs to someone else and is left alone.
 */
public final class DialogResponseListener extends PacketListenerAbstract {

    private final DialogService service;
    private PacketListenerCommon registration;

    private DialogResponseListener(DialogService service) {
        super(PacketListenerPriority.NORMAL);
        this.service = Objects.requireNonNull(service, "service");
    }

    /** @return the installed listener, or null when packetevents is not running */
    static DialogResponseListener install(DialogService service) {
        if (PacketEvents.getAPI() == null || PacketEvents.getAPI().getEventManager() == null) {
            return null;
        }
        DialogResponseListener listener = new DialogResponseListener(service);
        listener.registration = PacketEvents.getAPI().getEventManager().registerListener(listener);
        return listener;
    }

    void uninstall() {
        if (registration != null && PacketEvents.getAPI() != null
            && PacketEvents.getAPI().getEventManager() != null) {
            PacketEvents.getAPI().getEventManager().unregisterListener(registration);
        }
        registration = null;
    }

    @Override
    public void onPacketReceive(PacketReceiveEvent event) {
        if (event.getPacketType() != PacketType.Play.Client.CUSTOM_CLICK_ACTION) {
            return;
        }
        WrapperPlayClientCustomClickAction action = new WrapperPlayClientCustomClickAction(event);
        if (!DialogEncoder.ACTION_NAMESPACE.equals(action.getId().getNamespace())
            || !DialogEncoder.ACTION_KEY.equals(action.getId().getKey())) {
            return;
        }
        NBT payload = action.getPayload();
        if (!(payload instanceof NBTCompound compound) || !(event.getPlayer() instanceof Player viewer)) {
            return;
        }
        service.onResponse(viewer, compound);
    }
}
