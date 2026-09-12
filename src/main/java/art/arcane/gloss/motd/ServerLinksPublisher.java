package art.arcane.gloss.motd;

import java.util.List;

/**
 * Publishes the document's pause-menu links. Loaded through PaperBridges; a server without the
 * ServerLinks API simply publishes nothing.
 */
public interface ServerLinksPublisher {
    void publish(List<MotdDoc.MotdLink> links);

    void clear();
}
