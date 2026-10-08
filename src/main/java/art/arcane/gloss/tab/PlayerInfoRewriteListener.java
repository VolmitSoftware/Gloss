package art.arcane.gloss.tab;

import com.github.retrooper.packetevents.event.PacketListenerAbstract;
import com.github.retrooper.packetevents.event.PacketListenerPriority;
import com.github.retrooper.packetevents.event.PacketSendEvent;
import com.github.retrooper.packetevents.protocol.packettype.PacketType;
import com.github.retrooper.packetevents.wrapper.play.server.WrapperPlayServerPlayerInfoUpdate;
import com.github.retrooper.packetevents.wrapper.play.server.WrapperPlayServerPlayerInfoRemove;

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
        void recordUnlisted(UUID viewerId, Set<UUID> listed, Set<UUID> hidden);
    }

    private final Predicate<UUID> hasLayout;
    private final UnlistedRecorder recorder;
    private final Predicate<UUID> subjects;

    public PlayerInfoRewriteListener(Ownership ownership) {
        super(PacketListenerPriority.HIGHEST);
        this.hasLayout = ownership.hasLayout();
        this.recorder = ownership.recorder();
        this.subjects = ownership.subjects();
    }

    public record Ownership(Predicate<UUID> hasLayout, Predicate<UUID> subjects, UnlistedRecorder recorder) {
    }

    @Override
    public void onPacketSend(PacketSendEvent event) {
        if (event.isCancelled() || event.getUser() == null || event.getUser().getUUID() == null) {
            return;
        }
        UUID viewer = event.getUser().getUUID();
        if (event.getPacketType() == PacketType.Play.Server.PLAYER_INFO_REMOVE) {
            WrapperPlayServerPlayerInfoRemove removed = new WrapperPlayServerPlayerInfoRemove(event);
            Set<UUID> hidden = new LinkedHashSet<>();
            for (UUID id : removed.getProfileIds()) {
                if (subjects.test(id)) {
                    hidden.add(id);
                }
            }
            recorder.recordUnlisted(viewer, Set.of(), hidden);
            return;
        }
        if (event.getPacketType() != PacketType.Play.Server.PLAYER_INFO_UPDATE) {
            return;
        }
        WrapperPlayServerPlayerInfoUpdate packet = new WrapperPlayServerPlayerInfoUpdate(event);
        if (!packet.getActions().contains(WrapperPlayServerPlayerInfoUpdate.Action.UPDATE_LISTED)) {
            return;
        }
        Set<UUID> hidden = new LinkedHashSet<>();
        for (WrapperPlayServerPlayerInfoUpdate.PlayerInfo entry : packet.getEntries()) {
            if (!entry.isListed() && entry.getProfileId() != null && subjects.test(entry.getProfileId())) {
                hidden.add(entry.getProfileId());
            }
        }
        Set<UUID> unlisted = new LinkedHashSet<>();
        if (!hasLayout.test(viewer)) {
            for (WrapperPlayServerPlayerInfoUpdate.PlayerInfo entry : packet.getEntries()) {
                if (entry.isListed() && entry.getProfileId() != null && subjects.test(entry.getProfileId())) {
                    unlisted.add(entry.getProfileId());
                }
            }
        } else if (unlistRealEntries(packet.getEntries(), subjects, unlisted::add)) {
            packet.getActions().add(WrapperPlayServerPlayerInfoUpdate.Action.UPDATE_LISTED);
            event.markForReEncode(true);
        }
        recorder.recordUnlisted(viewer, unlisted, hidden);
    }

    /**
     * Unlists known real subjects while preserving other plugins' fake entries and our own slots.
     * The recorder tracks which entries Gloss may restore when the layout is released.
     */
    static boolean unlistRealEntries(List<WrapperPlayServerPlayerInfoUpdate.PlayerInfo> entries,
                                     Predicate<UUID> subjects, Consumer<UUID> unlisted) {
        boolean changed = false;
        for (WrapperPlayServerPlayerInfoUpdate.PlayerInfo entry : entries) {
            String name = entry.getGameProfile() == null ? null : entry.getGameProfile().getName();
            if (name != null && name.startsWith(TablistLayoutDefinition.SLOT_NAME_PREFIX)) {
                continue;
            }
            if (entry.isListed() && entry.getProfileId() != null && subjects.test(entry.getProfileId())) {
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
