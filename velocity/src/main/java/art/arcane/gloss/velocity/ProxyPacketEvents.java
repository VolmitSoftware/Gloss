package art.arcane.gloss.velocity;

import com.github.retrooper.packetevents.PacketEvents;
import com.velocitypowered.api.plugin.PluginContainer;
import com.velocitypowered.api.proxy.ProxyServer;
import io.github.retrooper.packetevents.velocity.factory.VelocityPacketEventsBuilder;
import io.github.slimjar.app.builder.InjectingApplicationBuilder;
import io.github.slimjar.injector.loader.factory.InjectableFactory;
import io.github.slimjar.logging.ProcessLogger;
import org.slf4j.Logger;

import java.net.URL;
import java.nio.file.Path;
import java.util.Objects;

public final class ProxyPacketEvents implements AutoCloseable {
    private final boolean owned;

    private ProxyPacketEvents(boolean owned) {
        this.owned = owned;
    }

    public static ProxyPacketEvents start(
            ProxyServer proxy, PluginContainer plugin, Logger logger, Path directory) {
        Objects.requireNonNull(proxy, "proxy");
        Objects.requireNonNull(plugin, "plugin");
        Objects.requireNonNull(logger, "logger");
        Objects.requireNonNull(directory, "directory");
        if (proxy.getPluginManager().getPlugin("packetevents").isPresent()) {
            logger.info("Using the PacketEvents plugin already loaded on this proxy.");
            return new ProxyPacketEvents(false);
        }
        logger.info("Loading PacketEvents...");
        loadLibraries(logger, directory.resolve("libraries"));
        PacketEvents.setAPI(VelocityPacketEventsBuilder.build(proxy, plugin, logger, directory));
        PacketEvents.getAPI().load();
        logger.info("PacketEvents loaded.");
        return new ProxyPacketEvents(true);
    }

    public void init() {
        if (owned) {
            PacketEvents.getAPI().init();
        }
    }

    @Override
    public void close() {
        if (owned && PacketEvents.getAPI() != null) {
            PacketEvents.getAPI().terminate();
        }
    }

    private static void loadLibraries(Logger logger, Path downloadDirectory) {
        URL dependencyFile = ProxyPacketEvents.class.getResource("/gloss-velocity-slimjar.dat");
        URL resolutionFile = ProxyPacketEvents.class.getResource("/gloss-velocity-slimjar-resolutions.dat");
        if (dependencyFile == null || resolutionFile == null) {
            throw new IllegalStateException(
                    "Gloss is missing its Velocity PacketEvents library manifest.");
        }
        InjectingApplicationBuilder<?> builder = InjectingApplicationBuilder.create(
                "Gloss-Velocity", ProxyPacketEvents.class.getClassLoader());
        builder.injectableFactory(InjectableFactory.selecting(
                        InjectableFactory.INJECTABLE,
                        InjectableFactory.WRAPPED,
                        InjectableFactory.UNSAFE,
                        InjectableFactory.ERROR))
                .dependencyFileUrl(dependencyFile)
                .preResolutionFileUrl(resolutionFile)
                .downloadDirectoryPath(downloadDirectory)
                .logger(new ProcessLogger() {
                    @Override
                    public void info(String message, Object... args) {
                        logger.info(format(message, args));
                    }

                    @Override
                    public void error(String message, Object... args) {
                        logger.error(format(message, args));
                    }

                    @Override
                    public void debug(String message, Object... args) {
                    }
                })
                .build();
    }

    private static String format(String message, Object... args) {
        if (args == null || args.length == 0) {
            return message;
        }
        return message.formatted(args);
    }
}
