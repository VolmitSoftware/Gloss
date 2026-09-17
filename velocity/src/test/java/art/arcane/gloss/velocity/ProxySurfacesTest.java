package art.arcane.gloss.velocity;

import com.velocitypowered.api.proxy.Player;
import com.velocitypowered.api.proxy.ProxyServer;
import net.kyori.adventure.bossbar.BossBar;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.title.Title;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.mockito.ArgumentCaptor;
import org.slf4j.Logger;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

final class ProxySurfacesTest {
    @TempDir
    Path directory;
    private Player viewer;
    private ProxySurfaces service;

    @BeforeEach
    void prepare() throws IOException {
        Files.writeString(directory.resolve("proxy.json"), """
            {"schemaVersion":1,"motd":{"enabled":false},"tablist":{"enabled":false},
             "scoreboards":{"enabled":false},"surfaces":{"enabled":true}}
            """);
        Files.createDirectories(directory.resolve("surfaces"));
        viewer = mock(Player.class);
        when(viewer.getUniqueId()).thenReturn(UUID.randomUUID());
        when(viewer.getUsername()).thenReturn("Viewer");
        when(viewer.isActive()).thenReturn(true);
        ping(50L);
        service = new ProxySurfaces(new ProxyText(mock(ProxyServer.class)), mock(Logger.class));
    }

    @AfterEach
    void cleanup() {
        service.close();
    }

    @Test
    void theActionBarIsResentEveryTickAndClearedOnceOnDeselection() throws IOException {
        write("bar.json", """
            {"schemaVersion":1,"surface":"actionbar","select":{"when":"viewer.ping < 100"},
             "presentation":{"text":"Hello $player"}}
            """);
        ProxyDocuments.Snapshot snapshot = snapshot();
        service.render(viewer, snapshot);
        service.render(viewer, snapshot);
        ping(500L);
        service.render(viewer, snapshot);
        service.render(viewer, snapshot);

        ArgumentCaptor<Component> sent = ArgumentCaptor.forClass(Component.class);
        verify(viewer, times(3)).sendActionBar(sent.capture());
        assertEquals(List.of(Component.text("Hello Viewer"), Component.text("Hello Viewer"), Component.empty()),
            sent.getAllValues());
    }

    @Test
    void theHighestPriorityDocumentWinsAndItsFirstMatchingVariantRenders() throws IOException {
        write("low.json", """
            {"schemaVersion":1,"surface":"actionbar","select":{"priority":10,"when":"true"},
             "presentation":{"text":"Low"}}
            """);
        write("high.json", """
            {"schemaVersion":1,"surface":"actionbar","select":{"priority":80,"when":"true"},
             "presentation":{"text":"Base"},
             "variants":[{"id":"quick","priority":5,"when":"viewer.ping < 100","presentation":{"text":"Quick"}},
                         {"id":"named","priority":20,"when":"viewer.name == 'Viewer'","presentation":{"text":"Named"}}]}
            """);
        ProxyDocuments.Snapshot snapshot = snapshot();
        service.render(viewer, snapshot);

        ArgumentCaptor<Component> sent = ArgumentCaptor.forClass(Component.class);
        verify(viewer).sendActionBar(sent.capture());
        assertEquals(Component.text("Named"), sent.getValue());
    }

    @Test
    void oneBossBarPerViewerIsCreatedOnceUpdatedInPlaceAndHiddenOnDeselection() throws IOException {
        write("boss.json", """
            {"schemaVersion":1,"surface":"bossbar","select":{"when":"viewer.ping < 300"},
             "presentation":{"title":"Ping $ping","progress":"{{ viewer.ping / 100 }}",
              "color":"red","style":"segmented_10"}}
            """);
        write("second.json", """
            {"schemaVersion":1,"surface":"bossbar","select":{"priority":-5,"when":"viewer.ping < 300"},
             "presentation":{"title":"Never shown"}}
            """);
        ProxyDocuments.Snapshot snapshot = snapshot();
        service.render(viewer, snapshot);

        ArgumentCaptor<BossBar> shown = ArgumentCaptor.forClass(BossBar.class);
        verify(viewer).showBossBar(shown.capture());
        BossBar bar = shown.getValue();
        assertEquals(Component.text("Ping 50"), bar.name());
        assertEquals(0.5F, bar.progress());
        assertEquals(BossBar.Color.RED, bar.color());
        assertEquals(BossBar.Overlay.NOTCHED_10, bar.overlay());

        ping(200L);
        service.render(viewer, snapshot);
        verify(viewer, times(1)).showBossBar(any(BossBar.class));
        assertEquals(Component.text("Ping 200"), bar.name());
        assertEquals(1.0F, bar.progress());

        ping(500L);
        service.render(viewer, snapshot);
        verify(viewer).hideBossBar(bar);
        service.render(viewer, snapshot);
        verify(viewer, times(1)).hideBossBar(any(BossBar.class));
    }

