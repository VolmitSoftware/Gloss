package art.arcane.gloss.names;

import art.arcane.gloss.doc.DocumentEnvelope;
import art.arcane.gloss.doc.DocumentParsers;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import java.util.HashMap;
import java.util.Map;
import java.util.Objects;

public record NamesDoc(int schemaVersion, long revision, Map<String, String> materials,
                       Map<String, String> entities, Map<String, String> worlds,
                       Map<String, String> gameModes, Map<String, String> dimensions,
                       Map<String, String> damageCauses, Map<String, String> effects,
                       Map<String, String> groups) {
    public static final String KIND = "names";
    public static final int CURRENT_SCHEMA_VERSION = 1;
    public static final NamesDoc DEFAULTS = new NamesDoc(CURRENT_SCHEMA_VERSION, 1L,
        Map.of("jack_o_lantern", "Jack o'Lantern"), Map.of(), Map.of(), Map.of(),
        Map.of("overworld", "Overworld", "the_nether", "The Nether", "the_end", "The End"),
        Map.of(), Map.of(), Map.of());

    public NamesDoc {
        DocumentEnvelope.requireSchemaVersion(KIND, schemaVersion, CURRENT_SCHEMA_VERSION);
        DocumentEnvelope.requireRevision(KIND, revision);
        materials = normalize(NameCategory.MATERIALS, materials);
        entities = normalize(NameCategory.ENTITIES, entities);
        worlds = normalize(NameCategory.WORLDS, worlds);
        gameModes = normalize(NameCategory.GAME_MODES, gameModes);
        dimensions = normalize(NameCategory.DIMENSIONS, dimensions);
        damageCauses = normalize(NameCategory.DAMAGE_CAUSES, damageCauses);
        effects = normalize(NameCategory.EFFECTS, effects);
        groups = normalize(NameCategory.GROUPS, groups);
    }

    public static NamesDoc parse(String fileName, String raw) {
        JsonObject document = JsonParser.parseString(raw).getAsJsonObject();
        for (NameCategory category : NameCategory.values()) {
            JsonElement values = document.get(category.key());
            if (values == null) {
                continue;
            }
            if (!values.isJsonObject()) {
                throw new IllegalArgumentException(fileName + " " + category.key() + " must be an object");
            }
            for (Map.Entry<String, JsonElement> entry : values.getAsJsonObject().entrySet()) {
                if (!entry.getValue().isJsonPrimitive() || !entry.getValue().getAsJsonPrimitive().isString()) {
                    throw new IllegalArgumentException(fileName + " name for " + entry.getKey() + " must be text");
                }
            }
        }
        return DocumentParsers.parseJson(fileName, raw, NamesDoc.class);
    }

    public Map<String, String> values(NameCategory category) {
        return switch (category) {
            case MATERIALS -> materials;
            case ENTITIES -> entities;
            case WORLDS -> worlds;
            case GAME_MODES -> gameModes;
            case DIMENSIONS -> dimensions;
            case DAMAGE_CAUSES -> damageCauses;
            case EFFECTS -> effects;
            case GROUPS -> groups;
        };
    }

    private static Map<String, String> normalize(NameCategory category, Map<String, String> source) {
        if (source == null || source.isEmpty()) {
            return Map.of();
        }
        Map<String, String> result = new HashMap<>(source.size());
        for (Map.Entry<String, String> entry : source.entrySet()) {
            String key = category.normalize(entry.getKey());
            if (key.isEmpty()) {
                throw new IllegalArgumentException("Names keys must not be blank");
            }
            String value = Objects.requireNonNull(entry.getValue(), "name for " + key);
            if (result.putIfAbsent(key, value) != null) {
                throw new IllegalArgumentException("Names key is repeated after normalization: " + key);
            }
        }
        return Map.copyOf(result);
    }
}
