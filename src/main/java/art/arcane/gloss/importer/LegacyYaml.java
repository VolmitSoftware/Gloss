package art.arcane.gloss.importer;

import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.YamlConfiguration;

import java.util.ArrayList;
import java.util.List;

/** The few reads every legacy YAML scanner needs, tolerant of scalar-or-list authoring. */
final class LegacyYaml {
    private LegacyYaml() {
    }

    static YamlConfiguration read(String source) {
        YamlConfiguration configuration = new YamlConfiguration();
        try {
            configuration.loadFromString(source);
        } catch (Exception malformed) {
            throw new IllegalArgumentException("file is not valid YAML: " + malformed.getMessage(),
                    malformed);
        }
        return configuration;
    }

    /** A value authored either as one string or as a list of strings, always read as a list. */
    static List<String> strings(ConfigurationSection section, String path) {
        if (section == null || !section.contains(path)) {
            return List.of();
        }
        Object value = section.get(path);
        if (value instanceof List<?> values) {
            List<String> strings = new ArrayList<>(values.size());
            for (Object item : values) {
                strings.add(String.valueOf(item));
            }
            return List.copyOf(strings);
        }
        return List.of(String.valueOf(value));
    }

    static String string(ConfigurationSection section, String path, String fallback) {
        if (section == null || !section.contains(path)) {
            return fallback;
        }
        Object value = section.get(path);
        return value instanceof List<?> values && !values.isEmpty()
                ? String.valueOf(values.getFirst())
                : String.valueOf(value);
    }

    static int integer(ConfigurationSection section, String path, int fallback) {
        if (section == null || !section.contains(path)) {
            return fallback;
        }
        Object value = section.get(path);
        return value instanceof Number number ? number.intValue() : fallback;
    }
}
