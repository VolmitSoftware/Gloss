package art.arcane.gloss.velocity;

import com.github.retrooper.packetevents.PacketEvents;
import com.github.retrooper.packetevents.PacketEventsAPI;
import com.github.retrooper.packetevents.netty.channel.ChannelOperator;
import com.github.retrooper.packetevents.protocol.ConnectionState;
import com.github.retrooper.packetevents.protocol.player.User;
import com.velocitypowered.api.proxy.Player;
import com.velocitypowered.api.proxy.ProxyServer;
import com.velocitypowered.api.proxy.ServerConnection;
import com.velocitypowered.api.proxy.player.TabList;
import com.velocitypowered.api.proxy.player.TabListEntry;
import com.velocitypowered.api.proxy.server.ServerInfo;
import com.velocitypowered.api.util.GameProfile;
import net.kyori.adventure.text.Component;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.slf4j.Logger;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.RETURNS_DEEP_STUBS;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.clearInvocations;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

final class ProxyTablistsTest {
    @TempDir
    Path directory;
    private final Map<UUID, TabListEntry> entries = new HashMap<>();
    private final List<Runnable> queued = new ArrayList<>();
    private PacketEventsAPI<?> previousApi;
    private User user;
    private boolean defer;
    private ProxyServer proxy;
    private Player viewer;
    private Player remote;
    private TabList tab;
    private ProxyTablists service;
    private ProxyDocuments.Snapshot snapshot;

    @BeforeEach
    void prepare() throws IOException {
        ProxyDocuments.seed(directory);
        Files.writeString(directory.resolve("tablist.json"), """
            {"schemaVersion":2,"show":true,
             "headerFooter":{"enabled":false},
             "listNames":{"enabled":true,"presentation":{"format":"$player"}}}
            """);
        snapshot = ProxyDocuments.load(directory);
        proxy = mock(ProxyServer.class);
        tab = mock(TabList.class);
        viewer = player("Viewer", "hub", 25566);
        remote = player("Remote", "games", 25567);
        when(viewer.getTabList()).thenReturn(tab);
        when(proxy.getAllPlayers()).thenReturn(List.of(remote));
        when(proxy.getPlayer(remote.getUniqueId())).thenReturn(Optional.of(remote));
        when(proxy.getPlayer(viewer.getUniqueId())).thenReturn(Optional.of(viewer));
        when(tab.getEntry(any(UUID.class))).thenAnswer(invocation -> Optional.ofNullable(entries.get(invocation.getArgument(0))));
        when(tab.buildEntry(any(GameProfile.class), any(), anyInt(), anyInt(), any(), anyBoolean(), anyInt(), anyBoolean()))
            .thenAnswer(invocation -> {
                TabListEntry entry = mock(TabListEntry.class);
                when(entry.getProfile()).thenReturn(invocation.getArgument(0));
                when(entry.getDisplayNameComponent()).thenReturn(Optional.empty());
                return entry;
            });
        doAnswer(invocation -> {
            TabListEntry entry = invocation.getArgument(0);
            entries.put(entry.getProfile().getId(), entry);
            return null;
        }).when(tab).addEntry(any(TabListEntry.class));
        when(tab.removeEntry(any(UUID.class))).thenAnswer(invocation -> Optional.ofNullable(entries.remove(invocation.getArgument(0))));
        previousApi = PacketEvents.getAPI();
        PacketEventsAPI<?> api = mock(PacketEventsAPI.class, RETURNS_DEEP_STUBS);
        PacketEvents.setAPI(api);
        user = mock(User.class);
        Object channel = new Object();
        when(user.getChannel()).thenReturn(channel);
        when(user.getEncoderState()).thenReturn(ConnectionState.PLAY);
        when(api.getPlayerManager().getUser(viewer)).thenReturn(user);
        ChannelOperator operator = api.getNettyManager().getChannelOperator();
        when(operator.isOpen(channel)).thenReturn(true);
        doAnswer(invocation -> {
            Runnable action = invocation.getArgument(1);
            if (defer) {
                queued.add(action);
            } else {
                action.run();
            }
            return null;
        }).when(operator).runInEventLoop(eq(channel), any(Runnable.class));
        service = new ProxyTablists(proxy, new ProxyText(proxy), mock(Logger.class));
    }

    @AfterEach
    void cleanup() {
        service.close();
        drain();
        PacketEvents.setAPI(previousApi);
    }

    @Test
    void disabledHeaderNeverClearsBackendHeader() {
        service.render(viewer, snapshot);
        service.clear(viewer);
        verify(viewer, never()).sendPlayerListHeaderAndFooter(any(Component.class), any(Component.class));
    }

