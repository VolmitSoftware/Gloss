package art.arcane.gloss.velocity;

import art.arcane.gloss.expr.ExpressionScope;
import com.velocitypowered.api.proxy.server.ServerPing;
import com.velocitypowered.api.util.Favicon;
import net.kyori.adventure.text.serializer.legacy.LegacyComponentSerializer;

import java.io.IOException;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ThreadLocalRandom;

public final class ProxyMotd {
    private final ProxyText text;
    private final Map<String, Favicon> icons;

    public ProxyMotd(ProxyText text, Path directory, ProxyDocuments.Motd document) throws IOException {
        this.text = text;
        Map<String, Favicon> loaded = new HashMap<>();
        Path images = directory.resolve("images").toAbsolutePath().normalize();
        for (ProxyDocuments.MotdEntry entry : document.entries()) {
            if (entry.favicon() != null && !entry.favicon().isBlank()) {
                Path file = images.resolve(entry.favicon()).normalize();
                if (!file.startsWith(images)) {
                    throw new IllegalArgumentException("MOTD favicon must be inside images/");
                }
                loaded.put(entry.favicon(), Favicon.create(file));
            }
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
        Favicon favicon = entry.favicon() == null ? null : icons.get(entry.favicon());
        if (favicon != null) {
            builder.favicon(favicon);
        }
        return builder.build();
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
