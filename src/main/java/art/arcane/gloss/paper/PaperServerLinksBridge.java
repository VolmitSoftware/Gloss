package art.arcane.gloss.paper;

import art.arcane.gloss.motd.MotdDoc;
import art.arcane.gloss.Gloss;
import art.arcane.volmlib.util.scheduling.FoliaScheduler;
import org.bukkit.entity.Player;
import art.arcane.gloss.motd.ServerLinksPublisher;
import org.bukkit.Bukkit;
import org.bukkit.ServerLinks;

import java.net.URI;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Publishes the document's pause-menu links. Known types take the client's built-in label through
 * {@code addLink(Type, URI)}; a labelled link uses the String overload, so no relocated type is
 * named here. Only the links this bridge added are removed again.
 */
public final class PaperServerLinksBridge implements ServerLinksPublisher {
    private final List<ServerLinks.ServerLink> published = new ArrayList<>();
    private final AtomicLong generation = new AtomicLong();

    @Override
    public void publish(List<MotdDoc.MotdLink> links) {
        clear();
        ServerLinks serverLinks = Bukkit.getServerLinks();
        if (serverLinks == null) {
            return;
        }
        for (MotdDoc.MotdLink link : links) {
            URI url = URI.create(link.url());
            published.add(link.isLabelled()
                ? serverLinks.addLink(link.label(), url)
                : serverLinks.addLink(ServerLinks.Type.valueOf(link.type().toUpperCase(Locale.ROOT)), url));
        }
        ServerLinks snapshot = serverLinks.copy();
        long version = generation.get();
        Gloss plugin = Gloss.instance;
        for (Player player : Bukkit.getOnlinePlayers()) {
            FoliaScheduler.runEntity(plugin, player, () -> {
                if (generation.get() == version && player.isOnline() && (plugin.proxyOwnership() == null
                    || !plugin.proxyOwnership().ownsServerLinks())) {
                    player.sendLinks(snapshot);
                }
            });
        }
    }

    @Override
    public void clear() {
        generation.incrementAndGet();
        if (published.isEmpty()) {
            return;
        }
        ServerLinks serverLinks = Bukkit.getServerLinks();
        if (serverLinks != null) {
            for (ServerLinks.ServerLink link : published) {
                serverLinks.removeLink(link);
            }
        }
        published.clear();
    }
}
