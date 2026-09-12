package art.arcane.gloss.util.common;

import com.github.retrooper.packetevents.PacketEvents;
import com.github.retrooper.packetevents.PacketEventsAPI;
import com.github.retrooper.packetevents.injector.ChannelInjector;
import com.github.retrooper.packetevents.manager.player.PlayerManager;
import com.github.retrooper.packetevents.manager.protocol.ProtocolManager;
import com.github.retrooper.packetevents.manager.server.ServerManager;
import com.github.retrooper.packetevents.manager.server.ServerVersion;
import com.github.retrooper.packetevents.netty.NettyManager;

/**
 * The smallest API a packet wrapper needs to be constructed off a server: a version to encode
 * against. Nothing here sends anything; tests capture through their own sinks.
 */
public final class StubPacketEventsApi extends PacketEventsAPI<Object> {
    public static void install() {
        PacketEvents.setAPI(new StubPacketEventsApi());
    }

    public static void clear() {
        PacketEvents.setAPI(null);
    }

    @Override
    public boolean isLoaded() {
        return true;
    }

    @Override
    public void init() {
    }

    @Override
    public boolean isInitialized() {
        return true;
    }

    @Override
    public boolean isTerminated() {
        return false;
    }

    @Override
    public Object getPlugin() {
        return this;
    }

    @Override
    public ServerManager getServerManager() {
        return () -> ServerVersion.V_26_1_2;
    }

    @Override
    public ProtocolManager getProtocolManager() {
        return null;
    }

    @Override
    public PlayerManager getPlayerManager() {
        return null;
    }

    @Override
    public NettyManager getNettyManager() {
        return null;
    }

    @Override
    public ChannelInjector getInjector() {
        return null;
    }
}
