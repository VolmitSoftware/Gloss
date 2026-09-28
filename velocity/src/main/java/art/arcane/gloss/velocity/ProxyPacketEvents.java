package art.arcane.gloss.velocity;

import com.github.retrooper.packetevents.PacketEvents;
import com.github.retrooper.packetevents.PacketEventsAPI;
import com.velocitypowered.api.plugin.PluginContainer;
import com.velocitypowered.api.proxy.ProxyServer;
import io.github.retrooper.packetevents.velocity.factory.VelocityPacketEventsBuilder;
import org.slf4j.Logger;

import java.nio.file.Path;
import java.util.Objects;

public final class ProxyPacketEvents implements AutoCloseable {
    private final PacketEventsAPI<PluginContainer> api;

    private ProxyPacketEvents(PacketEventsAPI<PluginContainer> api) {
        this.api = api;
    }

    public static ProxyPacketEvents start(
            ProxyServer proxy, PluginContainer plugin, Logger logger, Path directory) {
        Objects.requireNonNull(proxy, "proxy");
        Objects.requireNonNull(plugin, "plugin");
        Objects.requireNonNull(logger, "logger");
        Objects.requireNonNull(directory, "directory");
        logger.info("Loading bundled PacketEvents...");
        PacketEventsAPI<PluginContainer> api = VelocityPacketEventsBuilder.build(proxy, plugin, logger, directory);
        api.getSettings().checkForUpdates(false);
        PacketEvents.setAPI(api);
        api.load();
        logger.info("PacketEvents loaded.");
        return new ProxyPacketEvents(api);
    }

    public void init() {
        api.init();
    }

    @Override
    public void close() {
        api.terminate();
    }
}
