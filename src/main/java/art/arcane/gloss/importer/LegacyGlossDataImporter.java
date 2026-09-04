package art.arcane.gloss.importer;


import art.arcane.gloss.condition.ShowCondition;
import art.arcane.gloss.Gloss;
import art.arcane.gloss.animation.AnimationDoc;
import art.arcane.gloss.bubble.BubbleStyleDoc;
import art.arcane.gloss.config.GlossConfigFile;
import art.arcane.gloss.config.GlossConfigLoader;
import art.arcane.gloss.doc.AtomicFiles;
import art.arcane.gloss.doc.DocumentEnvelope;
import art.arcane.gloss.emoji.EmojiDoc;
import art.arcane.gloss.hologram.HologramDoc;
import art.arcane.gloss.motd.MotdDoc;
import art.arcane.volmlib.util.bukkit.json.BukkitJson;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import org.bukkit.configuration.InvalidConfigurationException;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.util.Vector;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.function.Function;
import java.util.logging.Level;
import java.util.stream.Stream;

/**
 * In-place migration of supported pre-merger Gloss data inside the current data folder to current
 * document envelopes. A file without {@code schemaVersion} is legacy; its original bytes are
 * backed up to {@code import-backups/<timestamp>/<kind>/<file>} before the rewrite, which makes
 * the run idempotent by construction — files that already carry the envelope are skipped.
 *
 * <p>Kinds: {@code holograms/} ({id,world,x,y,z,lines} → anchor envelope, embedded id dropped),
 * {@code emoji/} ({trigger,emoji,enabled} → envelope, the {@code <uses :id:>} sentinel becomes an
 * empty trigger), {@code animations/} ({target-framerate,animation-type,frames} → lowercased
 * mode + frameIntervalMs), and a legacy {@code config.yml} (mechanics overlay onto gloss.toml,
 * then renamed {@code config.yml.imported}).
 */
public final class LegacyGlossDataImporter {
    public static final String BACKUP_DIRECTORY_NAME = "import-backups";
    public static final String LEGACY_CONFIG_FILE_NAME = "config.yml";
    public static final String IMPORTED_CONFIG_FILE_NAME = "config.yml.imported";

    private static final DateTimeFormatter BACKUP_TIMESTAMP = DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss");
    private static final String JSON_EXTENSION = ".json";
    private static final String LEGACY_NO_TRIGGER = "<uses :id:>";

    public enum Status {
        MIGRATED,
        SKIPPED_ENVELOPE,
        SKIPPED_NOTE,
        ABSORBED,
        OVERLAID,
        ERROR
    }

    public record Entry(String kind, String path, Status status, String detail) {
        public Entry {
            kind = Objects.requireNonNull(kind, "kind");
            path = Objects.requireNonNull(path, "path");
            status = Objects.requireNonNull(status, "status");
        }

        public static Entry of(String kind, String path, Status status) {
            return new Entry(kind, path, status, null);
        }
    }

    public record Result(List<Entry> entries, String backupPath) {
        public Result {
            entries = List.copyOf(entries);
        }

        public long count(Status status) {
            return entries.stream().filter(entry -> entry.status() == status).count();
        }
    }

    private final File dataFolder;
    private final GlossConfigLoader configLoader;

    private Path backupRoot;

    public LegacyGlossDataImporter(File dataFolder, GlossConfigLoader configLoader) {
        this.dataFolder = Objects.requireNonNull(dataFolder, "dataFolder").getAbsoluteFile();
        this.configLoader = Objects.requireNonNull(configLoader, "configLoader");
    }

    public Result run(GlossConfigFile config) {
        Objects.requireNonNull(config, "config");
        backupRoot = null;
        List<Entry> entries = new ArrayList<>();
        migrateKind(HologramDoc.KIND, LegacyGlossDataImporter::convertHologram, entries);
        migrateKind(EmojiDoc.KIND, LegacyGlossDataImporter::convertEmoji, entries);
        migrateKind(AnimationDoc.KIND, LegacyGlossDataImporter::convertAnimation, entries);
        overlayLegacyConfig(config, entries);
        logReport(entries);
        return new Result(entries, backupRoot == null ? null : backupRoot.toString());
    }