    @Test
    void remoteEntryAddedByGlossIsRemovedOnClear() {
        service.render(viewer, snapshot);
        assertTrue(entries.containsKey(remote.getUniqueId()));
        service.clear(viewer);
        assertTrue(entries.isEmpty());
        verify(tab).removeEntry(remote.getUniqueId());
    }

    @Test
    void backendReplacementBeforeClearIsRetainedUntouched() {
        service.render(viewer, snapshot);
        TabListEntry backend = mock(TabListEntry.class);
        entries.put(remote.getUniqueId(), backend);
        service.clear(viewer);
        assertSame(backend, entries.get(remote.getUniqueId()));
        verify(tab, never()).removeEntry(remote.getUniqueId());
        verify(backend, never()).setDisplayName(any());
    }

    @Test
    void backendReplacementBeforeRefreshIsRestoredWithoutRemoval() {
        service.render(viewer, snapshot);
        TabListEntry backend = mock(TabListEntry.class);
        Component originalName = Component.text("Backend name");
        when(backend.getDisplayNameComponent()).thenReturn(Optional.of(originalName));
        when(backend.getListOrder()).thenReturn(8);
        entries.put(remote.getUniqueId(), backend);
        service.render(viewer, snapshot);
        service.clear(viewer);
        assertSame(backend, entries.get(remote.getUniqueId()));
        verify(tab, never()).removeEntry(remote.getUniqueId());
        verify(backend).setDisplayName(originalName);
        verify(backend, never()).setListOrder(anyInt());
    }

    @Test
    void disabledNamesAndSortingReleaseOnceThenLeaveBackendChangesAlone() throws IOException {
        TabListEntry backend = mock(TabListEntry.class);
        Component originalName = Component.text("Backend name");
        when(backend.getDisplayNameComponent()).thenReturn(Optional.of(originalName));
        when(backend.getListOrder()).thenReturn(8);
        entries.put(remote.getUniqueId(), backend);
        Files.writeString(directory.resolve("tablist.json"), """
            {"schemaVersion":2,"show":true,
             "listNames":{"enabled":true,"presentation":{"format":"Gloss name"}},
             "sort":{"enabled":true,"weight":"42"}}
            """);
        service.render(viewer, ProxyDocuments.load(directory));
        clearInvocations(backend);
        Files.writeString(directory.resolve("tablist.json"), """
            {"schemaVersion":2,"show":true,
             "listNames":{"enabled":false},"sort":{"enabled":false}}
            """);
        ProxyDocuments.Snapshot disabled = ProxyDocuments.load(directory);
        service.render(viewer, disabled);
        verify(backend).setDisplayName(originalName);
        verify(backend).setListOrder(8);
        clearInvocations(backend);
        when(backend.getDisplayNameComponent()).thenReturn(Optional.of(Component.text("New backend name")));
        when(backend.getListOrder()).thenReturn(11);
        service.render(viewer, disabled);
        service.clear(viewer);
        verify(backend, never()).setDisplayName(any());
        verify(backend, never()).setListOrder(anyInt());
    }

    @Test
    void protocolStateIsCheckedWhenQueuedRenderActuallyRuns() {
        defer = true;
        service.render(viewer, snapshot);
        when(user.getEncoderState()).thenReturn(ConnectionState.CONFIGURATION);
        drain();
        assertTrue(entries.isEmpty());
        when(user.getEncoderState()).thenReturn(ConnectionState.PLAY);
        service.render(viewer, snapshot);
        drain();
        assertTrue(entries.containsKey(remote.getUniqueId()));
    }

    @Test
    void closePreventsQueuedRenderFromRestoringClearedEntries() {
        service.render(viewer, snapshot);
        defer = true;
        service.render(viewer, snapshot);
        service.close();
        drain();
        assertTrue(entries.isEmpty());
        service.render(viewer, snapshot);
        drain();
        assertTrue(entries.isEmpty());
    }

    @Test
    void forgetRunsAfterQueuedRenderAndRemovesOwnedRemoteEntries() {
        defer = true;
        service.render(viewer, snapshot);
        service.forget(viewer.getUniqueId());
        drain();
        assertTrue(entries.isEmpty());
    }

    private void drain() {
        while (!queued.isEmpty()) {
            queued.removeFirst().run();
        }
    }

    private static Player player(String name, String server, int port) {
        Player player = mock(Player.class);
        UUID id = UUID.randomUUID();
        when(player.getUniqueId()).thenReturn(id);
        when(player.getUsername()).thenReturn(name);
        when(player.isActive()).thenReturn(true);
        when(player.getGameProfile()).thenReturn(new GameProfile(id, name, List.of()));
        ServerConnection connection = mock(ServerConnection.class);
        when(connection.getServerInfo()).thenReturn(new ServerInfo(server, new InetSocketAddress("127.0.0.1", port)));
        when(player.getCurrentServer()).thenReturn(Optional.of(connection));
        return player;
    }
}
