package art.arcane.gloss.velocity;

import com.github.retrooper.packetevents.PacketEvents;
import com.github.retrooper.packetevents.PacketEventsAPI;
import com.github.retrooper.packetevents.settings.PacketEventsSettings;
import com.velocitypowered.api.plugin.PluginContainer;
import com.velocitypowered.api.proxy.ProxyServer;
import io.github.retrooper.packetevents.velocity.factory.VelocityPacketEventsBuilder;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.mockito.InOrder;
import org.mockito.MockedStatic;
import org.slf4j.Logger;

import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

final class ProxyPacketEventsTest {
    @TempDir
    Path directory;

    @Test
    void managesItsBundledApiWithoutConsultingInstalledPlugins() {
        ProxyServer proxy = mock(ProxyServer.class);
        PluginContainer plugin = mock(PluginContainer.class);
        Logger logger = mock(Logger.class);
        PacketEventsAPI<PluginContainer> api = mock(PacketEventsAPI.class);
        PacketEventsSettings settings = new PacketEventsSettings();
        when(api.getSettings()).thenReturn(settings);
        PacketEventsAPI<?> previousApi = PacketEvents.getAPI();

        try (MockedStatic<VelocityPacketEventsBuilder> builder = mockStatic(VelocityPacketEventsBuilder.class)) {
            builder.when(() -> VelocityPacketEventsBuilder.build(proxy, plugin, logger, directory)).thenReturn(api);
            try (ProxyPacketEvents runtime = ProxyPacketEvents.start(proxy, plugin, logger, directory)) {
                assertSame(api, PacketEvents.getAPI());
                assertFalse(settings.shouldCheckForUpdates());
                runtime.init();
            }
            InOrder lifecycle = inOrder(api);
            lifecycle.verify(api).load();
            lifecycle.verify(api).init();
            lifecycle.verify(api).terminate();
            verifyNoInteractions(proxy);
        } finally {
            PacketEvents.setAPI(previousApi);
        }
    }
}
