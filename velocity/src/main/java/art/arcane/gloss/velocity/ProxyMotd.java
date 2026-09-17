package art.arcane.gloss.velocity;

import art.arcane.gloss.expr.ExpressionScope;
import com.velocitypowered.api.proxy.server.ServerPing;
import com.velocitypowered.api.util.Favicon;
import net.kyori.adventure.text.serializer.legacy.LegacyComponentSerializer;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ThreadLocalRandom;

public final class ProxyMotd {
    private static final int ICON_SIZE = 64;
    private static final byte[] PNG_SIGNATURE = {(byte) 0x89, 'P', 'N', 'G', '\r', '\n', 0x1A, '\n'};

    private final ProxyText text;
    private final Map<String, Favicon> icons;

    public ProxyMotd(ProxyText text, Path directory, ProxyDocuments.Motd document) throws IOException {
        this.text = text;
        Map<String, Favicon> loaded = new HashMap<>();
        Path images = directory.resolve("images").toAbsolutePath().normalize();
        loadIcon(loaded, images, document.favicon());
        for (ProxyDocuments.MotdEntry entry : document.entries()) {
            loadIcon(loaded, images, entry.favicon());
        }
        this.icons = Map.copyOf(loaded);
    }

    public ServerPing render(ServerPing original, ProxyDocuments.Snapshot snapshot) {
        ExpressionScope scope = text.scope(null, null);
        if (!snapshot.settings().motd() || !text.test(snapshot.motd().show(), scope)) {
            return original;
        }
        ProxyDocuments.MotdEntry entry = snapshot.motd().entries()
            .get(ThreadLocalRandom.current().nextInt(snapshot.motd().entries().size()));
        ServerPing.Builder builder = original.asBuilder()
            .description(text.render(String.join("\n&r", entry.lines()), scope));
        if (entry.online() != null) {
            builder.onlinePlayers(count(entry.online(), scope));
        }
        if (entry.max() != null) {
            builder.maximumPlayers(count(entry.max(), scope));
        }
        if (entry.version() != null) {
            builder.version(new ServerPing.Version(original.getVersion().getProtocol(), legacy(entry.version(), scope)));
        }
        if (!entry.sample().isEmpty()) {
            builder.clearSamplePlayers();
            for (int index = 0; index < entry.sample().size(); index++) {
                builder.samplePlayers(new ServerPing.SamplePlayer(legacy(entry.sample().get(index), scope),
                    new UUID(0L, index)));
            }
        }
        String path = faviconPath(snapshot.motd(), entry);
        Favicon favicon = path == null ? null : icons.get(path);
        if (favicon != null) {
            builder.favicon(favicon);
        }
        return builder.build();
    }

    private static void loadIcon(Map<String, Favicon> loaded, Path images, String path) throws IOException {
        if (path == null || path.isBlank()) {
            return;
        }
        Path file = images.resolve(path).normalize();
        if (!file.startsWith(images)) {
            throw new IllegalArgumentException("MOTD favicon must be inside images/");
        }
        byte[] bytes = Files.readAllBytes(file);
        if (bytes.length < PNG_SIGNATURE.length
            || !Arrays.equals(bytes, 0, PNG_SIGNATURE.length, PNG_SIGNATURE, 0, PNG_SIGNATURE.length)) {
            throw new IllegalArgumentException("MOTD favicon \"" + path + "\" must be a PNG file");
        }
        BufferedImage image = ImageIO.read(new ByteArrayInputStream(bytes));
        if (image == null) {
            throw new IllegalArgumentException("MOTD favicon \"" + path + "\" could not be decoded");
        }
        if (image.getWidth() != ICON_SIZE || image.getHeight() != ICON_SIZE) {
            throw new IllegalArgumentException("MOTD favicon \"" + path + "\" must be " + ICON_SIZE + "x" + ICON_SIZE
                + ", this one is " + image.getWidth() + "x" + image.getHeight());
        }
        loaded.put(path, Favicon.create(image));
    }

    private static String faviconPath(ProxyDocuments.Motd document, ProxyDocuments.MotdEntry entry) {
        if (entry.favicon() != null && !entry.favicon().isBlank()) {
            return entry.favicon();
        }
        return document.favicon();
    }

    private String legacy(String source, ExpressionScope scope) {
        return LegacyComponentSerializer.legacySection().serialize(text.render(source, scope));
    }

    private int count(String source, ExpressionScope scope) {
        double count = Double.parseDouble(text.plain(source, scope));
        if (!Double.isFinite(count)) {
            throw new IllegalArgumentException("MOTD count must be finite");
        }
        return (int) Math.clamp(count, 0, Integer.MAX_VALUE);
    }
}
