package art.arcane.gloss.velocity;

import com.velocitypowered.api.network.ProtocolVersion;
import com.velocitypowered.api.proxy.Player;
import com.velocitypowered.api.proxy.ProxyServer;
import com.velocitypowered.api.util.ServerLink;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.mockito.ArgumentCaptor;

import java.io.IOException;
import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

final class ProxyServerLinksTest {
    @TempDir
    Path directory;

    @Test
    void publishesTypedAndLabelledLinksInDocumentOrder() throws IOException {
        Files.writeString(seeded(), """
            {"schemaVersion":1,"show":true,"entries":[{"lines":["Network"]}],
             "links":[{"type":"report_bug","url":"https://example.org/bugs"},
                      {"type":"website","url":"https://example.org"},
                      {"label":"&dHello $player","url":"https://example.org/store"}]}
            """);
        ProxyText text = text();
        Player player = player(ProtocolVersion.MINECRAFT_1_21);
        when(player.getUsername()).thenReturn("Alex");

        new ProxyServerLinks(text).send(player, ProxyDocuments.load(directory));

        List<ServerLink> published = capture(player);
        assertEquals(3, published.size());
        assertEquals(ServerLink.Type.BUG_REPORT, published.get(0).getBuiltInType().orElseThrow());
        assertEquals(URI.create("https://example.org/bugs"), published.get(0).getUrl());
        assertTrue(published.get(0).getCustomLabel().isEmpty());
        assertEquals(ServerLink.Type.WEBSITE, published.get(1).getBuiltInType().orElseThrow());
        assertEquals(URI.create("https://example.org"), published.get(1).getUrl());
        assertTrue(published.get(2).getBuiltInType().isEmpty());
        assertEquals(text.render("&dHello Alex", text.scope(player, player)),
            published.get(2).getCustomLabel().orElseThrow());
        assertEquals(URI.create("https://example.org/store"), published.get(2).getUrl());
    }

    @Test
    void clientsBelowOneTwentyOneAndLinklessDocumentsReceiveNothing() throws IOException {
        Files.writeString(seeded(), """
            {"schemaVersion":1,"show":true,"entries":[{"lines":["Network"]}],
             "links":[{"type":"website","url":"https://example.org"}]}
            """);
        ProxyDocuments.Snapshot linked = ProxyDocuments.load(directory);
        Player old = player(ProtocolVersion.MINECRAFT_1_20_5);

        new ProxyServerLinks(text()).send(old, linked);

        verify(old, never()).setServerLinks(anyList());
        Files.writeString(directory.resolve("motd.json"), """
            {"schemaVersion":1,"show":true,"entries":[{"lines":["Network"]}]}
            """);
        Player modern = player(ProtocolVersion.MINECRAFT_1_21);

        new ProxyServerLinks(text()).send(modern, ProxyDocuments.load(directory));

        verify(modern, never()).setServerLinks(anyList());
    }

    @Test
    void disabledOrHiddenMotdPublishesNoLinks() throws IOException {
        Files.writeString(seeded(), """
            {"schemaVersion":1,"show":false,"entries":[{"lines":["Network"]}],
             "links":[{"type":"website","url":"https://example.org"}]}
            """);
        Player hidden = player(ProtocolVersion.MINECRAFT_1_21);

        new ProxyServerLinks(text()).send(hidden, ProxyDocuments.load(directory));

        verify(hidden, never()).setServerLinks(anyList());
        Files.writeString(directory.resolve("motd.json"), """
            {"schemaVersion":1,"show":true,"entries":[{"lines":["Network"]}],
             "links":[{"type":"website","url":"https://example.org"}]}
            """);
        Files.writeString(directory.resolve("proxy.json"), """
            {"schemaVersion":1,"motd":{"enabled":false}}
            """);
        Player disabled = player(ProtocolVersion.MINECRAFT_1_21);

        new ProxyServerLinks(text()).send(disabled, ProxyDocuments.load(directory));

        verify(disabled, never()).setServerLinks(anyList());
    }

    private Path seeded() throws IOException {
        ProxyDocuments.seed(directory);
        return directory.resolve("motd.json");
    }

    private static ProxyText text() {
        return new ProxyText(mock(ProxyServer.class));
    }

    private static Player player(ProtocolVersion version) {
        Player player = mock(Player.class);
        when(player.getProtocolVersion()).thenReturn(version);
        return player;
    }

    @SuppressWarnings("unchecked")
    private static List<ServerLink> capture(Player player) {
        ArgumentCaptor<List<ServerLink>> captor = ArgumentCaptor.forClass(List.class);
        verify(player).setServerLinks(captor.capture());
        return captor.getValue();
    }
}
