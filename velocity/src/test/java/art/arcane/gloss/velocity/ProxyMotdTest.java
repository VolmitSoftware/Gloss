package art.arcane.gloss.velocity;

import com.velocitypowered.api.proxy.ProxyServer;
import art.arcane.gloss.motd.MotdPolicy;
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
import static org.mockito.Mockito.clearInvocations;
import static org.mockito.Mockito.verifyNoInteractions;
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
        ServerPing ping = service.render(original(), snapshot.settings().motd(), new MotdPolicy.Request("", null));
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
        assertSame(original, service.render(original, snapshot.settings().motd(), new MotdPolicy.Request("", null)));
    }

    @Test
    void entryConditionsExcludeHiddenWeightsAndPreserveTheOriginalWhenNoneMatch() throws IOException {
        ProxyDocuments.seed(directory);
        Files.writeString(directory.resolve("motd.json"), """
            {"schemaVersion":1,"entries":[
              {"lines":["Hidden"],"show":false,"weight":1000000},
              {"lines":["Visible"],"show":"server.online > 0","weight":1}]}
            """);
        ProxyDocuments.Snapshot snapshot = ProxyDocuments.load(directory);
        ProxyServer proxy = mock(ProxyServer.class);
        ProxyText text = new ProxyText(proxy);
        ProxyMotd service = new ProxyMotd(text, directory, snapshot.motd());
        ServerPing original = original();
        assertSame(original, service.render(original, snapshot.settings().motd(), new MotdPolicy.Request("", null)));
        when(proxy.getPlayerCount()).thenReturn(1);
        service.refresh();
        for (int index = 0; index < 20; index++) {
            assertEquals(Component.text("Visible"), service.render(original, snapshot.settings().motd(), new MotdPolicy.Request("", null)).getDescriptionComponent());
        }
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

        ServerPing ping = service.render(original(), snapshot.settings().motd(), new MotdPolicy.Request("", null));

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

        ServerPing ping = service.render(original(), snapshot.settings().motd(), new MotdPolicy.Request("", null));

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

    @Test
    void statusRequestsUseSnapshotsWithoutQueryingProviders() throws IOException {
        ProxyDocuments.seed(directory);
        Files.writeString(directory.resolve("motd.json"), """
            {"schemaVersion":1,"entries":[{"lines":["Players: $online"],"online":"$online"}]}
            """);
        ProxyServer proxy = mock(ProxyServer.class);
        when(proxy.getPlayerCount()).thenReturn(7);
        ProxyDocuments.Snapshot documents = ProxyDocuments.load(directory);
        ProxyMotd service = new ProxyMotd(new ProxyText(proxy), directory, documents.motd());
        clearInvocations(proxy);
        for (int index = 0; index < 20; index++) {
            ServerPing response = service.render(original(), true, new MotdPolicy.Request("", null));
            assertEquals(Component.text("Players: 7"), response.getDescriptionComponent());
            assertEquals(7, response.getPlayers().orElseThrow().getOnline());
        }
        verifyNoInteractions(proxy);
        when(proxy.getPlayerCount()).thenReturn(8);
        service.refresh();
        assertEquals(8, service.render(original(), true, new MotdPolicy.Request("", null))
            .getPlayers().orElseThrow().getOnline());
    }

    @Test
    void hostnameProtocolSelectionAndSampleCountPoliciesCompose() throws IOException {
        ProxyDocuments.seed(directory);
        Files.writeString(directory.resolve("motd.json"), """
            {"schemaVersion":1,"rotation":{"mode":"first"},"entries":[
              {"lines":["Event"],"select":{"hostnames":["event.example.org"],"minProtocol":774},
               "sampleMode":"hide","counts":{"onlineMode":"offset","onlineValue":5,"maximumMode":"fixed","maximumValue":100}},
              {"lines":["Default"],"counts":{"hide":true}}]}
            """);
        ProxyMotd service = new ProxyMotd(new ProxyText(mock(ProxyServer.class)), directory,
            ProxyDocuments.load(directory).motd());
        ServerPing original = original();
        ServerPing event = service.render(original, true, new MotdPolicy.Request("EVENT.EXAMPLE.ORG.", 774));
        assertEquals(Component.text("Event"), event.getDescriptionComponent());
        assertEquals(original.getPlayers().orElseThrow().getOnline() + 5, event.getPlayers().orElseThrow().getOnline());
        assertEquals(100, event.getPlayers().orElseThrow().getMax());
        assertTrue(event.getPlayers().orElseThrow().getSample().isEmpty());
        ServerPing fallback = service.render(original, true, new MotdPolicy.Request("event.example.org", null));
        assertEquals(Component.text("Default"), fallback.getDescriptionComponent());
        assertTrue(fallback.getPlayers().isEmpty());
    }

    @Test
    void iconSetsArePreloadedAndSequenceDoesNotReadDisk() throws IOException {
        ProxyDocuments.seed(directory);
        Path first = icon("first.png", 0xFF204080);
        Path second = icon("second.png", 0xFF80C0FF);
        String firstData = Favicon.create(first).getBase64Url();
        String secondData = Favicon.create(second).getBase64Url();
        Files.writeString(directory.resolve("motd.json"), """
            {"schemaVersion":1,"rotation":{"mode":"sequence"},"icons":["first.png","second.png"],
             "entries":[{"lines":["Network"]}]}
            """);
        ProxyMotd service = new ProxyMotd(new ProxyText(mock(ProxyServer.class)), directory,
            ProxyDocuments.load(directory).motd());
        Files.delete(first);
        Files.delete(second);
        assertEquals(firstData, service.render(original(), true, new MotdPolicy.Request("", null))
            .getFavicon().orElseThrow().getBase64Url());
        assertEquals(secondData, service.render(original(), true, new MotdPolicy.Request("", null))
            .getFavicon().orElseThrow().getBase64Url());
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
