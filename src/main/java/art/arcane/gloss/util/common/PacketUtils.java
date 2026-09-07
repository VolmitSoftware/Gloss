package art.arcane.gloss.util.common;

import art.arcane.gloss.service.GlossTelemetry;
import com.github.retrooper.packetevents.PacketEvents;
import com.github.retrooper.packetevents.PacketEventsAPI;
import com.github.retrooper.packetevents.manager.player.PlayerManager;
import com.github.retrooper.packetevents.manager.protocol.ProtocolManager;
import com.github.retrooper.packetevents.netty.buffer.ByteBufHelper;
import com.github.retrooper.packetevents.netty.channel.ChannelHelper;
import com.github.retrooper.packetevents.util.Vector3d;
import com.github.retrooper.packetevents.wrapper.PacketWrapper;
import org.bukkit.entity.Player;
import org.bukkit.util.Vector;

import java.util.Collection;
import java.util.Collections;
import java.util.function.BooleanSupplier;

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

  /**
   * Writes every packet to each recipient's channel and flushes that channel once, instead of one
   * write-and-flush per packet. Each packet is still encoded per recipient; use
   * {@link #broadcast(Collection, PacketWrapper, BooleanSupplier)} when one packet goes to many.
   */
  public static void send(Collection<Player> players, Collection<PacketWrapper<?>> packets) {
    if (players.isEmpty() || packets.isEmpty()) return;
    PacketEventsAPI<?> api = PacketEvents.getAPI();
    PlayerManager playerManager = api.getPlayerManager();
    ProtocolManager protocolManager = api.getProtocolManager();
    long written = 0L;
    try {
      for (Player player : players) {
        Object channel = playerManager.getChannel(player);
        if (channel == null) continue;
        for (PacketWrapper<?> packet : packets) {
          protocolManager.writePacket(channel, packet);
          written++;
        }
        ChannelHelper.flush(channel);
      }
    } finally {
      GlossTelemetry.countPackets(written);
    }
  }

  /**
   * Encodes one packet once and hands a retained duplicate of that buffer to every recipient's
   * channel. This is the fan-out path: {@code send} would repeat the wrapper's {@code write()} —
   * for text updates an Adventure component to NBT encode — once per viewer.
   *
   * <p>{@code live} is polled before each recipient's write so a fan-out whose source was retired
   * mid-flight stops instead of resurrecting stale text on a reused entity id. On a proxy the
   * packet id is rewritten per user's client version, so the shared buffer is not valid there and
   * the per-recipient encode is used instead.</p>
   */
  public static void broadcast(Collection<Player> players, PacketWrapper<?> packet, BooleanSupplier live) {
    if (players.isEmpty() || !live.getAsBoolean()) return;
    PacketEventsAPI<?> api = PacketEvents.getAPI();
    PlayerManager playerManager = api.getPlayerManager();
    ProtocolManager protocolManager = api.getProtocolManager();
    boolean proxy = api.getInjector().isProxy();
    Object[] buffers = null;
    long written = 0L;
    try {
      for (Player player : players) {
        if (!live.getAsBoolean()) return;
        Object channel = playerManager.getChannel(player);
        if (channel == null) continue;
        if (proxy) {
          protocolManager.writePacket(channel, packet);
          ChannelHelper.flush(channel);
          written++;
          continue;
        }
        if (buffers == null) {
          buffers = protocolManager.transformWrappers(packet, channel, true);
        }
        for (Object buffer : buffers) {
          writeDuplicate(protocolManager, channel, buffer);
        }
        ChannelHelper.flush(channel);
        written++;
      }
    } finally {
      if (buffers != null) {
        for (Object buffer : buffers) {
          ByteBufHelper.release(buffer);
        }
      }
      GlossTelemetry.countPackets(written);
    }
  }

  /**
   * The duplicate is retained before {@code writePacket} takes ownership of it, so a throw between
   * those two points would strand it and keep the shared encode off refCnt 0 — a pooled-buffer leak.
   */
  private static void writeDuplicate(ProtocolManager protocolManager, Object channel, Object buffer) {
    Object duplicate = ByteBufHelper.retainedDuplicate(buffer);
    try {
      protocolManager.writePacket(channel, duplicate);
    } catch (RuntimeException failure) {
      ByteBufHelper.release(duplicate);
      throw failure;
    }
  }

  public static Vector vector(Vector3d vector) {
    return new Vector(vector.getX(), vector.getY(), vector.getZ());
  }

  public static Vector3d vector3d(Vector vector) {
    return new Vector3d(vector.getX(), vector.getY(), vector.getZ());
  }
}