    private void migrateKind(String kind, Function<JsonObject, Object> converter, List<Entry> entries) {
        File folder = new File(dataFolder, kind);
        File[] files = folder.listFiles(file -> file.isFile()
            && file.getName().toLowerCase(Locale.ROOT).endsWith(JSON_EXTENSION)
            && !file.getName().startsWith("."));
        if (files == null) {
            return;
        }
        Arrays.sort(files, Comparator.comparing(File::getName));
        for (File file : files) {
            String path = kind + "/" + file.getName();
            try {
                String raw = Files.readString(file.toPath(), StandardCharsets.UTF_8);
                JsonElement parsed = JsonParser.parseString(raw);
                if (!parsed.isJsonObject()) {
                    throw new IllegalArgumentException("document is not a JSON object");
                }
                JsonObject legacy = parsed.getAsJsonObject();
                if (legacy.has("schemaVersion")) {
                    entries.add(Entry.of(kind, path, Status.SKIPPED_ENVELOPE));
                    continue;
                }
                legacy.addProperty("_fileName", baseName(file));
                Object converted = converter.apply(legacy);
                backup(kind, file);
                writeDocument(file.toPath(), converted);
                entries.add(Entry.of(kind, path, Status.MIGRATED));
            } catch (IOException | RuntimeException failure) {
                entries.add(new Entry(kind, path, Status.ERROR, detail(failure)));
            }
        }
    }

    private static HologramDoc convertHologram(JsonObject legacy) {
        Vector position = new Vector(
            legacy.get("x").getAsDouble(),
            legacy.get("y").getAsDouble(),
            legacy.get("z").getAsDouble());
        HologramDoc.Anchor anchor = new HologramDoc.Anchor(legacy.get("world").getAsString(), position);
        return new HologramDoc(HologramDoc.CURRENT_SCHEMA_VERSION, DocumentEnvelope.INITIAL_REVISION,
            anchor, stringList(legacy.getAsJsonArray("lines")), true,
            HologramDoc.DEFAULT_SCALE, HologramDoc.DEFAULT_BILLBOARD, 0.0D, 0.0D, List.of(), ShowCondition.ALWAYS);
    }

    private static EmojiDoc convertEmoji(JsonObject legacy) {
        String trigger = optString(legacy, "trigger", "");
        if (LEGACY_NO_TRIGGER.equals(trigger)) {
            trigger = "";
        }
        boolean enabled = !legacy.has("enabled") || legacy.get("enabled").getAsBoolean();
        return new EmojiDoc(EmojiDoc.CURRENT_SCHEMA_VERSION, DocumentEnvelope.INITIAL_REVISION,
            trigger, legacy.get("emoji").getAsString(), enabled, ShowCondition.ALWAYS);
    }

    private static AnimationDoc convertAnimation(JsonObject legacy) {
        double framerate = legacy.get("target-framerate").getAsDouble();
        String mode = legacy.get("animation-type").getAsString().toLowerCase(Locale.ROOT);
        long frameIntervalMs = Math.round(1000.0D / framerate);
        return new AnimationDoc(AnimationDoc.CURRENT_SCHEMA_VERSION, DocumentEnvelope.INITIAL_REVISION,
            mode, frameIntervalMs, stringList(legacy.getAsJsonArray("frames")), ShowCondition.ALWAYS);
    }

    private void overlayLegacyConfig(GlossConfigFile config, List<Entry> entries) {
        File legacyConfig = new File(dataFolder, LEGACY_CONFIG_FILE_NAME);
        if (!legacyConfig.isFile()) {
            return;
        }
        YamlConfiguration yaml = new YamlConfiguration();
        try {
            yaml.load(legacyConfig);
        } catch (IOException | InvalidConfigurationException | RuntimeException failure) {
            entries.add(new Entry("config", LEGACY_CONFIG_FILE_NAME, Status.ERROR, detail(failure)));
            return;
        }
        overlayMechanics(yaml, config, entries);
        overlayBubbleContent(yaml, entries);
        overlayMotdContent(yaml, entries);
        try {
            configLoader.save(config);
        } catch (IOException failure) {
            entries.add(new Entry("config", GlossConfigLoader.FILE_NAME, Status.ERROR, detail(failure)));
        }
        try {
            Files.move(legacyConfig.toPath(), new File(dataFolder, IMPORTED_CONFIG_FILE_NAME).toPath(),
                StandardCopyOption.REPLACE_EXISTING);
            entries.add(new Entry("config", LEGACY_CONFIG_FILE_NAME, Status.OVERLAID,
                "renamed to " + IMPORTED_CONFIG_FILE_NAME));
        } catch (IOException failure) {
            entries.add(new Entry("config", LEGACY_CONFIG_FILE_NAME, Status.ERROR, detail(failure)));
        }
    }

