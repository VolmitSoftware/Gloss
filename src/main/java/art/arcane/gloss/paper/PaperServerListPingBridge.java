package art.arcane.gloss.paper;

import art.arcane.gloss.motd.PingDecorator;
import com.destroystokyo.paper.event.server.PaperServerListPingEvent;
import org.bukkit.event.server.ServerListPingEvent;

import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.UUID;

/**
 * The server-list extras only Paper's ping event carries: the hover sample, the player counts and
 * the version line. Every value crossing this boundary is a String or an int, so nothing here
 * touches a relocated type.
 */
public final class PaperServerListPingBridge implements PingDecorator {
    @Override
    public void decorate(ServerListPingEvent event, RenderedPing rendered) {
        if (!(event instanceof PaperServerListPingEvent paper)) {
            return;
        }
        applySample(paper, rendered.sample());
        if (rendered.online() != null) {
            paper.setNumPlayers(rendered.online());
        }
        if (rendered.version() != null) {
            paper.setVersion(rendered.version());
        }
    }

    private static void applySample(PaperServerListPingEvent paper, List<String> sample) {
        if (sample.isEmpty()) {
            return;
        }
        List<PaperServerListPingEvent.ListedPlayerInfo> listed = paper.getListedPlayers();
        listed.clear();
        for (String line : sample) {
            listed.add(new PaperServerListPingEvent.ListedPlayerInfo(line, sampleId(line)));
        }
    }

    /**
     * A stable id per sample line: the client only renders the name, and a random id per ping would
     * make the hover list flicker for anyone polling the server.
     */
    private static UUID sampleId(String line) {
        return UUID.nameUUIDFromBytes(("gloss:motd:sample:" + line).getBytes(StandardCharsets.UTF_8));
    }
}
