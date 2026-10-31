package art.arcane.gloss.importer;

import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.YamlConfiguration;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

public final class CmiTabListScanner {
    public static final String RELATIVE_PATH = "plugins/CMI/Settings/TabList.yml";
    private static final int TICKS_PER_SECOND = 20;

    public Path file(Path serverDirectory) {
        return serverDirectory.resolve(RELATIVE_PATH);
    }

    Draft read(String source) {
        YamlConfiguration yaml = LegacyYaml.read(source);
        ConfigurationSection root = yaml.getConfigurationSection("TabList");
        if (root == null) {
            return null;
        }
        List<String> warnings = new ArrayList<>();
        boolean header = root.getBoolean("addTabListHeader", true);
        boolean footer = root.getBoolean("addTabListFooter", true);
        double seconds = root.getDouble("Updates.AutoInterval", 60.0D);
        int intervalTicks = seconds > 0.0D ? (int) Math.max(1L, Math.round(seconds * TICKS_PER_SECOND)) : -1;
        ConfigurationSection general = root.getConfigurationSection("GeneralFormat");
        Format generalFormat = new Format(
                header ? frames(general, "Header", warnings) : List.of(""),
                footer ? frames(general, "Footer", warnings) : List.of(""));
        Map<String, Format> groups = new LinkedHashMap<>();
        ConfigurationSection groupFormats = root.getConfigurationSection("GroupFormat");
        if (root.getBoolean("GroupedEnabled", false) && groupFormats != null) {
            List<String> numbers = new ArrayList<>(groupFormats.getKeys(false));
            numbers.sort(Comparator.comparingInt(CmiTabListScanner::order));
            for (String number : numbers) {
                ConfigurationSection group = groupFormats.getConfigurationSection(number);
                if (group == null) {
                    continue;
                }
                boolean groupHeader = header && !root.getBoolean("useGeneralHeader", false)
                        && group.contains("Header");
                boolean groupFooter = footer && !root.getBoolean("useGeneralFooter", false)
                        && group.contains("Footer");
                if (!groupHeader && !groupFooter) {
                    continue;
                }
                groups.put(number, new Format(
                        groupHeader ? frames(group, "Header", warnings) : generalFormat.header(),
                        groupFooter ? frames(group, "Footer", warnings) : generalFormat.footer()));
            }
            if (!groups.isEmpty()) {
                warnings.add("CMI group formats were imported as variants gated on the cmi.tablist.<number> "
                        + "permissions; operators hold every permission, so they see the highest group");
            }
        }
        if (intervalTicks < 0 && (generalFormat.animated() || groups.values().stream().anyMatch(Format::animated))) {
            warnings.add("Updates.AutoInterval is off, so only the first CMI header and footer block was imported");
        }
        warnings.add("CMI PlayerName formats and tab sorting were not imported; the Gloss tablist keeps its own "
                + "list-name rules");
        return new Draft(header || footer, generalFormat, Map.copyOf(groups), intervalTicks,
                List.copyOf(warnings));
    }

    private static List<String> frames(ConfigurationSection section, String key, List<String> warnings) {
        if (section == null || !section.contains(key)) {
            return List.of("");
        }
        List<String> frames = new ArrayList<>();
        ConfigurationSection numbered = section.getConfigurationSection(key);
        if (numbered != null) {
            List<String> keys = new ArrayList<>(numbered.getKeys(false));
            keys.sort(Comparator.comparingInt(CmiTabListScanner::order));
            for (String frame : keys) {
                frames.add(CmiText.translate(String.join("\n", LegacyYaml.strings(numbered, frame)), warnings));
            }
        } else {
            frames.add(CmiText.translate(String.join("\n", LegacyYaml.strings(section, key)), warnings));
        }
        return frames.isEmpty() ? List.of("") : List.copyOf(frames);
    }

    private static int order(String key) {
        try {
            return Integer.parseInt(key.strip());
        } catch (NumberFormatException failure) {
            return Integer.MAX_VALUE;
        }
    }

    record Format(List<String> header, List<String> footer) {
        boolean animated() {
            return header.size() > 1 || footer.size() > 1;
        }
    }

    record Draft(boolean enabled, Format general, Map<String, Format> groups, int intervalTicks,
                 List<String> warnings) {
    }
}