    private void overlayMechanics(YamlConfiguration yaml, GlossConfigFile config, List<Entry> entries) {
        overlay(yaml, "splash-screen", entries, () -> config.splashScreen = yaml.getBoolean("splash-screen"));
        overlay(yaml, "metrics", entries, () -> config.metrics = yaml.getBoolean("metrics"));
        overlay(yaml, "features.holograms", entries, () -> config.features.holograms = yaml.getBoolean("features.holograms"));
        overlay(yaml, "features.boards", entries, () -> config.features.boards = yaml.getBoolean("features.boards"));
        overlay(yaml, "features.tablist", entries, () -> config.features.tablist = yaml.getBoolean("features.tablist"));
        overlay(yaml, "features.emoji", entries, () -> config.features.emoji = yaml.getBoolean("features.emoji"));
        overlay(yaml, "features.animations", entries, () -> config.features.animations = yaml.getBoolean("features.animations"));
        overlay(yaml, "features.chat-bubbles", entries, () -> config.features.chatBubbles = yaml.getBoolean("features.chat-bubbles"));
        overlay(yaml, "features.damage-indicators", entries, () -> config.features.damageIndicators = yaml.getBoolean("features.damage-indicators"));
        overlay(yaml, "features.drops", entries, () -> config.features.drops = yaml.getBoolean("features.drops"));
        overlay(yaml, "motd.enabled", entries, () -> config.features.motd = yaml.getBoolean("motd.enabled"));
        overlay(yaml, "hotload.watch-interval-ticks", entries, () -> config.hotload.watchIntervalTicks = yaml.getInt("hotload.watch-interval-ticks"));
        overlay(yaml, "holograms.stack-distance", entries, () -> config.holograms.stackDistance = yaml.getDouble("holograms.stack-distance"));
        overlay(yaml, "holograms.update-interval-ticks", entries, () -> config.holograms.updateIntervalTicks = yaml.getInt("holograms.update-interval-ticks"));
        overlay(yaml, "holograms.view-range", entries, () -> config.holograms.viewRange = yaml.getDouble("holograms.view-range"));
        overlay(yaml, "holograms.per-viewer-placeholders", entries, () -> config.holograms.perViewerPlaceholders = yaml.getBoolean("holograms.per-viewer-placeholders"));
        overlay(yaml, "holograms.temporary-update-interval-ticks", entries, () -> config.holograms.temporaryUpdateIntervalTicks = yaml.getInt("holograms.temporary-update-interval-ticks"));
        overlay(yaml, "boards.update-interval-ticks", entries, () -> config.boards.updateIntervalTicks = yaml.getInt("boards.update-interval-ticks"));
        overlay(yaml, "tablist.update-interval-ticks", entries, () -> config.tablist.updateIntervalTicks = yaml.getInt("tablist.update-interval-ticks"));
        overlay(yaml, "groups.use-vault", entries, () -> config.groups.useVault = yaml.getBoolean("groups.use-vault"));
        overlay(yaml, "emoji.emoji-specific-permissions", entries, () -> config.emoji.emojiSpecificPermissions = yaml.getBoolean("emoji.emoji-specific-permissions"));
        overlay(yaml, "emoji.tab-complete", entries, () -> config.emoji.tabComplete = yaml.getBoolean("emoji.tab-complete"));
        overlay(yaml, "text.placeholders", entries, () -> config.text.placeholders = yaml.getBoolean("text.placeholders"));
        overlay(yaml, "text.functions", entries, () -> config.text.functions = yaml.getBoolean("text.functions"));
        overlay(yaml, "chat.color", entries, () -> config.chat.color = yaml.getBoolean("chat.color"));
        overlay(yaml, "chat-bubbles.blacklist-worlds", entries, () -> config.chatBubbles.blacklistWorlds = new ArrayList<>(yaml.getStringList("chat-bubbles.blacklist-worlds")));
        overlay(yaml, "drops.name-format", entries, () -> config.drops.nameFormat = yaml.getString("drops.name-format"));
        overlay(yaml, "commands.sounds", entries, () -> config.commands.sounds = yaml.getBoolean("commands.sounds"));
    }

    private void overlay(YamlConfiguration yaml, String key, List<Entry> entries, Runnable apply) {
        if (!yaml.contains(key)) {
            return;
        }
        apply.run();
        entries.add(Entry.of("config", LEGACY_CONFIG_FILE_NAME + ":" + key, Status.OVERLAID));
    }

