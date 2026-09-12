package art.arcane.gloss.motd;

import org.bukkit.event.server.ServerListPingEvent;

import java.util.List;

/**
 * The server-list extras Spigot's ping event cannot express. Loaded through PaperBridges, so a
 * Spigot server simply has no decorator and keeps the MOTD, favicon and max-player count.
 */
public interface PingDecorator {
    void decorate(ServerListPingEvent event, RenderedPing rendered);

    record RenderedPing(List<String> sample, Integer online, Integer max, String version) {
        public RenderedPing {
            sample = sample == null ? List.of() : List.copyOf(sample);
        }
    }
}
