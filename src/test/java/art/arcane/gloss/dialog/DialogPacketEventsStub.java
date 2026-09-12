package art.arcane.gloss.dialog;

import com.github.retrooper.packetevents.PacketEvents;
import com.github.retrooper.packetevents.PacketEventsAPI;
import com.github.retrooper.packetevents.injector.ChannelInjector;
import com.github.retrooper.packetevents.manager.player.PlayerManager;
import com.github.retrooper.packetevents.manager.protocol.ProtocolManager;
import com.github.retrooper.packetevents.manager.server.ServerManager;
import com.github.retrooper.packetevents.manager.server.ServerVersion;
import com.github.retrooper.packetevents.netty.NettyManager;
import com.github.retrooper.packetevents.protocol.player.ClientVersion;
import com.github.retrooper.packetevents.wrapper.PacketWrapper;

/**
 * A packetevents API that knows one thing: which protocol version to encode for. Dialog encoding
 * touches no channel and no buffer, so a dummy wrapper on a pinned version is the whole harness.
 */
final class DialogPacketEventsStub extends PacketEventsAPI<Object> {

    static final ClientVersion VERSION = ClientVersion.V_26_2;

    private DialogPacketEventsStub() {
    }

    static void install() {
        PacketEvents.setAPI(new DialogPacketEventsStub());
    }

    static PacketWrapper<?> wrapper() {
        return PacketWrapper.createDummyWrapper(VERSION);
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
        return () -> ServerVersion.V_26_2;
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
