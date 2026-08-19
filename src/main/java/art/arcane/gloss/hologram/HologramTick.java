package art.arcane.gloss.hologram;

import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.entity.Player;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

final class HologramTick {
    record Viewer(Player player, UUID id, double x, double y, double z) {
    }

    private final Map<String, List<Viewer>> captured;

    HologramTick() {
        this.captured = new HashMap<>(4);
    }

    List<Viewer> viewers(World world) {
        String name = world.getName();
        List<Viewer> existing = captured.get(name);
        if (existing != null) {
            return existing;
        }

        List<Viewer> viewers = capture(world);
        captured.put(name, viewers);
        return viewers;
    }

    /**
     * The world's captured viewers, or null when this pass never looked at that world. Read-only:
     * unlike {@link #viewers(World)} it never captures, so a reader outside the drive pass cannot
     * mutate a snapshot that has already been published.
     */
    List<Viewer> captured(World world) {
        return captured.get(world.getName());
    }

    private static List<Viewer> capture(World world) {
        List<Player> players = world.getPlayers();
        List<Viewer> viewers = new ArrayList<>(players.size());
        for (Player player : players) {
            Location location = player.getLocation();
            if (location.getWorld() != world) {
                continue;
            }

            viewers.add(new Viewer(player, player.getUniqueId(), location.getX(), location.getY(), location.getZ()));
        }

        return viewers;
    }
}
