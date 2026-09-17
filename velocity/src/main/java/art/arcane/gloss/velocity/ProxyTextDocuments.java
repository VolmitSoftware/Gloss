package art.arcane.gloss.velocity;

import art.arcane.gloss.animation.AnimationClip;
import art.arcane.gloss.animation.AnimationMode;
import art.arcane.gloss.emoji.UnicodeText;
import art.arcane.gloss.expr.Expr;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.stream.Stream;

/**
 * The text catalogs the proxy render pipeline consumes: {@code emoji/*.json} (schema 1, one emoji
 * per file named by id) and {@code animations/*.json} (schema 1 frame clips referenced as
 * {@code |animation.<id>|}).
 */
public final class ProxyTextDocuments {
    private static final int SCHEMA_VERSION = 1;
    private static final long MIN_FRAME_INTERVAL_MS = 1L;
    private static final long MAX_FRAME_INTERVAL_MS = 60_000L;
    private static final String EXTENSION = ".json";

    private ProxyTextDocuments() {
    }

    public static Content load(Path directory, ProxyDocuments.Settings settings) throws IOException {
        List<Emoji> emoji = new ArrayList<>();
        for (Path path : documents(directory.resolve("emoji"))) {
            JsonObject document = ProxyDocuments.read(path, SCHEMA_VERSION);
            if (document.isEmpty()) {
                continue;
            }
            emoji.add(emoji(id(path), document));
        }
        emoji.sort(Comparator.comparing(Emoji::id));
        List<Path> paths = documents(directory.resolve("animations"));
        Map<String, Animation> animations = new HashMap<>(Math.max(4, paths.size() * 2));
        for (Path path : paths) {
            JsonObject document = ProxyDocuments.read(path, SCHEMA_VERSION);
            if (document.isEmpty()) {
                continue;
            }
            Animation animation = animation(id(path), document);
            animations.put(animation.id(), animation);
        }
        return new Content(settings.emoji(), settings.animations(), emoji, animations);
    }

    private static List<Path> documents(Path directory) throws IOException {
        if (!Files.isDirectory(directory)) {
            return List.of();
        }
        try (Stream<Path> paths = Files.list(directory)) {
            return paths.filter(path -> path.getFileName().toString().endsWith(EXTENSION)).sorted().toList();
        }
    }

    private static String id(Path path) {
        String name = path.getFileName().toString();
        return name.substring(0, name.length() - EXTENSION.length());
    }

    private static Emoji emoji(String id, JsonObject document) {
        String value = literal(document, "emoji");
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException("emoji document requires an emoji value: " + id);
        }
        return new Emoji(id, ProxyDocuments.string(document, "trigger", ""), UnicodeText.parse(value),
            ProxyDocuments.bool(document, "enabled", true), ProxyDocuments.expression(document, "show", "true"));
    }

    private static Animation animation(String id, JsonObject document) {
        String mode = literal(document, "mode");
        if (mode == null || mode.isBlank()) {
            throw new IllegalArgumentException("animation requires a mode: " + id);
        }
        long interval = Math.clamp(number(document, "frameIntervalMs"), MIN_FRAME_INTERVAL_MS, MAX_FRAME_INTERVAL_MS);
        AnimationClip clip = new AnimationClip(id, 1000.0D / interval, mode(mode, id),
            frames(ProxyDocuments.array(document, "frames"), id));
        return new Animation(id, ProxyDocuments.expression(document, "show", "true"), clip);
    }

    private static AnimationMode mode(String mode, String id) {
        try {
            return AnimationMode.valueOf(mode.toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException unknown) {
            throw new IllegalArgumentException("unknown animation mode: " + mode + " in " + id);
        }
    }

    private static List<String> frames(JsonArray array, String id) {
        if (array.isEmpty()) {
            throw new IllegalArgumentException("animation requires at least one frame: " + id);
        }
        List<String> frames = new ArrayList<>(array.size());
        for (JsonElement element : array) {
            String frame = element.isJsonNull() ? "" : element.getAsString();
            ProxyText.validateTemplate(frame);
            frames.add(frame);
        }
        return List.copyOf(frames);
    }

    private static String literal(JsonObject document, String key) {
        return document.has(key) && !document.get(key).isJsonNull() ? document.get(key).getAsString() : null;
    }

    private static long number(JsonObject document, String key) {
        return document.has(key) && !document.get(key).isJsonNull() ? document.get(key).getAsLong() : 0L;
    }

    public record Content(boolean emojiEnabled, boolean animationsEnabled, List<Emoji> emoji,
                          Map<String, Animation> animations) {
        public static final Content EMPTY = new Content(false, false, List.of(), Map.of());

        public Content {
            emoji = List.copyOf(emoji);
            animations = Map.copyOf(animations);
        }
    }

    public record Emoji(String id, String trigger, String emoji, boolean enabled, Expr show) {
        public String token() {
            return ":" + id + ":";
        }
    }

    public record Animation(String id, Expr show, AnimationClip clip) {
    }
}
