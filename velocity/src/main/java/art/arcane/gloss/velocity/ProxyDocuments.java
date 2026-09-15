package art.arcane.gloss.velocity;

import art.arcane.gloss.expr.Expr;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.function.Function;
import java.util.stream.Stream;

public final class ProxyDocuments {
    private ProxyDocuments() {
    }

    public static void seed(Path directory) throws IOException {
        for (String name : List.of("proxy.json", "motd.json", "tablist.json", "boards/default.json")) {
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

    public static Snapshot load(Path directory) throws IOException {
        JsonObject config = read(directory.resolve("proxy.json"), 1);
        Settings settings = new Settings(enabled(config, "motd"), enabled(config, "tablist"),
            enabled(config, "scoreboards"), Math.clamp(integer(config, "refreshMillis", 500), 50, 60000),
            bool(config, "networkTablist", true));
        JsonObject motd = read(directory.resolve("motd.json"), 1);
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
        return new Snapshot(settings, new Motd(expression(motd, "show", motd.isEmpty() ? "false" : "true"), List.copyOf(entries)),
            tablist, List.copyOf(boards));
    }

    private static JsonObject read(Path path, int schema) throws IOException {
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

    private static <T> Surface<T> surface(JsonObject object, Function<JsonObject, T> parser) {
        return new Surface<>(bool(object, "enabled", false), expression(object, "show", "true"),
            parser.apply(object(object, "presentation")), variants(object, parser));
    }

    private static <T> List<Variant<T>> variants(JsonObject object, Function<JsonObject, T> parser) {
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

    private static Expr expression(JsonObject object, String key, String fallback) {
        String source = string(object, key, fallback);
        if (source.length() > 1024) {
            throw new IllegalArgumentException("Expression exceeds 1024 characters: " + key);
        }
        return ProxyText.parseExpression(source);
    }

    private static boolean enabled(JsonObject object, String key) {
        return bool(object(object, key), "enabled", true);
    }

    private static JsonObject object(JsonObject object, String key) {
        return object.has(key) ? object.getAsJsonObject(key) : new JsonObject();
    }

    private static JsonArray array(JsonObject object, String key) {
        return object.has(key) ? object.getAsJsonArray(key) : new JsonArray();
    }

    private static String string(JsonObject object, String key, String fallback) {
        String value = object.has(key) && !object.get(key).isJsonNull() ? object.get(key).getAsString() : fallback;
        ProxyText.validateTemplate(value);
        return value;
    }

    private static String count(JsonObject object, String key) {
        String value = string(object, key, null);
        if (value != null && !value.contains("{{") && !value.contains("$")) {
            double count = Double.parseDouble(value);
            if (!Double.isFinite(count)) {
                throw new IllegalArgumentException("MOTD " + key + " must be finite");
            }
        }
        return value;
    }

    private static int integer(JsonObject object, String key, int fallback) {
        return object.has(key) ? object.get(key).getAsInt() : fallback;
    }

    private static boolean bool(JsonObject object, String key, boolean fallback) {
        return object.has(key) ? object.get(key).getAsBoolean() : fallback;
    }

    private static List<String> strings(JsonArray array) {
        List<String> result = new ArrayList<>(array.size());
        for (JsonElement element : array) {
            String value = element.getAsString();
            ProxyText.validateTemplate(value);
            result.add(value);
        }
        return List.copyOf(result);
    }

    public record Snapshot(Settings settings, Motd motd, Tablist tablist, List<Board> boards) {}
    public record Settings(boolean motd, boolean tablist, boolean scoreboards, long refreshMillis, boolean networkTablist) {}
    public record Motd(Expr show, List<MotdEntry> entries) {}
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
