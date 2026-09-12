package art.arcane.gloss.tab;

import com.github.retrooper.packetevents.event.PacketListenerAbstract;
import com.github.retrooper.packetevents.event.PacketListenerPriority;
import com.github.retrooper.packetevents.event.PacketSendEvent;
import com.github.retrooper.packetevents.protocol.packettype.PacketType;
import com.github.retrooper.packetevents.wrapper.play.server.WrapperPlayServerPlayerInfoUpdate;

import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.function.Consumer;
import java.util.function.Predicate;

/**
 * A late joiner is added to everyone's list by the server itself. For a viewer that already has a
 * layout, that entry is unlisted on the way out so the real player never flashes into the grid.
 */
public final class PlayerInfoRewriteListener extends PacketListenerAbstract {
    /** Where an unlisted entry is remembered, so whoever put it there gets it back. */
    @FunctionalInterface
    public interface UnlistedRecorder {
        void recordUnlisted(UUID viewerId, Set<UUID> entries);
    }

    private final Predicate<UUID> hasLayout;
    private final UnlistedRecorder recorder;

    public PlayerInfoRewriteListener(Predicate<UUID> hasLayout, UnlistedRecorder recorder) {
        super(PacketListenerPriority.NORMAL);
        this.hasLayout = hasLayout;
        this.recorder = recorder;
    }

    @Override
    public void onPacketSend(PacketSendEvent event) {
        if (event.getPacketType() != PacketType.Play.Server.PLAYER_INFO_UPDATE || event.getUser() == null
            || event.getUser().getUUID() == null || !hasLayout.test(event.getUser().getUUID())) {
            return;
        }
        WrapperPlayServerPlayerInfoUpdate packet = new WrapperPlayServerPlayerInfoUpdate(event);
        if (!packet.getActions().contains(WrapperPlayServerPlayerInfoUpdate.Action.ADD_PLAYER)) {
            return;
        }
        Set<UUID> unlisted = new LinkedHashSet<>();
        if (unlistRealEntries(packet.getEntries(), unlisted::add)) {
            packet.getActions().add(WrapperPlayServerPlayerInfoUpdate.Action.UPDATE_LISTED);
            event.markForReEncode(true);
            recorder.recordUnlisted(event.getUser().getUUID(), unlisted);
        }
    }

    /**
     * Unlists everything that is not one of our own grid slots. Every entry taken away is handed to
     * the recorder: another plugin's fake entries are unlisted here too, and the layout's restore
     * only re-lists what it knows about.
     */
    static boolean unlistRealEntries(List<WrapperPlayServerPlayerInfoUpdate.PlayerInfo> entries,
                                     Consumer<UUID> unlisted) {
        boolean changed = false;
        for (WrapperPlayServerPlayerInfoUpdate.PlayerInfo entry : entries) {
            String name = entry.getGameProfile() == null ? null : entry.getGameProfile().getName();
            if (name != null && name.startsWith(TablistLayoutRuntime.SLOT_NAME_PREFIX)) {
                continue;
            }
            if (entry.isListed()) {
                entry.setListed(false);
                changed = true;
                if (entry.getProfileId() != null) {
                    unlisted.accept(entry.getProfileId());
                }
            }
        }
        return changed;
    }
}
