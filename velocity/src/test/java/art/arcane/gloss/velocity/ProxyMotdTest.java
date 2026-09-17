package art.arcane.gloss.velocity;

import com.velocitypowered.api.proxy.ProxyServer;
import com.velocitypowered.api.proxy.server.ServerPing;
import com.velocitypowered.api.util.Favicon;
import net.kyori.adventure.text.Component;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

final class ProxyMotdTest {
    @TempDir
    Path directory;

    @Test
    void rendersDescriptionCountsVersionSampleAndRealFavicon() throws IOException {
        ProxyDocuments.seed(directory);
        Files.createDirectories(directory.resolve("images"));
        ImageIO.write(new BufferedImage(64, 64, BufferedImage.TYPE_INT_ARGB), "png", directory.resolve("images/icon.png").toFile());
        Files.writeString(directory.resolve("motd.json"), """
            {"schemaVersion":1,"show":true,"entries":[{
              "lines":["Network","Online: $online"],"favicon":"icon.png",
              "online":"{{ server.online + 3 }}","max":"50",
              "version":"Network $online","sample":["Players: $online"]}]}
            """);
        ProxyDocuments.Snapshot snapshot = ProxyDocuments.load(directory);
        ProxyServer proxy = mock(ProxyServer.class);
        when(proxy.getPlayerCount()).thenReturn(7);
        ProxyText text = new ProxyText(proxy);
        ProxyMotd service = new ProxyMotd(text, directory, snapshot.motd());
        ServerPing ping = service.render(original(), snapshot);
        assertEquals(text.render("Network\n&rOnline: 7", text.scope(null, null)), ping.getDescriptionComponent());
        assertEquals(10, ping.getPlayers().orElseThrow().getOnline());
        assertEquals(50, ping.getPlayers().orElseThrow().getMax());
        assertEquals("Players: 7", ping.getPlayers().orElseThrow().getSample().getFirst().getName());
        assertEquals("Network 7", ping.getVersion().getName());
        assertEquals(774, ping.getVersion().getProtocol());
        assertTrue(ping.getFavicon().orElseThrow().getBase64Url().startsWith("data:image/png;base64,"));
    }

    @Test
    void hiddenMotdPreservesOriginalPing() throws IOException {
        ProxyDocuments.seed(directory);
        Files.writeString(directory.resolve("motd.json"), """
            {"schemaVersion":1,"show":false,"entries":[{"lines":["Hidden"]}]}
            """);
        ProxyDocuments.Snapshot snapshot = ProxyDocuments.load(directory);
        ProxyMotd service = new ProxyMotd(new ProxyText(mock(ProxyServer.class)), directory, snapshot.motd());
        ServerPing original = original();
        assertSame(original, service.render(original, snapshot));
    }

    @Test
    void invalidFaviconRejectsSnapshotActivation() throws IOException {
        ProxyDocuments.seed(directory);
        Files.createDirectories(directory.resolve("images"));
        ImageIO.write(new BufferedImage(32, 32, BufferedImage.TYPE_INT_ARGB), "png", directory.resolve("images/icon.png").toFile());
        Files.writeString(directory.resolve("motd.json"), """
            {"schemaVersion":1,"entries":[{"lines":["Network"],"favicon":"icon.png"}]}
            """);
        ProxyDocuments.Snapshot snapshot = ProxyDocuments.load(directory);
        IllegalArgumentException refusal = assertThrows(IllegalArgumentException.class,
            () -> new ProxyMotd(new ProxyText(mock(ProxyServer.class)), directory, snapshot.motd()));
        assertTrue(refusal.getMessage().contains("64x64") && refusal.getMessage().contains("32x32"), refusal.getMessage());
    }

