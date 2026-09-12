package art.arcane.gloss.chat;

import org.bukkit.Location;
import org.bukkit.entity.Player;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;

/**
 * Who hears a message. The sender always hears themselves; everyone else has to be in the
 * channel's scope and able to see the sender, so a hidden player's chat stays hidden.
 */
public final class ChatAudience {
    private ChatAudience() {
    }

    public static List<Player> viewers(ChannelRuntime channel, Player sender,
                                       Collection<? extends Player> online) {
        List<Player> viewers = new ArrayList<>(online.size());
        for (Player viewer : online) {
            if (viewer.getUniqueId().equals(sender.getUniqueId()) || hears(channel, sender, viewer)) {
                viewers.add(viewer);
            }
        }
        return List.copyOf(viewers);
    }

    private static boolean hears(ChannelRuntime channel, Player sender, Player viewer) {
        if (!viewer.canSee(sender)) {
            return false;
        }
        ChannelDoc.Channel settings = channel.doc().channel();
        return switch (settings.scope()) {
            case GLOBAL -> true;
            case WORLD -> sameWorld(sender, viewer);
            case RADIUS -> withinRadius(sender, viewer, settings.radius());
            case PERMISSION -> viewer.hasPermission(settings.permission());
            case DIRECT -> false;
        };
    }

    private static boolean sameWorld(Player sender, Player viewer) {
        return sender.getWorld().equals(viewer.getWorld());
    }

    private static boolean withinRadius(Player sender, Player viewer, int radius) {
        if (!sameWorld(sender, viewer)) {
            return false;
        }
        Location origin = sender.getLocation();
        Location target = viewer.getLocation();
        double dx = origin.getX() - target.getX();
        double dy = origin.getY() - target.getY();
        double dz = origin.getZ() - target.getZ();
        return dx * dx + dy * dy + dz * dz <= (double) radius * radius;
    }
}
