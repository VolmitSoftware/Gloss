package art.arcane.gloss.util.common;

import art.arcane.gloss.service.GlossTelemetry;
import com.github.retrooper.packetevents.PacketEvents;
import com.github.retrooper.packetevents.manager.player.PlayerManager;
import com.github.retrooper.packetevents.util.Vector3d;
import com.github.retrooper.packetevents.wrapper.PacketWrapper;
import org.bukkit.entity.Player;
import org.bukkit.util.Vector;

import java.util.Collection;
import java.util.Collections;

public final class PacketUtils {

  public static void sendOne(Player player, PacketWrapper<?> packet) {
    if (player == null) return;
    GlossTelemetry.countPackets(1L);
    PacketEvents.getAPI().getPlayerManager().sendPacket(player, packet);
  }

  public static void send(Player player, PacketWrapper<?> packet) {
    if (player == null) return;
    send(player, Collections.singletonList(packet));
  }

  public static void send(Player player, Collection<PacketWrapper<?>> packets) {
    if (player == null) return;
    send(Collections.singletonList(player), packets);
  }

  public static void send(Collection<Player> players, Collection<PacketWrapper<?>> packets) {
    GlossTelemetry.countPackets((long) players.size() * packets.size());
    PlayerManager playerManager = PacketEvents.getAPI().getPlayerManager();
    players.forEach(player ->
        packets.forEach(packet ->
            playerManager.sendPacket(player, packet)
        )
    );
  }

  public static Vector vector(Vector3d vector) {
    return new Vector(vector.getX(), vector.getY(), vector.getZ());
  }

  public static Vector3d vector3d(Vector vector) {
    return new Vector3d(vector.getX(), vector.getY(), vector.getZ());
  }
}
