package art.arcane.gloss.velocity;

import art.arcane.gloss.proxy.OwnershipProtocol;
import com.velocitypowered.api.event.connection.PluginMessageEvent;
import com.velocitypowered.api.proxy.Player;
import com.velocitypowered.api.proxy.ProxyServer;
import com.velocitypowered.api.proxy.ServerConnection;
import com.velocitypowered.api.proxy.messages.MinecraftChannelIdentifier;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.mockito.ArgumentCaptor;
import org.slf4j.Logger;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.Optional;
import java.util.OptionalInt;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.RETURNS_DEEP_STUBS;
import static org.mockito.Mockito.clearInvocations;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class ProxyOwnershipTest {
    private static final byte[] KEY = "test-ownership-key".getBytes(StandardCharsets.UTF_8);
    private static final MinecraftChannelIdentifier CHANNEL = MinecraftChannelIdentifier.from(OwnershipProtocol.CHANNEL);

    @TempDir
    Path directory;
    private ProxyServer proxy;
    private Player player;
    private ServerConnection connection;
    private ProxyOwnership ownership;

    @BeforeEach
    void prepare() {
        proxy = mock(ProxyServer.class, RETURNS_DEEP_STUBS);
        player = mock(Player.class);
        connection = mock(ServerConnection.class);
        when(player.getUniqueId()).thenReturn(UUID.randomUUID());
        when(player.isActive()).thenReturn(true);
        when(player.getCurrentServer()).thenReturn(Optional.of(connection));
        when(connection.getPlayer()).thenReturn(player);
        when(connection.sendPluginMessage(eq(CHANNEL), any(byte[].class))).thenReturn(true);
        ownership = new ProxyOwnership(proxy, KEY);
    }

    @Test
    void repliesToBackendAndRestoresOnlyNewlyOwnedFeatures() {
        byte[] response = exchange(7);
        assertEquals(7, ownership.handle(new PluginMessageEvent(connection, player, CHANNEL, acknowledgement(response, false)), 7));
        assertEquals(0, ownership.handle(new PluginMessageEvent(connection, player, CHANNEL, acknowledgement(response, false)), 7));
        response = exchange(7);
        assertEquals(0, ownership.handle(new PluginMessageEvent(connection, player, CHANNEL, acknowledgement(response, false)), 7));
        response = exchange(1);
        assertEquals(0, ownership.handle(new PluginMessageEvent(connection, player, CHANNEL, acknowledgement(response, false)), 1));
        response = exchange(7);
        assertEquals(6, ownership.handle(new PluginMessageEvent(connection, player, CHANNEL, acknowledgement(response, false)), 7));
        ownership.forget(player.getUniqueId());
        response = exchange(7);
        assertEquals(7, ownership.handle(new PluginMessageEvent(connection, player, CHANNEL, acknowledgement(response, false)), 7));
    }

    @Test
    void consumesClientSpoofingAndDoesNotSignIt() {
        PluginMessageEvent forged = new PluginMessageEvent(player, connection, CHANNEL,
            OwnershipProtocol.encodeRequest(OwnershipProtocol.createRequest(player.getUniqueId())));
        assertEquals(0, ownership.handle(forged, 7));
        assertFalse(forged.getResult().isAllowed());
        verify(connection, never()).sendPluginMessage(any(), any(byte[].class));
    }

    @Test
    void rejectsWrongPlayersAndStaleBackendConnections() {
        PluginMessageEvent wrongPlayer = new PluginMessageEvent(connection, player, CHANNEL,
            OwnershipProtocol.encodeRequest(OwnershipProtocol.createRequest(UUID.randomUUID())));
        assertEquals(0, ownership.handle(wrongPlayer, 7));
        assertFalse(wrongPlayer.getResult().isAllowed());
        when(player.getCurrentServer()).thenReturn(Optional.of(mock(ServerConnection.class)));
        PluginMessageEvent stale = new PluginMessageEvent(connection, player, CHANNEL,
            OwnershipProtocol.encodeRequest(OwnershipProtocol.createRequest(player.getUniqueId())));
        assertEquals(0, ownership.handle(stale, 7));
        assertFalse(stale.getResult().isAllowed());
        verify(connection, never()).sendPluginMessage(any(), any(byte[].class));
    }

    @Test
    void missingKeyStillConsumesChannelAndKeyChangesInvalidateAcknowledgements() {
        byte[] response = exchange(7);
        ownership.setKey(null);
        PluginMessageEvent acknowledgement = new PluginMessageEvent(connection, player, CHANNEL, acknowledgement(response, false));
        assertEquals(0, ownership.handle(acknowledgement, 7));
        assertFalse(acknowledgement.getResult().isAllowed());
        ownership.setKey(KEY);
        assertEquals(0, ownership.handle(acknowledgement, 7));
        response = exchange(7);
        assertEquals(7, ownership.handle(new PluginMessageEvent(connection, player, CHANNEL, acknowledgement(response, false)), 7));
    }

    @Test
    void malformedAcknowledgementDoesNotRestoreFeatures() {
        byte[] response = exchange(7);
        response[response.length - 1] ^= 1;
        PluginMessageEvent invalid = new PluginMessageEvent(connection, player, CHANNEL, acknowledgement(response, false));
        assertEquals(0, ownership.handle(invalid, 7));
        assertFalse(invalid.getResult().isAllowed());
    }

    @Test
    void explicitKeysUseExactBytesAndRejectShortFiles() throws IOException {
        Path file = directory.resolve("proxy-ownership.key");
        Files.write(file, new byte[31]);
        ProxyOwnership.KeySource source = new ProxyOwnership.KeySource(proxy, mock(Logger.class), directory);
        assertThrows(IOException.class, () -> ProxyOwnership.loadKey(source));
        byte[] bytes = "a-test-key-containing-at-least-32-bytes\n".getBytes(StandardCharsets.UTF_8);
        Files.write(file, bytes);
        assertArrayEquals(bytes, ProxyOwnership.loadKey(source));
    }

    @Test
    void backendOwnershipResetReassertsUnchangedFeatures() {
        byte[] response = exchange(7);
        assertEquals(7, ownership.handle(new PluginMessageEvent(connection, player, CHANNEL, acknowledgement(response, false)), 7));
        response = exchange(7);
        assertEquals(7, ownership.handle(new PluginMessageEvent(connection, player, CHANNEL, acknowledgement(response, true)), 7));
    }

    @Test
    void rejectsAcknowledgementsWithInvalidFlagsOrWithoutFlag() {
        byte[] response = exchange(7);
        assertEquals(0, ownership.handle(new PluginMessageEvent(connection, player, CHANNEL, response), 7));
        byte[] invalid = acknowledgement(response, false);
        invalid[invalid.length - 1] = 2;
        assertEquals(0, ownership.handle(new PluginMessageEvent(connection, player, CHANNEL, invalid), 7));
        assertEquals(7, ownership.handle(new PluginMessageEvent(connection, player, CHANNEL, acknowledgement(response, false)), 7));
    }

    private byte[] acknowledgement(byte[] response, boolean changed) {
        byte[] acknowledgement = Arrays.copyOf(response, response.length + 1);
        acknowledgement[response.length] = (byte) (changed ? 1 : 0);
        return acknowledgement;
    }

    private byte[] exchange(int mask) {
        clearInvocations(connection);
        OwnershipProtocol.Request request = OwnershipProtocol.createRequest(player.getUniqueId());
        PluginMessageEvent event = new PluginMessageEvent(connection, player, CHANNEL, OwnershipProtocol.encodeRequest(request));
        assertEquals(0, ownership.handle(event, mask));
        assertFalse(event.getResult().isAllowed());
        ArgumentCaptor<byte[]> response = ArgumentCaptor.forClass(byte[].class);
        verify(connection).sendPluginMessage(eq(CHANNEL), response.capture());
        assertEquals(OptionalInt.of(mask), OwnershipProtocol.verifyReply(response.getValue(), request, KEY, System.currentTimeMillis()));
        return response.getValue();
    }
}
