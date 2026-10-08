package art.arcane.gloss.tab;

import art.arcane.gloss.util.common.PacketUtils;
import art.arcane.gloss.service.GlossTelemetry;
import art.arcane.gloss.util.common.TextUtils;
import com.github.retrooper.packetevents.PacketEvents;
import com.github.retrooper.packetevents.manager.server.ServerVersion;
import com.github.retrooper.packetevents.protocol.player.ClientVersion;
import com.github.retrooper.packetevents.protocol.player.User;
import com.github.retrooper.packetevents.protocol.player.TextureProperty;
import com.github.retrooper.packetevents.protocol.player.UserProfile;
import com.github.retrooper.packetevents.wrapper.PacketWrapper;
import com.github.retrooper.packetevents.wrapper.play.server.WrapperPlayServerPlayerInfoRemove;
import com.github.retrooper.packetevents.wrapper.play.server.WrapperPlayServerPlayerInfoUpdate;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;

import java.util.ArrayList;
import java.util.Collection;
import java.util.EnumSet;
import java.util.List;
import java.util.UUID;
import java.util.function.Function;

/**
 * Writes a layout as player-info packets. Fake entries are strictly client side: nothing here
 * touches the server roster, so {@code /list}, proxies and {@code Bukkit.getOnlinePlayers()} are
 * unchanged.
 */
public final class PacketLayoutSink implements TablistLayoutService.LayoutSink {
    @Override
    public void addSlots(Player viewer, List<TablistLayoutService.SlotEntry> entries) {
        List<WrapperPlayServerPlayerInfoUpdate.PlayerInfo> infos = new ArrayList<>(entries.size());
        for (TablistLayoutService.SlotEntry entry : entries) {
            infos.add(info(entry));
        }
        EnumSet<WrapperPlayServerPlayerInfoUpdate.Action> actions = EnumSet.of(
            WrapperPlayServerPlayerInfoUpdate.Action.ADD_PLAYER,
            WrapperPlayServerPlayerInfoUpdate.Action.UPDATE_LISTED,
            WrapperPlayServerPlayerInfoUpdate.Action.UPDATE_DISPLAY_NAME,
            WrapperPlayServerPlayerInfoUpdate.Action.UPDATE_LIST_ORDER,
            WrapperPlayServerPlayerInfoUpdate.Action.UPDATE_LATENCY);
        includeHatAction(viewer, actions);
        send(viewer, new WrapperPlayServerPlayerInfoUpdate(actions, infos));
    }

    @Override
    public void updateSlots(Player viewer, List<TablistLayoutService.SlotEntry> entries) {
        List<WrapperPlayServerPlayerInfoUpdate.PlayerInfo> infos = new ArrayList<>(entries.size());
        for (TablistLayoutService.SlotEntry entry : entries) {
            infos.add(info(entry));
        }
        EnumSet<WrapperPlayServerPlayerInfoUpdate.Action> actions = EnumSet.of(
            WrapperPlayServerPlayerInfoUpdate.Action.UPDATE_DISPLAY_NAME, WrapperPlayServerPlayerInfoUpdate.Action.UPDATE_LATENCY);
        includeHatAction(viewer, actions);
        send(viewer, new WrapperPlayServerPlayerInfoUpdate(actions, infos));
    }

    @Override
    public void removeSlots(Player viewer, List<String> slotNames) {
        List<UUID> ids = new ArrayList<>(slotNames.size());
        for (String slotName : slotNames) {
            ids.add(TablistLayoutRuntime.slotId(
                Integer.parseInt(slotName.substring(TablistLayoutRuntime.SLOT_NAME_PREFIX.length()))));
        }
        send(viewer, new WrapperPlayServerPlayerInfoRemove(ids));
    }

    @Override
    public void listReal(Player viewer, Collection<UUID> subjects, boolean listed) {
        List<WrapperPlayServerPlayerInfoUpdate.PlayerInfo> infos = new ArrayList<>(subjects.size());
        for (UUID subject : subjects) {
            Player player = Bukkit.getPlayer(subject);
            if (listed && (player == null || !viewer.canSee(player))) {
                continue;
            }
            WrapperPlayServerPlayerInfoUpdate.PlayerInfo info =
                new WrapperPlayServerPlayerInfoUpdate.PlayerInfo(profileOf(subject));
            info.setListed(listed);
            infos.add(info);
        }
        if (infos.isEmpty()) {
            return;
        }
        if (PacketEvents.getAPI() != null) {
            PacketEvents.getAPI().getPlayerManager().sendPacketSilently(viewer,
                new WrapperPlayServerPlayerInfoUpdate(WrapperPlayServerPlayerInfoUpdate.Action.UPDATE_LISTED, infos));
            GlossTelemetry.countPackets(1L);
        }
    }

    private WrapperPlayServerPlayerInfoUpdate.PlayerInfo info(TablistLayoutService.SlotEntry entry) {
        List<TextureProperty> properties = entry.texture() == null
            ? List.of()
            : List.of(new TextureProperty("textures", entry.texture().value(), entry.texture().signature()));
        UserProfile profile = new UserProfile(entry.id(), entry.name(), properties);
        WrapperPlayServerPlayerInfoUpdate.PlayerInfo info =
            new WrapperPlayServerPlayerInfoUpdate.PlayerInfo(profile);
        info.setListed(true);
        info.setShowHat(entry.hat());
        info.setLatency(entry.ping());
        info.setListOrder(entry.listOrder());
        info.setDisplayName(TextUtils.parse(entry.text()));
        return info;
    }

    private static void includeHatAction(Player viewer, EnumSet<WrapperPlayServerPlayerInfoUpdate.Action> actions) {
        if (PacketEvents.getAPI() == null || !PacketEvents.getAPI().getServerManager().getVersion()
            .isNewerThanOrEquals(ServerVersion.V_1_21_4)) {
            return;
        }
        User user = PacketEvents.getAPI().getPlayerManager().getUser(viewer);
        if (user != null && user.getClientVersion().isNewerThanOrEquals(ClientVersion.V_1_21_4)) {
            actions.add(WrapperPlayServerPlayerInfoUpdate.Action.UPDATE_HAT);
        }
    }

    private static UserProfile profileOf(UUID subject) {
        return new UserProfile(subject, null);
    }

    private static void send(Player viewer, PacketWrapper<?> packet) {
        if (PacketEvents.getAPI() == null) {
            return;
        }
        PacketUtils.send(viewer, packet);
    }
}