    private void overlayBubbleContent(YamlConfiguration yaml, List<Entry> entries) {
        if (!yaml.contains("chat-bubbles")) {
            return;
        }
        boolean present = yaml.contains("chat-bubbles.message.prefix") || yaml.contains("chat-bubbles.message.offset")
            || yaml.contains("chat-bubbles.word-wrap-break-chars") || yaml.contains("chat-bubbles.max-time-alive")
            || yaml.contains("chat-bubbles.line-stagger-ticks") || yaml.contains("chat-bubbles.fly-away")
            || yaml.contains("chat-bubbles.follow-players") || yaml.contains("chat-bubbles.hide-own-messages");
        if (!present) {
            return;
        }
        File styleFile = new File(new File(dataFolder, BubbleStyleDoc.KIND), "default" + JSON_EXTENSION);
        try {
            byte[] shipped = readResource("/defaults/" + BubbleStyleDoc.KIND + "/default" + JSON_EXTENSION);
            if (styleFile.isFile() && !Arrays.equals(shipped, Files.readAllBytes(styleFile.toPath()))) {
                entries.add(new Entry("config", "bubbles/default" + JSON_EXTENSION, Status.SKIPPED_NOTE,
                    "bubbles/default.json was already customized; config.yml bubble content not applied"));
                return;
            }
            BubbleStyleDoc base = BubbleStyleDoc.parse("default.json", new String(shipped, StandardCharsets.UTF_8));
            Vector offset = new Vector(
                yaml.getDouble("chat-bubbles.message.offset.x", base.offset().getX()),
                yaml.getDouble("chat-bubbles.message.offset.y", base.offset().getY()),
                yaml.getDouble("chat-bubbles.message.offset.z", base.offset().getZ()));
            BubbleStyleDoc.Motion motion = yaml.getBoolean("chat-bubbles.fly-away", true)
                ? base.motion()
                : new BubbleStyleDoc.Motion(new BubbleStyleDoc.Axis("0", "0", "0"),
                    new BubbleStyleDoc.Axis("1", "1", "1"), new BubbleStyleDoc.Axis("0", "0", "0"), "1");
            BubbleStyleDoc updated = new BubbleStyleDoc(base.schemaVersion(), base.revision() + 1L,
                yaml.getString("chat-bubbles.message.prefix", base.prefix()),
                offset,
                yaml.getInt("chat-bubbles.word-wrap-break-chars", base.wordWrapChars()),
                yaml.getLong("chat-bubbles.max-time-alive", base.maxAliveMs()),
                yaml.getBoolean("chat-bubbles.follow-players", base.followPlayer()),
                yaml.getBoolean("chat-bubbles.hide-own-messages", base.hideOwn()),
                motion,
                base.shimmer(),
                base.select(),
                base.particleLayers(), base.show());
            writeDocument(styleFile.toPath(), updated);
            entries.add(Entry.of("config", LEGACY_CONFIG_FILE_NAME + ":chat-bubbles", Status.OVERLAID));
        } catch (IOException | RuntimeException failure) {
            entries.add(new Entry("config", "bubbles/default" + JSON_EXTENSION, Status.ERROR, detail(failure)));
        }
    }

    private void overlayMotdContent(YamlConfiguration yaml, List<Entry> entries) {
        List<String> texts = yaml.getStringList("motd.texts");
        if (texts.isEmpty()) {
            return;
        }
        File motdFile = new File(dataFolder, MotdDoc.KIND + JSON_EXTENSION);
        try {
            byte[] shipped = readResource("/defaults/" + MotdDoc.KIND + "/" + MotdDoc.KIND + JSON_EXTENSION);
            if (motdFile.isFile() && !Arrays.equals(shipped, Files.readAllBytes(motdFile.toPath()))) {
                entries.add(new Entry("config", MotdDoc.KIND + JSON_EXTENSION, Status.SKIPPED_NOTE,
                    "motd.json was already customized; config.yml motd texts not applied"));
                return;
            }
            List<MotdDoc.MotdEntry> motdEntries = new ArrayList<>(texts.size());
            for (String text : texts) {
                if (text == null || text.isBlank()) {
                    continue;
                }
                List<String> lines = new ArrayList<>(Arrays.asList(text.split("\n", -1)));
                if (lines.size() > MotdDoc.MAX_LINES_PER_ENTRY) {
                    lines = lines.subList(0, MotdDoc.MAX_LINES_PER_ENTRY);
                }
                motdEntries.add(new MotdDoc.MotdEntry(lines));
            }
            if (motdEntries.isEmpty()) {
                return;
            }
            MotdDoc updated = new MotdDoc(MotdDoc.CURRENT_SCHEMA_VERSION, DocumentEnvelope.INITIAL_REVISION, ShowCondition.ALWAYS,
                motdEntries);
            writeDocument(motdFile.toPath(), updated);
            entries.add(Entry.of("config", LEGACY_CONFIG_FILE_NAME + ":motd.texts", Status.OVERLAID));
        } catch (IOException | RuntimeException failure) {
            entries.add(new Entry("config", MotdDoc.KIND + JSON_EXTENSION, Status.ERROR, detail(failure)));
        }
    }

