package art.arcane.gloss.importer;

import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.YamlConfiguration;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.stream.Stream;

/**
 * Reads {@code plugins/FeatherBoard/boards/*.yml}.
 *
 * <p>FeatherBoard authors a title and each line either as one string or as a list of animation
 * frames, optionally with its own update interval. Frames come across; a per-line interval does
 * not, because a Gloss board has one refresh for the whole document, so every line that carries one
 * is reported.
 */
public final class FeatherBoardScanner {
    public static final String RELATIVE_PATH = "plugins/FeatherBoard/boards";
    private static final int DEFAULT_INTERVAL_TICKS = 20;

    public Path root(Path serverDirectory) {
        return serverDirectory.resolve(RELATIVE_PATH);
    }

    public List<LegacyBoardDraft> scan(Path root) throws IOException {
        if (!Files.isDirectory(root, LinkOption.NOFOLLOW_LINKS)) {
            return List.of();
        }
        List<LegacyBoardDraft> drafts = new ArrayList<>();
        try (Stream<Path> files = Files.list(root)) {
            for (Path file : files.sorted(Comparator.comparing(Path::getFileName)).toList()) {
                String name = file.getFileName().toString();
                if (!Files.isRegularFile(file, LinkOption.NOFOLLOW_LINKS)
                        || !(name.endsWith(".yml") || name.endsWith(".yaml"))) {
                    continue;
                }
                drafts.add(read(name.substring(0, name.lastIndexOf('.')),
                        Files.readString(file, StandardCharsets.UTF_8)));
            }
        }
        return List.copyOf(drafts);
    }

    LegacyBoardDraft read(String id, String source) {
        YamlConfiguration yaml = LegacyYaml.read(source);
        ConfigurationSection root = yaml.getConfigurationSection("scoreboard") == null
                ? yaml
                : yaml.getConfigurationSection("scoreboard");
        List<String> warnings = new ArrayList<>();
        List<String> titleFrames = LegacyYaml.strings(root, "title");
        int titleInterval = LegacyYaml.integer(root, "title-interval", DEFAULT_INTERVAL_TICKS);
        List<LegacyBoardDraft.Line> lines = new ArrayList<>();
        List<?> rawLines = root.getList("lines");
        if (rawLines != null) {
            for (Object rawLine : rawLines) {
                lines.add(line(rawLine, warnings, lines.size()));
            }
        }
        if (titleFrames.isEmpty()) {
            warnings.add("board has no title; the Gloss board keeps an empty title");
        }
        return new LegacyBoardDraft(id, titleFrames, titleInterval, lines, warnings);
    }

    private LegacyBoardDraft.Line line(Object rawLine, List<String> warnings, int index) {
        if (rawLine instanceof List<?> frames) {
            List<String> strings = new ArrayList<>(frames.size());
            frames.forEach(frame -> strings.add(String.valueOf(frame)));
            return new LegacyBoardDraft.Line(strings, DEFAULT_INTERVAL_TICKS);
        }
        if (rawLine instanceof java.util.Map<?, ?> map) {
            List<String> frames = new ArrayList<>();
            Object text = map.containsKey("text") ? map.get("text") : map.get("lines");
            if (text instanceof List<?> values) {
                values.forEach(value -> frames.add(String.valueOf(value)));
            } else if (text != null) {
                frames.add(String.valueOf(text));
            }
            Object interval = map.containsKey("interval") ? map.get("interval") : map.get("update");
            if (interval instanceof Number ticks) {
                warnings.add("line " + (index + 1) + " has its own update interval of " + ticks
                        + " ticks; a Gloss board refreshes as one document");
                return new LegacyBoardDraft.Line(frames, ticks.intValue());
            }
            return new LegacyBoardDraft.Line(frames, DEFAULT_INTERVAL_TICKS);
        }
        return new LegacyBoardDraft.Line(List.of(String.valueOf(rawLine)), DEFAULT_INTERVAL_TICKS);
    }
}
