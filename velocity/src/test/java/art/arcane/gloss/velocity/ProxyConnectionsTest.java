package art.arcane.gloss.velocity;

import com.velocitypowered.api.event.connection.DisconnectEvent;
import com.velocitypowered.api.proxy.Player;
import com.velocitypowered.api.proxy.ProxyServer;
import com.velocitypowered.api.proxy.ServerConnection;
import com.velocitypowered.api.proxy.server.ServerInfo;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.serializer.legacy.LegacyComponentSerializer;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.slf4j.Logger;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

final class ProxyConnectionsTest {
    @TempDir
    Path directory;
    private final Map<UUID, List<String>> delivered = new LinkedHashMap<>();
    private ProxyServer proxy;
    private ProxyConnections connections;

    @BeforeEach
    void prepare() throws IOException {
        ProxyDocuments.seed(directory);
        proxy = mock(ProxyServer.class);
        connections = new ProxyConnections(proxy, new ProxyText(proxy), mock(Logger.class));
    }

    @Test
    void joinReachesEveryNetworkPlayerIncludingTheSubject() throws IOException {
        document("""
            {"schemaVersion":1,
             "join":{"presentation":{"text":"&a+ &f$player &7joined &f$to"}}}
            """);
        Player subject = player("Alex", "hub");
        Player elsewhere = player("Robin", "games");
        online(subject, elsewhere);

        connections.joined(subject, load());

        assertEquals(List.of("§a+ §fAlex §7joined §fhub"), messages(subject));
        assertEquals(List.of("§a+ §fAlex §7joined §fhub"), messages(elsewhere));
    }

    @Test
    void switchOnTheServerAudienceReachesTheOldAndNewBackendsOnly() throws IOException {
        document("""
            {"schemaVersion":1,
             "switch":{"audience":"server","presentation":{"text":"$player: $from -> $to"}}}
            """);
        Player subject = player("Alex", "games");
        Player onOldServer = player("Robin", "hub");
        Player onNewServer = player("Sam", "games");
        Player elsewhere = player("Kai", "arena");
        online(subject, onOldServer, onNewServer, elsewhere);

        connections.switched(subject, "hub", load());

        assertEquals(List.of("Alex: hub -> games"), messages(subject));
        assertEquals(List.of("Alex: hub -> games"), messages(onOldServer));
        assertEquals(List.of("Alex: hub -> games"), messages(onNewServer));
        assertTrue(messages(elsewhere).isEmpty());
    }

    @Test
    void leaveSkipsTheSubjectAndUsesTheLastKnownBackend() throws IOException {
        document("""
            {"schemaVersion":1,
             "leave":{"audience":"server","presentation":{"text":"&c- &f$player &7left &f$from"}}}
            """);
        Player subject = player("Alex", "hub");
        Player onLastServer = player("Robin", "hub");
        Player elsewhere = player("Kai", "arena");
        online(subject, onLastServer, elsewhere);
        connections.joined(subject, load());
        when(subject.getCurrentServer()).thenReturn(Optional.empty());

        connections.left(new DisconnectEvent(subject, DisconnectEvent.LoginStatus.SUCCESSFUL_LOGIN), load());

        assertTrue(messages(subject).isEmpty());
        assertEquals(List.of("§c- §fAlex §7left §fhub"), messages(onLastServer));
        assertTrue(messages(elsewhere).isEmpty());
    }

    @Test
    void onlySuccessfulLoginsProduceALeaveMessage() throws IOException {
        document("""
            {"schemaVersion":1,"leave":{"presentation":{"text":"&c- &f$player"}}}
            """);
        Player subject = player("Alex", "hub");
        Player watcher = player("Robin", "hub");
        online(subject, watcher);

        connections.left(new DisconnectEvent(subject, DisconnectEvent.LoginStatus.CANCELLED_BY_USER), load());
        connections.left(new DisconnectEvent(subject, DisconnectEvent.LoginStatus.CONFLICTING_LOGIN), load());
        connections.left(new DisconnectEvent(subject, DisconnectEvent.LoginStatus.PRE_SERVER_JOIN), load());
        assertTrue(messages(watcher).isEmpty());

        connections.left(new DisconnectEvent(subject, DisconnectEvent.LoginStatus.SUCCESSFUL_LOGIN), load());
        assertEquals(List.of("§c- §fAlex"), messages(watcher));
    }