    private void backup(String kind, File file) throws IOException {
        Path target = backupDirectory().resolve(kind).resolve(file.getName());
        Files.createDirectories(target.getParent());
        try {
            Files.copy(file.toPath(), target, StandardCopyOption.REPLACE_EXISTING);
        } catch (IOException failure) {
            discardEmptyDirectory(target.getParent());
            discardEmptyBackupDirectory();
            throw failure;
        }
    }

    private Path backupDirectory() throws IOException {
        if (backupRoot == null) {
            Path root = dataFolder.toPath().resolve(BACKUP_DIRECTORY_NAME)
                .resolve(LocalDateTime.now().format(BACKUP_TIMESTAMP));
            Files.createDirectories(root);
            backupRoot = root;
        }
        return backupRoot;
    }

    /**
     * Removes the timestamp folder — and {@code import-backups/} above it — when the write that
     * created it failed and left nothing inside. A failed backup must not be reported as one either,
     * so the memoized root is dropped along with the directory.
     */
    private void discardEmptyBackupDirectory() {
        Path root = backupRoot;
        if (root == null) {
            return;
        }
        try {
            if (!isEmptyDirectory(root)) {
                return;
            }
            Files.delete(root);
            backupRoot = null;
            Path parent = root.getParent();
            if (parent != null && isEmptyDirectory(parent)) {
                Files.delete(parent);
            }
        } catch (IOException failure) {
            Gloss.logExceptionStack(false, failure,
                "Unable to remove the empty import backup directory %s.", root);
        }
    }

    private static void discardEmptyDirectory(Path directory) {
        try {
            if (directory != null && isEmptyDirectory(directory)) {
                Files.delete(directory);
            }
        } catch (IOException failure) {
            Gloss.logExceptionStack(false, failure,
                "Unable to remove the empty import backup directory %s.", directory);
        }
    }

    private static boolean isEmptyDirectory(Path directory) throws IOException {
        if (!Files.isDirectory(directory)) {
            return false;
        }
        try (Stream<Path> children = Files.list(directory)) {
            return children.findAny().isEmpty();
        }
    }

    private static void writeDocument(Path file, Object document) throws IOException {
        AtomicFiles.replace(file,
            (BukkitJson.GSON.toJson(document) + System.lineSeparator()).getBytes(StandardCharsets.UTF_8));
    }

    private static byte[] readResource(String path) throws IOException {
        try (InputStream stream = LegacyGlossDataImporter.class.getResourceAsStream(path)) {
            if (stream == null) {
                throw new IOException("missing shipped resource " + path);
            }
            return stream.readAllBytes();
        }
    }

    private static List<String> stringList(JsonArray array) {
        if (array == null) {
            return List.of();
        }
        List<String> values = new ArrayList<>(array.size());
        for (JsonElement element : array) {
            values.add(element == null || element.isJsonNull() ? "" : element.getAsString());
        }
        return values;
    }

    private static String optString(JsonObject json, String key, String fallback) {
        JsonElement element = json.get(key);
        return element == null || element.isJsonNull() ? fallback : element.getAsString();
    }

    private static String baseName(File file) {
        String name = file.getName();
        int dot = name.lastIndexOf('.');
        return dot < 0 ? name : name.substring(0, dot);
    }

    private void logReport(List<Entry> entries) {
        Map<String, int[]> byKind = new LinkedHashMap<>();
        for (Entry entry : entries) {
            int[] counts = byKind.computeIfAbsent(entry.kind(), key -> new int[Status.values().length]);
            counts[entry.status().ordinal()]++;
        }
        for (Map.Entry<String, int[]> kind : byKind.entrySet()) {
            int[] counts = kind.getValue();
            int active = counts[Status.MIGRATED.ordinal()] + counts[Status.ABSORBED.ordinal()]
                + counts[Status.OVERLAID.ordinal()] + counts[Status.ERROR.ordinal()]
                + counts[Status.SKIPPED_NOTE.ordinal()];
            if (active == 0) {
                continue;
            }
            StringBuilder line = new StringBuilder("Legacy import: ").append(kind.getKey());
            for (Status status : Status.values()) {
                if (counts[status.ordinal()] > 0) {
                    line.append(' ').append(counts[status.ordinal()]).append(' ')
                        .append(status.name().toLowerCase(Locale.ROOT).replace('_', '-'));
                }
            }
            Gloss.log(Level.INFO, "%s", line);
        }
    }

    private static String detail(Throwable failure) {
        String message = failure.getMessage();
        return message == null || message.isBlank() ? failure.getClass().getSimpleName() : message;
    }
}
