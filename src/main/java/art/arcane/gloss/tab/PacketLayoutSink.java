package art.arcane.gloss.tab;

import art.arcane.gloss.util.common.PacketUtils;
import art.arcane.gloss.util.common.TextUtils;
import com.github.retrooper.packetevents.PacketEvents;
import com.github.retrooper.packetevents.protocol.player.TextureProperty;
import com.github.retrooper.packetevents.protocol.player.User;
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

/**
 * Writes a layout as player-info packets. Fake entries are strictly client side: nothing here
 * touches the server roster, so {@code /list}, proxies and {@code Bukkit.getOnlinePlayers()} are
 * unchanged.
 */
public final class PacketLayoutSink implements TablistLayoutService.LayoutSink {
    private static final String TEXTURES = "textures";

    @Override
    public void addSlots(Player viewer, List<TablistLayoutService.SlotEntry> entries) {
        List<WrapperPlayServerPlayerInfoUpdate.PlayerInfo> infos = new ArrayList<>(entries.size());
        for (TablistLayoutService.SlotEntry entry : entries) {
            infos.add(info(entry));
        }
        send(viewer, new WrapperPlayServerPlayerInfoUpdate(EnumSet.of(
            WrapperPlayServerPlayerInfoUpdate.Action.ADD_PLAYER,
            WrapperPlayServerPlayerInfoUpdate.Action.UPDATE_LISTED,
            WrapperPlayServerPlayerInfoUpdate.Action.UPDATE_DISPLAY_NAME,
            WrapperPlayServerPlayerInfoUpdate.Action.UPDATE_LIST_ORDER,
            WrapperPlayServerPlayerInfoUpdate.Action.UPDATE_LATENCY), infos));
    }

    @Override
    public void updateTexts(Player viewer, List<TablistLayoutService.SlotEntry> entries) {
        List<WrapperPlayServerPlayerInfoUpdate.PlayerInfo> infos = new ArrayList<>(entries.size());
        for (TablistLayoutService.SlotEntry entry : entries) {
            infos.add(info(entry));
        }
        send(viewer, new WrapperPlayServerPlayerInfoUpdate(
            WrapperPlayServerPlayerInfoUpdate.Action.UPDATE_DISPLAY_NAME, infos));
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
            if (subject.equals(viewer.getUniqueId())) {
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
        send(viewer, new WrapperPlayServerPlayerInfoUpdate(
            WrapperPlayServerPlayerInfoUpdate.Action.UPDATE_LISTED, infos));
    }

    private static WrapperPlayServerPlayerInfoUpdate.PlayerInfo info(TablistLayoutService.SlotEntry entry) {
        UserProfile profile = new UserProfile(entry.id(), entry.name(), textures(entry.skin()));
        WrapperPlayServerPlayerInfoUpdate.PlayerInfo info =
            new WrapperPlayServerPlayerInfoUpdate.PlayerInfo(profile);
        info.setListed(true);
        info.setLatency(entry.ping());
        info.setListOrder(entry.listOrder());
        info.setDisplayName(TextUtils.parse(entry.text()));
        return info;
    }

    /**
     * A skin named after an online player copies that account's texture property; anything else
     * leaves the entry with the default skin its deterministic id already selects.
     */
    private static List<TextureProperty> textures(String skin) {
        if (skin == null || PacketEvents.getAPI() == null) {
            return List.of();
        }
        Player source = Bukkit.getPlayerExact(skin);
        if (source == null) {
            return List.of();
        }
        User user = PacketEvents.getAPI().getPlayerManager().getUser(source);
        if (user == null || user.getProfile() == null) {
            return List.of();
        }
        List<TextureProperty> properties = new ArrayList<>(1);
        for (TextureProperty property : user.getProfile().getTextureProperties()) {
            if (TEXTURES.equals(property.getName())) {
                properties.add(property);
            }
        }
        return properties;
    }

    private static UserProfile profileOf(UUID subject) {
        Player player = Bukkit.getPlayer(subject);
        return new UserProfile(subject, player == null ? null : player.getName());
    }

    private static void send(Player viewer, PacketWrapper<?> packet) {
        if (PacketEvents.getAPI() == null) {
            return;
        }
        PacketUtils.send(viewer, packet);
    }
}
