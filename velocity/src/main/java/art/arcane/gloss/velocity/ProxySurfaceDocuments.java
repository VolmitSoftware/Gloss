package art.arcane.gloss.velocity;

import art.arcane.gloss.expr.Expr;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.stream.Stream;

/**
 * The proxy's {@code surfaces/*.json} folder: schema-1 action bar, boss bar and title documents in
 * the same shape the server edition reads. HUD slots have no meaning on the proxy and are ignored.
 */
public final class ProxySurfaceDocuments {
    public static final List<String> KINDS = List.of("actionbar", "bossbar", "title");
    public static final List<String> TRIGGERS = List.of("select", "once", "repeat");
    public static final List<String> COLORS = List.of("pink", "blue", "red", "green", "yellow", "purple", "white");
    public static final List<String> STYLES = List.of("solid", "segmented_6", "segmented_10", "segmented_12",
        "segmented_20");
    public static final int MAX_TTL_TICKS = 1200;
    public static final int MAX_FADE_TICKS = 1200;
    public static final int MAX_REPEAT_TICKS = 72_000;
    public static final int DEFAULT_FADE_IN_TICKS = 10;
    public static final int DEFAULT_STAY_TICKS = 40;
    public static final int DEFAULT_FADE_OUT_TICKS = 10;
    public static final String DEFAULT_TRIGGER = "select";
    private static final int SCHEMA_VERSION = 1;
    private static final String FOLDER = "surfaces";
    private static final String SUFFIX = ".json";
    private static final Expr FULL_PROGRESS = ProxyText.parseExpression("1");

    private ProxySurfaceDocuments() {
    }

    public static List<Document> load(Path directory) throws IOException {
        Path folder = directory.resolve(FOLDER);
        if (!Files.isDirectory(folder)) {
            return List.of();
        }
        List<Document> documents = new ArrayList<>();
        try (Stream<Path> paths = Files.list(folder)) {
            for (Path path : paths.filter(file -> file.getFileName().toString().endsWith(SUFFIX)).sorted().toList()) {
                JsonObject object = ProxyDocuments.read(path, SCHEMA_VERSION);
                if (object.isEmpty()) {
                    continue;
                }
                String name = path.getFileName().toString();
                documents.add(document(name.substring(0, name.length() - SUFFIX.length()), object));
            }
        }
        documents.sort(Comparator.comparingInt(Document::priority).reversed().thenComparing(Document::id));
        return List.copyOf(documents);
    }

    private static Document document(String id, JsonObject object) {
        String kind = name(ProxyDocuments.string(object, "surface", null), KINDS, "surface " + id);
        if (kind == null) {
            throw new IllegalArgumentException("surface " + id
                + " requires a surface of actionbar, bossbar or title");
        }
        JsonObject selection = ProxyDocuments.object(object, "select");
        return new Document(id, kind, ProxyDocuments.expression(object, "show", "true"),
            ProxyDocuments.integer(selection, "priority", 0),
            ProxyDocuments.expression(selection, "when", "false"),
            presentation(object, kind, "surfaces." + id + ".presentation"), variants(object, id, kind));
    }

    private static List<Variant> variants(JsonObject object, String id, String kind) {
        List<Variant> variants = new ArrayList<>();
        Set<String> ids = new HashSet<>();
        for (JsonElement element : ProxyDocuments.array(object, "variants")) {
            JsonObject variant = element.getAsJsonObject();
            String variantId = variantId(ProxyDocuments.string(variant, "id", null));
            if (!ids.add(variantId)) {
                throw new IllegalArgumentException("surface variant id is duplicated: " + variantId);
            }
            variants.add(new Variant(variantId, ProxyDocuments.integer(variant, "priority", 0),
                ProxyDocuments.expression(variant, "when", "false"),
                presentation(variant, kind, "surfaces." + id + ".variants." + variantId + ".presentation")));
        }
        variants.sort(Comparator.comparingInt(Variant::priority).reversed().thenComparing(Variant::id));
        return List.copyOf(variants);
    }

    private static Presentation presentation(JsonObject owner, String kind, String path) {
        if (!owner.has("presentation")) {
            throw new IllegalArgumentException(path + " is required");
        }
        JsonObject object = ProxyDocuments.object(owner, "presentation");
        return switch (kind) {
            case "actionbar" -> actionBar(object, path);
            case "bossbar" -> bossBar(object, path);
            default -> title(object, path);
        };
    }

    private static Presentation actionBar(JsonObject object, String path) {
        String text = trimToNull(ProxyDocuments.string(object, "text", null));
        if (text == null) {
            throw new IllegalArgumentException(path + " requires text for an actionbar surface");
        }
        return new Presentation(text, null, null, null, null, null, ticks(object, "ttlTicks", 1, MAX_TTL_TICKS),
            null, null, null, null, null);
    }

