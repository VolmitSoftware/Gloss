package art.arcane.gloss.glosspack;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.regex.Pattern;

/** The {@code manifest.json} of a {@code .glosspack}: what it is, what it needs, what it carries. */
public record GlossPackManifest(String id, String name, String packVersion, String author,
                                Requirements requires, List<DocumentRef> documents,
                                List<ImageRef> images, boolean serverCommands) {
    public static final String FORMAT = "glosspack";
    public static final int VERSION = 1;
    public static final String FILE_NAME = "manifest.json";
    private static final Pattern ID = Pattern.compile("[a-z0-9][a-z0-9-]{0,63}");
    private static final Pattern SHA256 = Pattern.compile("[0-9a-f]{64}");
    private static final int MAX_ENTRIES = 512;

    public GlossPackManifest {
        if (id == null || !ID.matcher(id).matches()) {
            throw new IllegalArgumentException("pack id must be a lowercase slug: " + id);
        }
        if (name == null || name.isBlank() || name.length() > 128) {
            throw new IllegalArgumentException("pack name is invalid");
        }
        if (!GlossPackVersions.isSemver(packVersion)) {
            throw new IllegalArgumentException("pack packVersion must be a version: " + packVersion);
        }
        author = author == null ? "" : author;
        requires = Objects.requireNonNull(requires, "requires");
        documents = List.copyOf(documents);
        images = List.copyOf(images);
        if (documents.size() + images.size() > MAX_ENTRIES) {
            throw new IllegalArgumentException("pack declares more than " + MAX_ENTRIES + " files");
        }
    }

    public static GlossPackManifest parse(String source) {
        JsonElement parsed = JsonParser.parseString(Objects.requireNonNull(source, "source"));
        if (!parsed.isJsonObject()) {
            throw new IllegalArgumentException("pack manifest must be a JSON object");
        }
        JsonObject manifest = parsed.getAsJsonObject();
        if (!FORMAT.equals(string(manifest, "format"))) {
            throw new IllegalArgumentException("not a Gloss pack manifest");
        }
        if (number(manifest, "version") != VERSION) {
            throw new IllegalArgumentException("unsupported pack manifest version");
        }
        return new GlossPackManifest(string(manifest, "id"), string(manifest, "name"),
                string(manifest, "packVersion"), optionalString(manifest, "author"),
                requirements(manifest), documents(manifest), images(manifest),
                manifest.has("serverCommands") && manifest.get("serverCommands").getAsBoolean());
    }

    /** Everything about this server that stops the pack installing, in operator words. */
    public List<String> unmetRequirements(GlossPackEnvironment environment) {
        List<String> unmet = new ArrayList<>();
        if (!requires.gloss().isBlank()
                && !GlossPackVersions.satisfies(requires.gloss(), environment.glossVersion())) {
            unmet.add("requires Gloss " + requires.gloss() + ", this server is "
                    + environment.glossVersion());
        }
        for (String plugin : requires.plugins()) {
            if (!environment.installedPlugins().contains(plugin)) {
                unmet.add("requires plugin " + plugin);
            }
        }
        return List.copyOf(unmet);
    }

    public boolean allowsServerCommands(GlossPackEnvironment environment) {
        return serverCommands && environment.allowServerCommands();
    }

    private static Requirements requirements(JsonObject manifest) {
        if (!manifest.has("requires")) {
            return new Requirements("", Map.of(), List.of(), List.of());
        }
        JsonObject requires = manifest.getAsJsonObject("requires");
        Map<String, Integer> schemas = new LinkedHashMap<>();
        if (requires.has("schemas")) {
            for (Map.Entry<String, JsonElement> schema
                    : requires.getAsJsonObject("schemas").entrySet()) {
                schemas.put(schema.getKey(), schema.getValue().getAsInt());
            }
        }
        return new Requirements(optionalString(requires, "gloss"), Map.copyOf(schemas),
                strings(requires, "plugins"), strings(requires, "itemProviders"));
    }

    private static List<DocumentRef> documents(JsonObject manifest) {
        List<DocumentRef> documents = new ArrayList<>();
        for (JsonElement value : array(manifest, "documents")) {
            JsonObject document = value.getAsJsonObject();
            documents.add(new DocumentRef(string(document, "kind"), string(document, "id"),
                    hash(document)));
        }
        return List.copyOf(documents);
    }

    private static List<ImageRef> images(JsonObject manifest) {
        List<ImageRef> images = new ArrayList<>();
        for (JsonElement value : array(manifest, "images")) {
            JsonObject image = value.getAsJsonObject();
            images.add(new ImageRef(string(image, "path"), hash(image)));
        }
        return List.copyOf(images);
    }

    private static String hash(JsonObject entry) {
        String sha256 = string(entry, "sha256");
        if (!SHA256.matcher(sha256).matches()) {
            throw new IllegalArgumentException("pack entry sha256 is invalid: " + sha256);
        }
        return sha256;
    }

    private static JsonArray array(JsonObject object, String field) {
        return object.has(field) ? object.getAsJsonArray(field) : new JsonArray();
    }

    private static List<String> strings(JsonObject object, String field) {
        List<String> values = new ArrayList<>();
        for (JsonElement value : array(object, field)) {
            values.add(value.getAsString());
        }
        return List.copyOf(values);
    }

    private static String string(JsonObject object, String field) {
        JsonElement value = object.get(field);
        if (value == null || !value.isJsonPrimitive() || !value.getAsJsonPrimitive().isString()
                || value.getAsString().isBlank()) {
            throw new IllegalArgumentException("pack manifest " + field + " must be a string");
        }
        return value.getAsString();
    }

    private static String optionalString(JsonObject object, String field) {
        JsonElement value = object.get(field);
        return value == null || !value.isJsonPrimitive() ? "" : value.getAsString();
    }

    private static int number(JsonObject object, String field) {
        JsonElement value = object.get(field);
        if (value == null || !value.isJsonPrimitive() || !value.getAsJsonPrimitive().isNumber()) {
            throw new IllegalArgumentException("pack manifest " + field + " must be a number");
        }
        return value.getAsInt();
    }

    /** What the pack needs before it will install. */
    public record Requirements(String gloss, Map<String, Integer> schemas, List<String> plugins,
                               List<String> itemProviders) {
        public Requirements {
            gloss = gloss == null ? "" : gloss;
            schemas = Map.copyOf(schemas);
            plugins = List.copyOf(plugins);
            itemProviders = List.copyOf(itemProviders);
        }

        /** The declared kinds, so a pack naming a kind this build has no folder for is caught. */
        public Set<String> schemaKinds() {
            return schemas.keySet();
        }
    }

    /** One document the pack carries, addressed by its data-folder collection and id. */
    public record DocumentRef(String kind, String id, String sha256) {
        public String archivePath() {
            return "documents/" + kind + "/" + id + ".json";
        }
    }

    /** One image the pack carries, relative to the {@code images/} folder. */
    public record ImageRef(String path, String sha256) {
        public String archivePath() {
            return "images/" + path;
        }
    }
}
