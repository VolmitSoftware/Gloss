package art.arcane.gloss.velocity;

import art.arcane.gloss.expr.ExpressionScope;
import art.arcane.gloss.motd.MotdPolicy;
import net.kyori.adventure.text.Component;
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
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicLong;
import java.util.random.RandomGenerator;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ThreadLocalRandom;

public final class ProxyMotd {
    private static final int ICON_SIZE = 64;
    private static final byte[] PNG_SIGNATURE = {(byte) 0x89, 'P', 'N', 'G', '\r', '\n', 0x1A, '\n'};

    private final ProxyText text;
    private final Map<String, Favicon> icons;
    private final ProxyDocuments.Motd document;
    private final AtomicLong sequence = new AtomicLong();
    private volatile Prepared prepared;

    public ProxyMotd(ProxyText text, Path directory, ProxyDocuments.Motd document) throws IOException {
        this.text = text;
        this.document = document;
        Map<String, Favicon> loaded = new HashMap<>();
        Path images = directory.resolve("images").toAbsolutePath().normalize();
        for (ProxyDocuments.MotdEntry entry : document.entries()) {
            for (String path : document.iconsFor(entry)) {
                loadIcon(loaded, images, path);
            }
        }
        this.icons = Map.copyOf(loaded);
        refresh();
    }

    public void refresh() {
        ExpressionScope scope = new SampledScope(text.scope(null, null));
        List<Response> responses = new ArrayList<>(document.entries().size());
        List<MotdPolicy.Candidate> candidates = new ArrayList<>(document.entries().size());
        long now = System.currentTimeMillis();
        int online = ((Number) scope.variable("server.online")).intValue();
        if (text.test(document.show(), scope)) {
            for (ProxyDocuments.MotdEntry entry : document.entries()) {
                if (!text.test(entry.show(), scope) || !entry.select().matchesSnapshot(now, document.state(), online)) {
                    continue;
                }
                int index = responses.size();
                candidates.add(new MotdPolicy.Candidate(index, entry.weight(), entry.select()));
                List<ServerPing.SamplePlayer> sample = new ArrayList<>(entry.sample().size());
                for (int line = 0; line < entry.sample().size(); line++) {
                    sample.add(new ServerPing.SamplePlayer(legacy(entry.sample().get(line), scope), new UUID(0L, line)));
                }
                responses.add(new Response(text.render(String.join("\n&r", entry.lines()), scope),
                    entry.online() == null ? null : count(entry.online(), scope),
                    entry.max() == null ? null : count(entry.max(), scope),
                    entry.version() == null ? null : legacy(entry.version(), scope), List.copyOf(sample),
                    entry.sampleMode(), entry.counts(), document.iconsFor(entry)));
            }
        }
        prepared = new Prepared(List.copyOf(responses), List.copyOf(candidates));
    }

    public ServerPing render(ServerPing original, boolean enabled, MotdPolicy.Request request) {
        Prepared current = prepared;
        if (!enabled || current == null) {
            return original;
        }
        long position = document.rotation().position(sequence, System.currentTimeMillis());
        RandomGenerator random = ThreadLocalRandom.current();
        int index = document.rotation().choose(current.candidates(), request, position, random);
        if (index < 0) {
            return original;
        }
        Response response = current.responses().get(index);
        ServerPing.Builder builder = original.asBuilder().description(response.description());
        MotdPolicy.Counts counts = response.counts();
        if (!counts.onlineMode().equals("inherit")) {
            builder.onlinePlayers(MotdPolicy.count(counts.onlineMode(), counts.onlineValue(), builder.getOnlinePlayers()));
        } else if (response.online() != null) {
            builder.onlinePlayers(response.online());
        }
        if (!counts.maximumMode().equals("inherit")) {
            builder.maximumPlayers(MotdPolicy.count(counts.maximumMode(), counts.maximumValue(), builder.getMaximumPlayers()));
        } else if (response.maximum() != null) {
            builder.maximumPlayers(response.maximum());
        }
        if (response.version() != null) {
            builder.version(new ServerPing.Version(original.getVersion().getProtocol(), response.version()));
        }
        if (!response.sampleMode().equals("inherit")) {
            builder.clearSamplePlayers();
            if (response.sampleMode().equals("replace")) {
                builder.samplePlayers(response.sample());
            }
        }
        if (counts.hide()) {
            builder.nullPlayers();
        }
        int iconIndex = document.rotation().icon(response.icons().size(), position, random);
        if (iconIndex >= 0) {
            Favicon favicon = icons.get(response.icons().get(iconIndex));
            if (favicon != null) {
                builder.favicon(favicon);
            }
        }
        return builder.build();
    }

    private static void loadIcon(Map<String, Favicon> loaded, Path images, String path) throws IOException {
        if (path == null || path.isBlank() || loaded.containsKey(path)) {
            return;
        }
        Path file = images.resolve(path).normalize();
        if (!file.startsWith(images)) {
            throw new IllegalArgumentException("MOTD favicon must be inside images/");
        }
        if (!file.toRealPath().startsWith(images.toRealPath())) {
            throw new IllegalArgumentException("MOTD favicon must remain inside images/");
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
    private static final class SampledScope implements ExpressionScope {
        private final ExpressionScope source;
        private final Map<String, Object> values = new HashMap<>();

        private SampledScope(ExpressionScope source) {
            this.source = source;
        }

        @Override
        public Object variable(String name) {
            return values.computeIfAbsent(name, source::variable);
        }

        @Override
        public Object call(String name, List<Object> arguments) {
            return source.call(name, arguments);
        }
    }

    private record Prepared(List<Response> responses, List<MotdPolicy.Candidate> candidates) {
    }

    private record Response(Component description, Integer online, Integer maximum, String version,
                            List<ServerPing.SamplePlayer> sample, String sampleMode, MotdPolicy.Counts counts,
                            List<String> icons) {
    }

}