    private static Presentation bossBar(JsonObject object, String path) {
        String title = trimToNull(ProxyDocuments.string(object, "title", null));
        if (title == null) {
            throw new IllegalArgumentException(path + " requires a title for a bossbar surface");
        }
        String color = name(ProxyDocuments.string(object, "color", null), COLORS, path + " color");
        String style = name(ProxyDocuments.string(object, "style", null), STYLES, path + " style");
        return new Presentation(null, title, null, progress(ProxyDocuments.string(object, "progress", null)),
            color == null ? "white" : color, style == null ? "solid" : style,
            ticks(object, "ttlTicks", 1, MAX_TTL_TICKS), null, null, null, null, null);
    }

    private static Presentation title(JsonObject object, String path) {
        String title = trimToNull(ProxyDocuments.string(object, "title", null));
        if (title == null) {
            throw new IllegalArgumentException(path + " requires a title for a title surface");
        }
        String subtitle = ProxyDocuments.string(object, "subtitle", "");
        String trigger = name(ProxyDocuments.string(object, "trigger", null), TRIGGERS, path + " trigger");
        String resolvedTrigger = trigger == null ? DEFAULT_TRIGGER : trigger;
        Integer fadeIn = ticks(object, "fadeInTicks", 0, MAX_FADE_TICKS);
        Integer configuredStay = ticks(object, "stayTicks", 0, MAX_FADE_TICKS);
        Integer fadeOut = ticks(object, "fadeOutTicks", 0, MAX_FADE_TICKS);
        Integer repeat = ticks(object, "repeatTicks", 1, MAX_REPEAT_TICKS);
        int stay = configuredStay == null ? DEFAULT_STAY_TICKS : configuredStay.intValue();
        if (resolvedTrigger.equals("repeat")) {
            repeat = Integer.valueOf(Math.max(stay, repeat == null ? stay : repeat.intValue()));
        }
        return new Presentation(null, title, subtitle == null ? "" : subtitle, null, null, null,
            ticks(object, "ttlTicks", 1, MAX_TTL_TICKS),
            Integer.valueOf(fadeIn == null ? DEFAULT_FADE_IN_TICKS : fadeIn.intValue()), Integer.valueOf(stay),
            Integer.valueOf(fadeOut == null ? DEFAULT_FADE_OUT_TICKS : fadeOut.intValue()), resolvedTrigger, repeat);
    }

    private static Expr progress(String source) {
        String normalized = trimToNull(source);
        if (normalized == null) {
            return FULL_PROGRESS;
        }
        if (normalized.startsWith("{{") && normalized.endsWith("}}")) {
            normalized = normalized.substring(2, normalized.length() - 2).trim();
        }
        return ProxyText.parseExpression(normalized);
    }

    private static Integer ticks(JsonObject object, String key, int min, int max) {
        if (!object.has(key)) {
            return null;
        }
        return Integer.valueOf(Math.clamp(ProxyDocuments.integer(object, key, min), min, max));
    }

    private static String name(String value, List<String> allowed, String owner) {
        String normalized = value == null ? null : value.trim().toLowerCase(Locale.ROOT);
        if (normalized == null || normalized.isEmpty()) {
            return null;
        }
        if (!allowed.contains(normalized)) {
            throw new IllegalArgumentException(owner + " must be one of " + String.join(", ", allowed) + ": " + value);
        }
        return normalized;
    }

    private static String variantId(String id) {
        String normalized = id == null ? "" : id.trim();
        if (normalized.isEmpty()) {
            throw new IllegalArgumentException("surface variant id may not be blank");
        }
        for (int index = 0; index < normalized.length(); index++) {
            char character = normalized.charAt(index);
            if (!Character.isLetterOrDigit(character) && character != '-' && character != '_' && character != '.') {
                throw new IllegalArgumentException("surface variant id contains an unsupported character: " + id);
            }
        }
        return normalized;
    }

    private static String trimToNull(String value) {
        if (value == null) {
            return null;
        }
        return value.isBlank() ? null : value;
    }

    public record Document(String id, String kind, Expr show, int priority, Expr when, Presentation presentation,
                           List<Variant> variants) {
    }

    public record Variant(String id, int priority, Expr when, Presentation presentation) {
    }

    public record Presentation(String text, String title, String subtitle, Expr progress, String color,
                               String style, Integer ttlTicks, Integer fadeInTicks, Integer stayTicks,
                               Integer fadeOutTicks, String trigger, Integer repeatTicks) {
    }
}
