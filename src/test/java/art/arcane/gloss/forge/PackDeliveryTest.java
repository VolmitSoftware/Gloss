package art.arcane.gloss.forge;

import org.bukkit.entity.Player;
import org.bukkit.event.player.PlayerResourcePackStatusEvent;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.lang.reflect.Proxy;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class PackDeliveryTest {
    private static final UUID PLAYER = UUID.fromString("00000000-0000-0000-0000-0000000000f0");

    @TempDir
    Path folder;

    private final List<Object[]> sent = new ArrayList<>();
    private final PackNamespace namespace = new PackNamespace();
    private final PackDelivery delivery = new PackDelivery(null, namespace);

    private Player player() {
        return (Player) Proxy.newProxyInstance(PackDeliveryTest.class.getClassLoader(),
            new Class<?>[]{Player.class}, (proxy, method, args) -> switch (method.getName()) {
                case "getUniqueId" -> PLAYER;
                case "getName" -> "tester";
                case "setResourcePack" -> {
                    sent.add(args);
                    yield null;
                }
                case "hashCode" -> System.identityHashCode(proxy);
                case "equals" -> proxy == args[0];
                case "toString" -> "player";
                default -> null;
            });
    }

    private PackArtifact artifact(String content) throws IOException {
        Path zip = folder.resolve(content + ".zip");
        Files.writeString(zip, content, StandardCharsets.UTF_8);
        byte[] sha1 = new byte[20];
        Arrays.fill(sha1, (byte) content.length());
        StringBuilder hex = new StringBuilder();
        for (byte value : sha1) {
            hex.append(String.format("%02x", value & 0xFF));
        }
        return new PackArtifact(folder.resolve("pack"), zip, sha1, hex.toString(), 0L);
    }

    private static PackDelivery.Settings settings(String url) {
        return new PackDelivery.Settings(url, false, "127.0.0.1", 0, "Gloss glyphs and icons", false);
    }

    @Test
    void sendingCarriesTheStableIdTheUrlAndTheSha1Bytes() throws IOException {
        PackArtifact artifact = artifact("pack");
        delivery.enable(settings("https://cdn.example/gloss.zip"), artifact);

        assertTrue(delivery.send(player()));

        Object[] call = sent.getFirst();
        assertEquals(PackDelivery.PACK_ID, call[0]);
        assertEquals("https://cdn.example/gloss.zip", call[1]);
        assertArrayEquals(artifact.sha1(), (byte[]) call[2]);
        assertEquals("Gloss glyphs and icons", call[3]);
        assertEquals(Boolean.FALSE, call[4]);
    }

    @Test
    void theUrlTemplateSubstitutesTheCurrentHash() throws IOException {
        PackArtifact artifact = artifact("pack");
        delivery.enable(settings("https://cdn.example/gloss-{sha1}.zip"), artifact);

        assertEquals("https://cdn.example/gloss-" + artifact.sha1Hex() + ".zip", delivery.url());
    }

    @Test
    void nothingIsSentWithoutAUrlOrAListener() throws IOException {
        delivery.enable(settings(""), artifact("pack"));

        assertFalse(delivery.send(player()));
        assertTrue(sent.isEmpty());
        assertFalse(delivery.configured());
    }

    @Test
    void nothingIsSentBeforeTheFirstBuild() {
        delivery.enable(settings("https://cdn.example/gloss.zip"), null);

        assertFalse(delivery.send(player()));
        assertTrue(sent.isEmpty());
    }

    @Test
    void theStatusEventUpdatesTheNamespace() throws IOException {
        PackArtifact artifact = artifact("pack");
        delivery.enable(settings("https://cdn.example/gloss.zip"), artifact);
        Player viewer = player();

        delivery.onStatus(new PlayerResourcePackStatusEvent(viewer, PackDelivery.PACK_ID,
            PlayerResourcePackStatusEvent.Status.SUCCESSFULLY_LOADED));

        assertTrue(namespace.loaded(PLAYER));
        assertEquals("successfully_loaded", namespace.status(PLAYER));
    }

    @Test
    void aStatusForAnotherPluginsPackIsIgnored() throws IOException {
        delivery.enable(settings("https://cdn.example/gloss.zip"), artifact("pack"));

        delivery.onStatus(new PlayerResourcePackStatusEvent(player(), UUID.randomUUID(),
            PlayerResourcePackStatusEvent.Status.SUCCESSFULLY_LOADED));

        assertFalse(namespace.loaded(PLAYER));
    }

    @Test
    void aRebuildResendsAndInvalidatesTheOldStatus() throws IOException {
        delivery.enable(settings("https://cdn.example/gloss-{sha1}.zip"), artifact("pack"));
        delivery.send(player());
        delivery.onStatus(new PlayerResourcePackStatusEvent(player(), PackDelivery.PACK_ID,
            PlayerResourcePackStatusEvent.Status.SUCCESSFULLY_LOADED));
        assertTrue(namespace.loaded(PLAYER));

        PackArtifact rebuilt = artifact("rebuilt");
        delivery.publish(rebuilt);

        assertFalse(namespace.loaded(PLAYER));
        assertEquals("https://cdn.example/gloss-" + rebuilt.sha1Hex() + ".zip", delivery.url());
    }
}
