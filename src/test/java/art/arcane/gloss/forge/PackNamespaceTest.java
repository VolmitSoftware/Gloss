package art.arcane.gloss.forge;

import org.junit.jupiter.api.Test;

import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class PackNamespaceTest {
    private static final UUID PLAYER = UUID.nameUUIDFromBytes("player".getBytes());
    private static final String SHA1 = "0123456789abcdef0123456789abcdef01234567";

    private final PackNamespace namespace = new PackNamespace();

    @Test
    void prefixIsPack() {
        assertEquals("pack", namespace.prefix());
    }

    @Test
    void aViewerWithNoStatusHasNotLoadedThePack() {
        namespace.publish(SHA1);

        assertFalse(namespace.loaded(PLAYER));
        assertEquals("none", namespace.status(PLAYER));
        assertEquals(Boolean.FALSE, namespace.resolve("loaded", null));
        assertEquals(SHA1, namespace.resolve("sha1", null));
    }

    @Test
    void successfullyLoadedForTheCurrentHashCounts() {
        namespace.publish(SHA1);
        namespace.record(PLAYER, "SUCCESSFULLY_LOADED");

        assertTrue(namespace.loaded(PLAYER));
        assertEquals("successfully_loaded", namespace.status(PLAYER));
    }

    @Test
    void aRebuildInvalidatesEveryViewerUntilTheyReloadIt() {
        namespace.publish(SHA1);
        namespace.record(PLAYER, "SUCCESSFULLY_LOADED");
        namespace.publish("ffffffffffffffffffffffffffffffffffffffff");

        assertFalse(namespace.loaded(PLAYER));
        assertEquals("none", namespace.status(PLAYER));
    }

    @Test
    void anyOtherStatusIsNotLoaded() {
        namespace.publish(SHA1);
        namespace.record(PLAYER, "DECLINED");
        assertFalse(namespace.loaded(PLAYER));
        assertEquals("declined", namespace.status(PLAYER));

        namespace.record(PLAYER, "ACCEPTED");
        assertFalse(namespace.loaded(PLAYER));
        assertEquals("accepted", namespace.status(PLAYER));
    }

    @Test
    void quittingForgetsTheViewer() {
        namespace.publish(SHA1);
        namespace.record(PLAYER, "SUCCESSFULLY_LOADED");
        namespace.forget(PLAYER);

        assertFalse(namespace.loaded(PLAYER));
    }

    @Test
    void unknownSuffixesResolveToNull() {
        assertNull(namespace.resolve("nonsense", null));
        assertNull(namespace.resolve(null, null));
    }

    @Test
    void theStaticViewerCheckIsFalseWithoutAnInstalledNamespace() {
        assertFalse(PackNamespace.loaded((org.bukkit.entity.Player) null));
    }
}
