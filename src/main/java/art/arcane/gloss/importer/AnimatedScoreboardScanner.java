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
 * Reads {@code plugins/AnimatedScoreboard/scoreboards/*.yml}.
 *
 * <p>AnimatedScoreboard nests frames under {@code frames} with an {@code interval}. Frames and the
 * title interval come across; a per-score interval does not and is reported.
 */
public final class AnimatedScoreboardScanner {
    public static final String RELATIVE_PATH = "plugins/AnimatedScoreboard/scoreboards";
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
        List<String> warnings = new ArrayList<>();
        ConfigurationSection title = yaml.getConfigurationSection("title");
        List<String> titleFrames = title == null
                ? LegacyYaml.strings(yaml, "title")
                : LegacyYaml.strings(title, "frames");
        int titleInterval = title == null
                ? DEFAULT_INTERVAL_TICKS
                : LegacyYaml.integer(title, "interval", DEFAULT_INTERVAL_TICKS);
        List<LegacyBoardDraft.Line> lines = new ArrayList<>();
        List<?> scores = yaml.getList("scores") == null ? yaml.getList("lines") : yaml.getList("scores");
        if (scores != null) {
            for (Object score : scores) {
                lines.add(score(score, warnings, lines.size()));
            }
        }
        if (titleFrames.isEmpty()) {
            warnings.add("scoreboard has no title; the Gloss board keeps an empty title");
        }
        return new LegacyBoardDraft(id, titleFrames, titleInterval, lines, warnings);
    }

    private LegacyBoardDraft.Line score(Object raw, List<String> warnings, int index) {
        if (raw instanceof java.util.Map<?, ?> map) {
            List<String> frames = new ArrayList<>();
            Object values = map.get("frames");
            if (values instanceof List<?> list) {
                list.forEach(value -> frames.add(String.valueOf(value)));
            } else if (values != null) {
                frames.add(String.valueOf(values));
            }
            Object interval = map.get("interval");
            if (interval instanceof Number ticks) {
                warnings.add("score " + (index + 1) + " has its own interval of " + ticks
                        + " ticks; a Gloss board refreshes as one document");
                return new LegacyBoardDraft.Line(frames, ticks.intValue());
            }
            return new LegacyBoardDraft.Line(frames, DEFAULT_INTERVAL_TICKS);
        }
        if (raw instanceof List<?> list) {
            List<String> frames = new ArrayList<>(list.size());
            list.forEach(value -> frames.add(String.valueOf(value)));
            return new LegacyBoardDraft.Line(frames, DEFAULT_INTERVAL_TICKS);
        }
        return new LegacyBoardDraft.Line(List.of(String.valueOf(raw)), DEFAULT_INTERVAL_TICKS);
    }
}
