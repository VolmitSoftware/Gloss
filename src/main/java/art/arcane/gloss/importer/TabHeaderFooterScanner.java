package art.arcane.gloss.importer;

import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.YamlConfiguration;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Reads the {@code header-footer} block of {@code plugins/TAB/config.yml}, global and per group.
 *
 * <p>TAB writes header and footer as lists of lines; Gloss joins them with newlines because its
 * header and footer are single rendered strings. Per-world overrides are not read: Gloss selects a
 * header and footer by condition, and a world condition is a different expression than a group one,
 * so the importer reports them instead of guessing.
 */
public final class TabHeaderFooterScanner {
    public static final String RELATIVE_PATH = "plugins/TAB/config.yml";

    public Path file(Path serverDirectory) {
        return serverDirectory.resolve(RELATIVE_PATH);
    }

    public Draft scan(Path file) throws IOException {
        if (!Files.isRegularFile(file, LinkOption.NOFOLLOW_LINKS)) {
            return null;
        }
        return read(Files.readString(file, StandardCharsets.UTF_8));
    }

    Draft read(String source) {
        YamlConfiguration yaml = LegacyYaml.read(source);
        ConfigurationSection headerFooter = yaml.getConfigurationSection("header-footer");
        if (headerFooter == null) {
            return null;
        }
        List<String> warnings = new ArrayList<>();
        boolean enabled = !headerFooter.contains("enabled") || headerFooter.getBoolean("enabled");
        String header = join(LegacyYaml.strings(headerFooter, "header"));
        String footer = join(LegacyYaml.strings(headerFooter, "footer"));
        Map<String, String[]> groups = new LinkedHashMap<>();
        ConfigurationSection perGroup = headerFooter.getConfigurationSection("per-group");
        if (perGroup != null) {
            for (String group : perGroup.getKeys(false)) {
                ConfigurationSection section = perGroup.getConfigurationSection(group);
                if (section == null) {
                    continue;
                }
                groups.put(group, new String[] {
                        join(LegacyYaml.strings(section, "header")),
                        join(LegacyYaml.strings(section, "footer"))});
            }
        }
        if (headerFooter.getConfigurationSection("per-world") != null) {
            warnings.add("per-world header and footer overrides were not imported; add world "
                    + "conditions to the tablist variants by hand");
        }
        return new Draft(enabled, header, footer, Map.copyOf(groups), List.copyOf(warnings));
    }

    private static String join(List<String> lines) {
        return String.join("\n", lines);
    }

    /** TAB's header and footer, globally and per permission group. */
    public record Draft(boolean enabled, String header, String footer,
                        Map<String, String[]> groups, List<String> warnings) {
    }
}
