package art.arcane.gloss.glosspack;

import art.arcane.gloss.doc.AtomicFiles;
import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.stream.Stream;

/** Reads and writes the {@code packs/&lt;id&gt;.json} ledgers. */
public final class GlossPackLedgers {
    static final String DIRECTORY = "packs";
    private static final Gson GSON = new GsonBuilder()
            .serializeNulls()
            .disableHtmlEscaping()
            .setPrettyPrinting()
            .create();

    private final Path root;

    public GlossPackLedgers(Path dataDirectory) {
        this.root = Objects.requireNonNull(dataDirectory, "dataDirectory")
                .toAbsolutePath().normalize().resolve(DIRECTORY);
    }

    public Path file(String id) {
        Path target = root.resolve(id + ".json").normalize();
        if (!target.startsWith(root)) {
            throw new IllegalArgumentException("pack id escapes the packs folder: " + id);
        }
        return target;
    }

    public GlossPackLedger read(String id) {
        Path file = file(id);
        if (!Files.isRegularFile(file, LinkOption.NOFOLLOW_LINKS)) {
            return null;
        }
        try {
            JsonObject stored = JsonParser.parseString(
                    Files.readString(file, StandardCharsets.UTF_8)).getAsJsonObject();
            GlossPackManifest manifest = GlossPackManifest.parse(
                    stored.getAsJsonObject("manifest").toString());
            Map<String, String> hashes = new LinkedHashMap<>();
            for (Map.Entry<String, JsonElement> entry
                    : stored.getAsJsonObject("installedHashes").entrySet()) {
                hashes.put(entry.getKey(), entry.getValue().getAsString());
            }
            return new GlossPackLedger(manifest,
                    stored.has("source") ? stored.get("source").getAsString() : "",
                    stored.has("installedAt") ? stored.get("installedAt").getAsLong() : 0L,
                    hashes);
        } catch (IOException | RuntimeException failure) {
            throw new IllegalStateException("pack ledger is unreadable: " + file, failure);
        }
    }

    public List<GlossPackLedger> all() {
        if (!Files.isDirectory(root, LinkOption.NOFOLLOW_LINKS)) {
            return List.of();
        }
        List<GlossPackLedger> ledgers = new ArrayList<>();
        try (Stream<Path> files = Files.list(root)) {
            for (Path file : files.sorted(Comparator.comparing(Path::getFileName)).toList()) {
                String name = file.getFileName().toString();
                if (!name.endsWith(".json")) {
                    continue;
                }
                GlossPackLedger ledger = read(name.substring(0, name.length() - ".json".length()));
                if (ledger != null) {
                    ledgers.add(ledger);
                }
            }
        } catch (IOException failure) {
            throw new IllegalStateException("cannot list installed packs", failure);
        }
        return List.copyOf(ledgers);
    }

    byte[] serialize(GlossPackLedger ledger) {
        JsonObject manifest = JsonParser.parseString(GSON.toJson(ledger.manifest()))
                .getAsJsonObject();
        manifest.addProperty("format", GlossPackManifest.FORMAT);
        manifest.addProperty("version", GlossPackManifest.VERSION);
        JsonObject stored = new JsonObject();
        stored.add("manifest", manifest);
        stored.addProperty("source", ledger.source());
        stored.addProperty("installedAt", ledger.installedAt());
        JsonObject hashes = new JsonObject();
        ledger.installedHashes().entrySet().stream()
                .sorted(Map.Entry.comparingByKey())
                .forEach(entry -> hashes.addProperty(entry.getKey(), entry.getValue()));
        stored.add("installedHashes", hashes);
        return (GSON.toJson(stored) + System.lineSeparator()).getBytes(StandardCharsets.UTF_8);
    }

    void write(GlossPackLedger ledger) throws IOException {
        AtomicFiles.replace(file(ledger.id()), serialize(ledger));
    }
}
