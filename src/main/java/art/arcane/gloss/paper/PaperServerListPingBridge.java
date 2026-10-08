package art.arcane.gloss.paper;

import art.arcane.gloss.motd.PingDecorator;
import art.arcane.gloss.motd.MotdPolicy;
import com.destroystokyo.paper.event.server.PaperServerListPingEvent;
import org.bukkit.event.server.ServerListPingEvent;

import java.nio.charset.StandardCharsets;
import java.net.InetSocketAddress;
import java.util.List;
import java.util.UUID;

/**
 * The server-list extras only Paper's ping event carries: the hover sample, the player counts and
 * the version line. Every value crossing this boundary is a String or an int, so nothing here
 * touches a relocated type.
 */
public final class PaperServerListPingBridge implements PingDecorator {
    @Override
    public MotdPolicy.Request request(ServerListPingEvent event) {
        if (!(event instanceof PaperServerListPingEvent paper)) {
            return new MotdPolicy.Request(event.getHostname(), null);
        }
        int protocol = paper.getClient().getProtocolVersion();
        InetSocketAddress host = paper.getClient().getVirtualHost();
        return new MotdPolicy.Request(host == null ? event.getHostname() : host.getHostString(), protocol < 0 ? null : protocol);
    }

    @Override
    public void decorate(ServerListPingEvent event, RenderedPing rendered) {
        if (!(event instanceof PaperServerListPingEvent paper)) {
            return;
        }
        if (!rendered.sampleMode().equals("inherit")) {
            applySample(paper, rendered.sampleMode().equals("hide") ? List.of() : rendered.sample());
        }
        if (!rendered.counts().onlineMode().equals("inherit")) {
            paper.setNumPlayers(MotdPolicy.count(rendered.counts().onlineMode(), rendered.counts().onlineValue(), paper.getNumPlayers()));
        } else if (rendered.online() != null) {
            paper.setNumPlayers(rendered.online());
        }
        if (rendered.version() != null) {
            paper.setVersion(rendered.version());
        }
        if (rendered.counts().hide()) {
            paper.setHidePlayers(true);
        }
    }

    private static void applySample(PaperServerListPingEvent paper, List<String> sample) {
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