    @Test
    void aSelectTriggerTitleFiresOnceUntilTheSelectedVariantChanges() throws IOException {
        write("card.json", """
            {"schemaVersion":1,"surface":"title","select":{"when":"true"},
             "presentation":{"title":"&aWelcome","subtitle":"Enjoy","fadeInTicks":5,"stayTicks":60,
              "fadeOutTicks":15},
             "variants":[{"id":"vip","when":"viewer.ping < 20","presentation":{"title":"VIP"}}]}
            """);
        ProxyDocuments.Snapshot snapshot = snapshot();
        service.render(viewer, snapshot);
        service.render(viewer, snapshot);
        service.render(viewer, snapshot);

        ArgumentCaptor<Title> titles = ArgumentCaptor.forClass(Title.class);
        verify(viewer, times(1)).showTitle(titles.capture());
        Title first = titles.getValue();
        assertEquals(Component.text("Welcome").color(NamedTextColor.GREEN), first.title());
        assertEquals(Component.text("Enjoy"), first.subtitle());
        assertEquals(Duration.ofMillis(250L), first.times().fadeIn());
        assertEquals(Duration.ofMillis(3000L), first.times().stay());
        assertEquals(Duration.ofMillis(750L), first.times().fadeOut());

        ping(10L);
        service.render(viewer, snapshot);
        service.render(viewer, snapshot);
        verify(viewer, times(2)).showTitle(titles.capture());
        assertEquals(Component.text("VIP"), titles.getValue().title());
        assertEquals(Duration.ofMillis(2000L), titles.getValue().times().stay());
    }

    @Test
    void aOnceTriggerTitleNeverFiresTwiceForTheSameDocument() throws IOException {
        write("card.json", """
            {"schemaVersion":1,"surface":"title","select":{"when":"viewer.ping < 100"},
             "presentation":{"title":"Welcome","trigger":"once"},
             "variants":[{"id":"vip","when":"viewer.ping < 20",
                          "presentation":{"title":"VIP","trigger":"once"}}]}
            """);
        ProxyDocuments.Snapshot snapshot = snapshot();
        service.render(viewer, snapshot);
        ping(500L);
        service.render(viewer, snapshot);
        ping(10L);
        service.render(viewer, snapshot);
        service.render(viewer, snapshot);

        ArgumentCaptor<Title> titles = ArgumentCaptor.forClass(Title.class);
        verify(viewer, times(1)).showTitle(titles.capture());
        assertEquals(Component.text("Welcome"), titles.getValue().title());
    }

    @Test
    void disablingSurfacesClearsEveryKind() throws IOException {
        write("bar.json", """
            {"schemaVersion":1,"surface":"actionbar","select":{"when":"true"},
             "presentation":{"text":"Hello"}}
            """);
        write("boss.json", """
            {"schemaVersion":1,"surface":"bossbar","select":{"when":"true"},
             "presentation":{"title":"Event"}}
            """);
        service.render(viewer, snapshot());
        ArgumentCaptor<BossBar> shown = ArgumentCaptor.forClass(BossBar.class);
        verify(viewer).showBossBar(shown.capture());

        Files.writeString(directory.resolve("proxy.json"), """
            {"schemaVersion":1,"motd":{"enabled":false},"tablist":{"enabled":false},
             "scoreboards":{"enabled":false},"surfaces":{"enabled":false}}
            """);
        service.render(viewer, snapshot());

        verify(viewer).hideBossBar(shown.getValue());
        ArgumentCaptor<Component> sent = ArgumentCaptor.forClass(Component.class);
        verify(viewer, times(2)).sendActionBar(sent.capture());
        assertEquals(Component.empty(), sent.getAllValues().getLast());
    }

    @Test
    void resetHidesTheBarAndReArmsTheTitleSoABackendSwitchGetsThemBack() throws IOException {
        write("boss.json", """
            {"schemaVersion":1,"surface":"bossbar","select":{"when":"true"},
             "presentation":{"title":"Event"}}
            """);
        write("card.json", """
            {"schemaVersion":1,"surface":"title","select":{"when":"true"},
             "presentation":{"title":"Welcome"}}
            """);
        ProxyDocuments.Snapshot snapshot = snapshot();
        service.render(viewer, snapshot);
        ArgumentCaptor<BossBar> shown = ArgumentCaptor.forClass(BossBar.class);
        verify(viewer).showBossBar(shown.capture());
        BossBar bar = shown.getValue();

        service.reset(viewer);
        verify(viewer).hideBossBar(bar);
        service.render(viewer, snapshot);

        verify(viewer, times(2)).showBossBar(shown.capture());
        assertSame(bar, shown.getValue());
        verify(viewer, times(2)).showTitle(any(Title.class));
    }

    @Test
    void forgetDropsStateWithoutSendingAndCloseHidesWhatIsStillOwned() throws IOException {
        write("boss.json", """
            {"schemaVersion":1,"surface":"bossbar","select":{"when":"true"},
             "presentation":{"title":"Event"}}
            """);
        ProxyDocuments.Snapshot snapshot = snapshot();
        service.render(viewer, snapshot);
        ArgumentCaptor<BossBar> shown = ArgumentCaptor.forClass(BossBar.class);
        verify(viewer).showBossBar(shown.capture());

        service.forget(viewer.getUniqueId());
        verify(viewer, never()).hideBossBar(any(BossBar.class));
        service.close();
        verify(viewer, never()).hideBossBar(any(BossBar.class));

        service = new ProxySurfaces(new ProxyText(mock(ProxyServer.class)), mock(Logger.class));
        service.render(viewer, snapshot);
        service.close();
        verify(viewer, times(2)).showBossBar(shown.capture());
        verify(viewer).hideBossBar(shown.getValue());
    }

    private ProxyDocuments.Snapshot snapshot() throws IOException {
        return ProxyDocuments.load(directory);
    }

    private void ping(long milliseconds) {
        when(viewer.getPing()).thenReturn(milliseconds);
    }

    private void write(String name, String json) throws IOException {
        Files.writeString(directory.resolve("surfaces").resolve(name), json);
    }
}