    @Test
    void theFeatureSwitchTheDocumentGateAndTheSectionSwitchEachSilenceTheBroadcast() throws IOException {
        Player subject = player("Alex", "hub");
        Player watcher = player("Robin", "hub");
        online(subject, watcher);

        document("""
            {"schemaVersion":1,"join":{"presentation":{"text":"&ajoined"}}}
            """);
        Files.writeString(directory.resolve("proxy.json"), """
            {"schemaVersion":1,"connections":{"enabled":false}}
            """);
        connections.joined(subject, load());
        assertTrue(messages(watcher).isEmpty());

        Files.writeString(directory.resolve("proxy.json"), "{\"schemaVersion\":1}");
        document("""
            {"schemaVersion":1,"show":false,"join":{"presentation":{"text":"&ajoined"}}}
            """);
        connections.joined(subject, load());
        assertTrue(messages(watcher).isEmpty());

        document("""
            {"schemaVersion":1,"join":{"enabled":false,"presentation":{"text":"&ajoined"}}}
            """);
        connections.joined(subject, load());
        assertTrue(messages(watcher).isEmpty());

        document("""
            {"schemaVersion":1,"join":{"show":"false","presentation":{"text":"&ajoined"}}}
            """);
        connections.joined(subject, load());
        assertTrue(messages(watcher).isEmpty());

        document("""
            {"schemaVersion":1,"leave":{"presentation":{"text":"&aleft"}}}
            """);
        connections.joined(subject, load());
        assertTrue(messages(watcher).isEmpty());
    }

    @Test
    void variantsAreChosenPerRecipient() throws IOException {
        document("""
            {"schemaVersion":1,
             "join":{"presentation":{"text":"&7$player joined"},
              "variants":[{"priority":1,"when":"hasPermission('viewer', 'gloss.notify.plain')",
                           "presentation":{"text":"&8$player joined"}},
                          {"priority":10,"when":"hasPermission('viewer', 'gloss.notify.staff')",
                           "presentation":{"text":"&6$player joined from &f$to"}}]}}
            """);
        Player subject = player("Alex", "hub");
        Player staff = player("Robin", "hub");
        Player plain = player("Sam", "hub");
        when(staff.hasPermission("gloss.notify.staff")).thenReturn(true);
        when(staff.hasPermission("gloss.notify.plain")).thenReturn(true);
        when(plain.hasPermission("gloss.notify.plain")).thenReturn(true);
        online(subject, staff, plain);

        connections.joined(subject, load());

        assertEquals(List.of("§6Alex joined from §fhub"), messages(staff));
        assertEquals(List.of("§8Alex joined"), messages(plain));
        assertEquals(List.of("§7Alex joined"), messages(subject));
    }

    private ProxyDocuments.Snapshot load() throws IOException {
        return ProxyDocuments.load(directory);
    }

    private void document(String json) throws IOException {
        Files.writeString(directory.resolve("connections.json"), json);
        delivered.clear();
    }

    private void online(Player... players) {
        when(proxy.getAllPlayers()).thenReturn(List.of(players));
    }

    private List<String> messages(Player player) {
        return delivered.getOrDefault(player.getUniqueId(), List.of());
    }

    private Player player(String name, String server) {
        Optional<ServerConnection> connection = serverConnection(server);
        Player player = mock(Player.class);
        UUID id = UUID.randomUUID();
        when(player.getUniqueId()).thenReturn(id);
        when(player.getUsername()).thenReturn(name);
        when(player.getCurrentServer()).thenReturn(connection);
        when(player.hasPermission(anyString())).thenReturn(false);
        doAnswer(invocation -> {
            Component message = invocation.getArgument(0);
            delivered.computeIfAbsent(id, ignored -> new ArrayList<>())
                .add(LegacyComponentSerializer.legacySection().serialize(message));
            return null;
        }).when(player).sendMessage(any(Component.class));
        return player;
    }

    private static Optional<ServerConnection> serverConnection(String name) {
        if (name == null) {
            return Optional.empty();
        }
        ServerConnection connection = mock(ServerConnection.class);
        when(connection.getServerInfo()).thenReturn(new ServerInfo(name, new InetSocketAddress("127.0.0.1", 25565)));
        return Optional.of(connection);
    }
}