    @Test
    void aFaviconThatIsNotAPngIsRefusedEvenAtSixtyFourSquare() throws IOException {
        ProxyDocuments.seed(directory);
        Files.createDirectories(directory.resolve("images"));
        ImageIO.write(new BufferedImage(64, 64, BufferedImage.TYPE_INT_RGB), "jpg", directory.resolve("images/icon.png").toFile());
        Files.writeString(directory.resolve("motd.json"), """
            {"schemaVersion":1,"entries":[{"lines":["Network"],"favicon":"icon.png"}]}
            """);
        ProxyDocuments.Snapshot snapshot = ProxyDocuments.load(directory);
        IllegalArgumentException refusal = assertThrows(IllegalArgumentException.class,
            () -> new ProxyMotd(new ProxyText(mock(ProxyServer.class)), directory, snapshot.motd()));
        assertTrue(refusal.getMessage().contains("PNG") && refusal.getMessage().contains("icon.png"), refusal.getMessage());
    }

    @Test
    void aDocumentFaviconAppliesToAnEntryWithoutOne() throws IOException {
        ProxyDocuments.seed(directory);
        Path icon = icon("default.png", 0xFF204080);
        Files.writeString(directory.resolve("motd.json"), """
            {"schemaVersion":1,"show":true,"favicon":"default.png",
             "entries":[{"lines":["Network"]}]}
            """);
        ProxyDocuments.Snapshot snapshot = ProxyDocuments.load(directory);
        ProxyMotd service = new ProxyMotd(new ProxyText(mock(ProxyServer.class)), directory, snapshot.motd());

        ServerPing ping = service.render(original(), snapshot);

        assertEquals(Favicon.create(icon).getBase64Url(), ping.getFavicon().orElseThrow().getBase64Url());
    }

    @Test
    void anEntryFaviconWinsOverTheDocumentFavicon() throws IOException {
        ProxyDocuments.seed(directory);
        Path fallback = icon("default.png", 0xFF204080);
        Path override = icon("season4.png", 0xFF80C0FF);
        Files.writeString(directory.resolve("motd.json"), """
            {"schemaVersion":1,"show":true,"favicon":"default.png",
             "entries":[{"lines":["Network"],"favicon":"season4.png"}]}
            """);
        ProxyDocuments.Snapshot snapshot = ProxyDocuments.load(directory);
        ProxyMotd service = new ProxyMotd(new ProxyText(mock(ProxyServer.class)), directory, snapshot.motd());

        ServerPing ping = service.render(original(), snapshot);

        assertEquals(Favicon.create(override).getBase64Url(), ping.getFavicon().orElseThrow().getBase64Url());
        assertNotEquals(Favicon.create(fallback).getBase64Url(), ping.getFavicon().orElseThrow().getBase64Url());
    }

    @Test
    void anInvalidDocumentFaviconRejectsSnapshotActivation() throws IOException {
        ProxyDocuments.seed(directory);
        Files.createDirectories(directory.resolve("images"));
        ImageIO.write(new BufferedImage(32, 32, BufferedImage.TYPE_INT_ARGB), "png",
            directory.resolve("images/default.png").toFile());
        Files.writeString(directory.resolve("motd.json"), """
            {"schemaVersion":1,"favicon":"default.png","entries":[{"lines":["Network"]}]}
            """);
        ProxyDocuments.Snapshot snapshot = ProxyDocuments.load(directory);

        assertThrows(IllegalArgumentException.class,
            () -> new ProxyMotd(new ProxyText(mock(ProxyServer.class)), directory, snapshot.motd()));
    }

    private Path icon(String name, int argb) throws IOException {
        Files.createDirectories(directory.resolve("images"));
        BufferedImage image = new BufferedImage(64, 64, BufferedImage.TYPE_INT_ARGB);
        for (int x = 0; x < 64; x++) {
            for (int y = 0; y < 64; y++) {
                image.setRGB(x, y, argb);
            }
        }
        Path file = directory.resolve("images").resolve(name);
        ImageIO.write(image, "png", file.toFile());
        return file;
    }

    private static ServerPing original() {
        return ServerPing.builder().version(new ServerPing.Version(774, "Original"))
            .description(Component.text("Original")).onlinePlayers(2).maximumPlayers(20).build();
    }
}
