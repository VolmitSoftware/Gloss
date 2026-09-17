package art.arcane.gloss.velocity;

import art.arcane.gloss.expr.Expr;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.net.URISyntaxException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.function.Function;
import java.util.stream.Stream;

public final class ProxyDocuments {
    private static final int MAX_LINKS = 16;
    private static final Set<String> LINK_TYPES = Set.of("report_bug", "community_guidelines", "support",
        "status", "feedback", "community", "website", "forums", "news", "announcements");

    private ProxyDocuments() {
    }

    public static void seed(Path directory) throws IOException {
        for (String name : bundledDefaults()) {
            Path target = directory.resolve(name);
            if (Files.exists(target)) {
                continue;
            }
            Files.createDirectories(target.getParent());
            try (InputStream input = ProxyDocuments.class.getResourceAsStream("/proxy-defaults/" + name)) {
                if (input == null) {
                    throw new IOException("Missing bundled proxy default " + name);
                }
                Files.copy(input, target);
            }
        }
    }

    /** Every bundled proxy default, from the manifest the build writes next to them. */
    static List<String> bundledDefaults() throws IOException {
        try (InputStream input = ProxyDocuments.class.getResourceAsStream("/proxy-defaults/manifest.txt")) {
            if (input == null) {
                throw new IOException("Missing bundled proxy default manifest");
            }
            List<String> names = new ArrayList<>();
            for (String line : new String(input.readAllBytes(), StandardCharsets.UTF_8).split("\n")) {
                String name = line.trim();
                if (!name.isEmpty()) {
                    names.add(name);
                }
            }
            return List.copyOf(names);
        }
    }

    public static Snapshot load(Path directory) throws IOException {
        JsonObject config = read(directory.resolve("proxy.json"), 1);
        Settings settings = new Settings(enabled(config, "motd"), enabled(config, "tablist"),
            enabled(config, "scoreboards"), enabled(config, "surfaces"), enabled(config, "connections"),
            enabled(config, "emoji"), enabled(config, "animations"),
            Math.clamp(integer(config, "refreshMillis", 500), 50, 60000), bool(config, "networkTablist", true));
        JsonObject motd = read(directory.resolve("motd.json"), 1);
        String motdFavicon = string(motd, "favicon", null);
        if (motdFavicon != null && motdFavicon.isBlank()) {
            motdFavicon = null;
        }
        List<MotdEntry> entries = new ArrayList<>();
        for (JsonElement element : array(motd, "entries")) {
            JsonObject entry = element.getAsJsonObject();
            List<String> lines = strings(array(entry, "lines"));
            if (lines.isEmpty() || lines.size() > 2) {
                throw new IllegalArgumentException("MOTD requires one or two lines");
            }
            List<String> sample = strings(array(entry, "sample"));
            if (sample.size() > 12) {
                throw new IllegalArgumentException("MOTD sample supports at most twelve lines");
            }
            entries.add(new MotdEntry(lines, string(entry, "favicon", null), sample,
                count(entry, "online"), count(entry, "max"), string(entry, "version", null)));
        }
        if (!motd.isEmpty() && entries.isEmpty()) {
            throw new IllegalArgumentException("MOTD requires at least one entry");
        }
        JsonArray linkArray = array(motd, "links");
        if (linkArray.size() > MAX_LINKS) {
            throw new IllegalArgumentException("MOTD supports at most " + MAX_LINKS + " links");
        }
        List<MotdLink> links = new ArrayList<>(linkArray.size());
        for (JsonElement element : linkArray) {
            JsonObject link = element.getAsJsonObject();
            links.add(new MotdLink(string(link, "type", null), string(link, "label", null),
                string(link, "url", null)));
        }
        JsonObject tab = read(directory.resolve("tablist.json"), 2);
        JsonObject sorting = object(tab, "sort");
        Expr sort = bool(sorting, "enabled", false) ? expression(sorting, "weight", "0") : null;
        Tablist tablist = new Tablist(expression(tab, "show", tab.isEmpty() ? "false" : "true"),
            surface(object(tab, "headerFooter"), p -> new HeaderFooter(string(p, "header", ""), string(p, "footer", ""))),
            surface(object(tab, "listNames"), p -> new ListName(string(p, "format", "$player"))), sort);
        List<Board> boards = new ArrayList<>();
        Path boardDirectory = directory.resolve("boards");
        if (Files.isDirectory(boardDirectory)) {
            try (Stream<Path> paths = Files.list(boardDirectory)) {
                for (Path path : paths.filter(p -> p.getFileName().toString().endsWith(".json")).sorted().toList()) {
                    JsonObject board = read(path, 2);
                    if (board.isEmpty()) {
                        continue;
                    }
                    JsonObject selection = object(board, "select");
                    boards.add(new Board(path.getFileName().toString(), expression(board, "show", "true"),
                        integer(selection, "priority", 0), expression(selection, "when", "false"),
                        presentation(object(board, "presentation")), variants(board, ProxyDocuments::presentation)));
                }
            }
        }
        boards.sort(Comparator.comparingInt(Board::priority).reversed().thenComparing(Board::id));
        return new Snapshot(settings, new Motd(expression(motd, "show", motd.isEmpty() ? "false" : "true"),
            motdFavicon, List.copyOf(entries), List.copyOf(links)), tablist, List.copyOf(boards),
            ProxySurfaceDocuments.load(directory), ProxyConnectionDocuments.load(directory),
            ProxyTextDocuments.load(directory, settings));
    }

