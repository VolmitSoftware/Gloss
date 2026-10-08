package art.arcane.gloss.tab;

import com.github.retrooper.packetevents.PacketEvents;
import com.github.retrooper.packetevents.PacketEventsAPI;
import com.github.retrooper.packetevents.injector.ChannelInjector;
import com.github.retrooper.packetevents.manager.player.PlayerManager;
import com.github.retrooper.packetevents.manager.protocol.ProtocolManager;
import com.github.retrooper.packetevents.manager.server.ServerManager;
import com.github.retrooper.packetevents.manager.server.ServerVersion;
import com.github.retrooper.packetevents.netty.NettyManager;
import com.github.retrooper.packetevents.protocol.player.TextureProperty;
import com.github.retrooper.packetevents.protocol.player.User;
import com.github.retrooper.packetevents.protocol.player.UserProfile;
import com.github.retrooper.packetevents.wrapper.play.server.WrapperPlayServerPlayerInfoUpdate;
import org.bukkit.Bukkit;
import org.bukkit.Server;
import org.bukkit.entity.Player;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;

class PacketLayoutSinkTest {
    @Test
    void playerCellCopiesSignedTexturesAndCurrentLatency() throws ReflectiveOperationException {
        UUID playerId = UUID.randomUUID();
        TextureProperty texture = new TextureProperty("textures", "texture-value", "texture-signature");
        UserProfile source = new UserProfile(playerId, "ListedPlayer", List.of(texture));
        Player player = (Player) Proxy.newProxyInstance(Player.class.getClassLoader(),
            new Class<?>[]{Player.class}, (proxy, method, arguments) -> switch (method.getName()) {
                case "getName" -> source.getName();
                case "getUniqueId" -> playerId;
                default -> throw new UnsupportedOperationException(method.getName());
            });
        Field serverField = Bukkit.class.getDeclaredField("server");
        serverField.setAccessible(true);
        Object previousServer = serverField.get(null);
        PacketEventsAPI<?> previousApi = PacketEvents.getAPI();
        try {
            serverField.set(null, Proxy.newProxyInstance(Server.class.getClassLoader(),
                new Class<?>[]{Server.class}, (proxy, method, arguments) -> {
                    if (method.getName().equals("getPlayerExact")) {
                        assertEquals("ListedPlayer", arguments[0]);
                        return player;
                    }
                    throw new UnsupportedOperationException(method.getName());
                }));
            PacketEvents.setAPI(new ProfileApi(source, player));
            TablistLayoutService.SlotEntry cell = new TablistLayoutService.SlotEntry(
                TablistLayoutRuntime.slotId(0), TablistLayoutRuntime.slotName(0), 100_000,
                "[Staff] ListedPlayer", "ListedPlayer", 73, true, new TablistLayoutDefinition.Skin(texture.getValue(), texture.getSignature()));
            Method infoMethod = PacketLayoutSink.class.getDeclaredMethod("info", TablistLayoutService.SlotEntry.class);
            infoMethod.setAccessible(true);

            WrapperPlayServerPlayerInfoUpdate.PlayerInfo info =
                (WrapperPlayServerPlayerInfoUpdate.PlayerInfo) infoMethod.invoke(new PacketLayoutSink(), cell);

            assertEquals(cell.id(), info.getProfileId());
            assertEquals(cell.name(), info.getGameProfile().getName());
            assertEquals(73, info.getLatency());
            assertEquals(100_000, info.getListOrder());
            assertEquals(1, info.getGameProfile().getTextureProperties().size());
            assertEquals(texture.getValue(), info.getGameProfile().getTextureProperties().getFirst().getValue());
            assertEquals("texture-signature", info.getGameProfile().getTextureProperties().getFirst().getSignature());
        } finally {
            PacketEvents.setAPI(previousApi);
            serverField.set(null, previousServer);
        }
    }

    private static final class ProfileApi extends PacketEventsAPI<Object> {
        private final UserProfile profile;
        private final Player player;

        private ProfileApi(UserProfile profile, Player player) {
            this.profile = profile;
            this.player = player;
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
            return (PlayerManager) Proxy.newProxyInstance(PlayerManager.class.getClassLoader(),
                new Class<?>[]{PlayerManager.class}, (proxy, method, arguments) -> {
                    if (method.getName().equals("getUser")) {
                        assertSame(player, arguments[0]);
                        Class<?> allocatorType = Class.forName("sun.misc.Unsafe");
                        Field allocatorField = allocatorType.getDeclaredField("theUnsafe");
                        allocatorField.setAccessible(true);
                        Object allocator = allocatorField.get(null);
                        User user = (User) allocatorType.getMethod("allocateInstance", Class.class)
                            .invoke(allocator, User.class);
                        Field profileField = User.class.getDeclaredField("profile");
                        profileField.setAccessible(true);
                        profileField.set(user, profile);
                        return user;
                    }
                    throw new UnsupportedOperationException(method.getName());
                });
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
}
