package art.arcane.gloss.paper;

import art.arcane.gloss.motd.ServerLinksPublisher;
import art.arcane.gloss.service.PaperBridges;
import org.bukkit.ServerLinks;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Method;
import java.net.URI;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class PaperServerLinksBridgeTest {
    @Test
    void theBridgeLoadsThroughPaperBridgesAsAServerLinksPublisher() {
        Optional<ServerLinksPublisher> publisher = PaperBridges.load("org.bukkit.ServerLinks",
            "art.arcane.gloss.paper.PaperServerLinksBridge", ServerLinksPublisher.class);

        assertTrue(publisher.isPresent());
    }

    @Test
    void theServerStillDeclaresTheStringTypedLinkSettersTheBridgeUses() throws ReflectiveOperationException {
        Method labelled = ServerLinks.class.getMethod("addLink", String.class, URI.class);
        Method typed = ServerLinks.class.getMethod("setLink", ServerLinks.Type.class, URI.class);
        Method remove = ServerLinks.class.getMethod("removeLink", ServerLinks.ServerLink.class);

        assertNotNull(labelled);
        assertNotNull(typed);
        assertEquals(boolean.class, remove.getReturnType());
    }

    @Test
    void everyDocumentLinkTypeMapsOntoAServerLinkType() {
        for (String type : art.arcane.gloss.motd.MotdDoc.LINK_TYPES) {
            assertNotNull(ServerLinks.Type.valueOf(type.toUpperCase(java.util.Locale.ROOT)),
                "no ServerLinks.Type for " + type);
        }
    }
}