    static JsonObject read(Path path, int schema) throws IOException {
        if (!Files.exists(path)) {
            return new JsonObject();
        }
        if (Files.size(path) > 1_048_576) {
            throw new IOException("Document exceeds one MiB: " + path);
        }
        try {
            JsonObject object = JsonParser.parseString(Files.readString(path)).getAsJsonObject();
            if (integer(object, "schemaVersion", 0) != schema) {
                return new JsonObject();
            }
            return object;
        } catch (RuntimeException failure) {
            throw new IOException("Invalid document " + path, failure);
        }
    }

    static <T> Surface<T> surface(JsonObject object, Function<JsonObject, T> parser) {
        return new Surface<>(bool(object, "enabled", false), expression(object, "show", "true"),
            parser.apply(object(object, "presentation")), variants(object, parser));
    }

    static <T> List<Variant<T>> variants(JsonObject object, Function<JsonObject, T> parser) {
        List<Variant<T>> variants = new ArrayList<>();
        for (JsonElement element : array(object, "variants")) {
            JsonObject variant = element.getAsJsonObject();
            variants.add(new Variant<>(integer(variant, "priority", 0), expression(variant, "when", "false"),
                parser.apply(object(variant, "presentation"))));
        }
        variants.sort(Comparator.comparingInt((Variant<T> variant) -> variant.priority()).reversed());
        return List.copyOf(variants);
    }

    private static BoardPresentation presentation(JsonObject object) {
        List<BoardLine> lines = new ArrayList<>();
        for (JsonElement element : array(object, "lines")) {
            if (element.isJsonPrimitive()) {
                String value = element.getAsString();
                ProxyText.validateTemplate(value);
                lines.add(new BoardLine(value, null, null));
            } else {
                JsonObject line = element.getAsJsonObject();
                lines.add(new BoardLine(string(line, "text", ""), string(line, "value", null), string(line, "format", null)));
            }
        }
        if (lines.size() > 15) {
            throw new IllegalArgumentException("A scoreboard supports at most fifteen lines");
        }
        return new BoardPresentation(string(object, "title", ""), List.copyOf(lines), bool(object, "hideNumbers", false));
    }

    static Expr expression(JsonObject object, String key, String fallback) {
        String source = string(object, key, fallback);
        if (source.length() > 1024) {
            throw new IllegalArgumentException("Expression exceeds 1024 characters: " + key);
        }
        return ProxyText.parseExpression(source);
    }

    static boolean enabled(JsonObject object, String key) {
        return bool(object(object, key), "enabled", true);
    }

    static JsonObject object(JsonObject object, String key) {
        return object.has(key) ? object.getAsJsonObject(key) : new JsonObject();
    }

    static JsonArray array(JsonObject object, String key) {
        return object.has(key) ? object.getAsJsonArray(key) : new JsonArray();
    }

    static String string(JsonObject object, String key, String fallback) {
        String value = object.has(key) && !object.get(key).isJsonNull() ? object.get(key).getAsString() : fallback;
        ProxyText.validateTemplate(value);
        return value;
    }

