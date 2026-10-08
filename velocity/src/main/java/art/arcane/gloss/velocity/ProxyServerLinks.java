package art.arcane.gloss.velocity;

import art.arcane.gloss.expr.ExpressionScope;
import com.velocitypowered.api.network.ProtocolVersion;
import com.velocitypowered.api.proxy.Player;
import com.velocitypowered.api.util.ServerLink;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Publishes the MOTD document's pause-menu links to each player after every backend connection,
 * so the proxy's list is always the last one the client received.
 */
public final class ProxyServerLinks {
    private final ProxyText text;
    private final Set<UUID> published = ConcurrentHashMap.newKeySet();

    public ProxyServerLinks(ProxyText text) {
        this.text = text;
    }

    public void send(Player player, ProxyDocuments.Snapshot snapshot) {
        List<ProxyDocuments.MotdLink> documented = snapshot.motd().enabledLinks(snapshot.settings().motd());
        if (!snapshot.motd().linksEnabled(snapshot.settings().motd())
            || snapshot.motd().serverLinks() == null && !text.test(snapshot.motd().show(), text.scope(null, null))
            || !player.getProtocolVersion().noLessThan(ProtocolVersion.MINECRAFT_1_21)) {
            if (published.remove(player.getUniqueId())) {
                player.setServerLinks(List.of());
            }
            return;
        }
        ExpressionScope scope = text.scope(player, player);
        List<ServerLink> links = new ArrayList<>(documented.size());
        for (ProxyDocuments.MotdLink link : documented) {
            links.add(link.isLabelled()
                ? ServerLink.serverLink(text.render(link.label(), scope), link.url())
                : ServerLink.serverLink(builtIn(link.type()), link.url()));
        }
        player.setServerLinks(links);
        published.add(player.getUniqueId());
    }

    public void forget(UUID playerId) {
        published.remove(playerId);
    }

    public void clear(Player player) {
        if (published.remove(player.getUniqueId())) {
            player.setServerLinks(List.of());
        }
    }

    private static ServerLink.Type builtIn(String type) {
        return type.equals("report_bug")
            ? ServerLink.Type.BUG_REPORT
            : ServerLink.Type.valueOf(type.toUpperCase(Locale.ROOT));
    }
}