    private static String count(JsonObject object, String key) {
        String value = string(object, key, null);
        if (value != null && !value.contains("{{") && !value.contains("$") && !value.contains("|")) {
            double count = Double.parseDouble(value);
            if (!Double.isFinite(count)) {
                throw new IllegalArgumentException("MOTD " + key + " must be finite");
            }
        }
        return value;
    }

    static int integer(JsonObject object, String key, int fallback) {
        return object.has(key) ? object.get(key).getAsInt() : fallback;
    }

    static boolean bool(JsonObject object, String key, boolean fallback) {
        return object.has(key) ? object.get(key).getAsBoolean() : fallback;
    }

    static String trimToNull(String value) {
        if (value == null) {
            return null;
        }
        String trimmed = value.trim();
        return trimmed.isEmpty() ? null : trimmed;
    }

    static List<String> strings(JsonArray array) {
        List<String> result = new ArrayList<>(array.size());
        for (JsonElement element : array) {
            String value = element.getAsString();
            ProxyText.validateTemplate(value);
            result.add(value);
        }
        return List.copyOf(result);
    }

    public record Snapshot(Settings settings, Motd motd, Tablist tablist, List<Board> boards,
                           List<ProxySurfaceDocuments.Document> surfaces, ProxyConnectionDocuments.Document connections,
                           ProxyTextDocuments.Content content) {}
    public record Settings(boolean motd, boolean tablist, boolean scoreboards, boolean surfaces, boolean connections,
                           boolean emoji, boolean animations, long refreshMillis, boolean networkTablist) {}
    public record Motd(Expr show, String favicon, List<MotdEntry> entries, List<MotdLink> links) {}
    /** One pause-menu link: a client-known {@code type} or a custom {@code label}, and an http(s) url. */
    public record MotdLink(String type, String label, String url) {
        public MotdLink {
            type = normalizeType(type);
            label = trimToNull(label);
            url = normalizeUrl(url);
            if (type == null && label == null) {
                throw new IllegalArgumentException("MOTD link requires a known type or a label: " + url);
            }
        }

        public boolean isLabelled() {
            return type == null;
        }

        private static String normalizeType(String type) {
            String normalized = trimToNull(type);
            if (normalized == null) {
                return null;
            }
            String lower = normalized.toLowerCase(Locale.ROOT);
            if (!LINK_TYPES.contains(lower)) {
                throw new IllegalArgumentException("MOTD link type must be one of " + LINK_TYPES + ": " + type);
            }
            return lower;
        }

        private static String normalizeUrl(String url) {
            String normalized = trimToNull(url);
            if (normalized == null) {
                throw new IllegalArgumentException("MOTD link requires a url");
            }
            URI parsed;
            try {
                parsed = new URI(normalized);
            } catch (URISyntaxException failure) {
                throw new IllegalArgumentException("MOTD link url is not a valid URI: " + url, failure);
            }
            String scheme = parsed.getScheme() == null ? "" : parsed.getScheme().toLowerCase(Locale.ROOT);
            if (!scheme.equals("http") && !scheme.equals("https") || parsed.getHost() == null) {
                throw new IllegalArgumentException("MOTD link url must be an http or https address: " + url);
            }
            return normalized;
        }
    }
    public record MotdEntry(List<String> lines, String favicon, List<String> sample, String online, String max, String version) {}
    public record Tablist(Expr show, Surface<HeaderFooter> headerFooter, Surface<ListName> listNames, Expr sortWeight) {}
    public record Surface<T>(boolean enabled, Expr show, T presentation, List<Variant<T>> variants) {}
    public record Variant<T>(int priority, Expr when, T presentation) {}
    public record HeaderFooter(String header, String footer) {}
    public record ListName(String format) {}
    public record Board(String id, Expr show, int priority, Expr when, BoardPresentation presentation, List<Variant<BoardPresentation>> variants) {}
    public record BoardPresentation(String title, List<BoardLine> lines, boolean hideNumbers) {}
    public record BoardLine(String text, String value, String format) {
        public BoardLine {
            if (format != null && !List.of("blank", "number", "styled", "fixed").contains(format)) {
                throw new IllegalArgumentException("Unknown scoreboard line format: " + format);
            }
        }
    }
}
