package art.arcane.gloss.importer;

import art.arcane.gloss.Gloss;
import art.arcane.gloss.config.GlossConfigFile;
import art.arcane.gloss.config.GlossConfigLoader;
import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.EnumMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.function.Consumer;
import java.util.stream.Stream;

/**
 * One-shot COPY of a retired {@code plugins/holoui} data folder into the Gloss data folder. The
 * source directory is never modified; every touched path lands in the {@code holoui-import.json}
 * receipt, whose presence makes the boot-time run a no-op. {@code /gloss import holoui} re-runs
 * with force semantics (overwrites previously imported files, still never touches the source).
 *
 * <p>Mapping: {@code menus/**} and {@code images/**} copy verbatim, {@code boards/**} copies to
 * {@code panels/**} (byte-compatible contract), {@code previews/*.json} copy with their
 * {@code lang()} keys rewritten from {@code holoui.preview.*} to {@code gloss.preview.*} unless
 * the rewritten bytes are identical to a shipped default (those re-extract anyway; the receipt
 * disposition detail notes the rewrite), {@code preview-scales.json} and
 * {@code language.yml} copy verbatim, and {@code settings.json} overlays its flat keys onto the
 * just-loaded {@link GlossConfigFile} before re-serializing {@code gloss.toml} through the loader
 * so comments regenerate. Editor sync sessions, transactions, and backups are secrets and never
 * copy; {@code custom-items.json} is regenerable and never copies either.
 */
public final class HoloUiDataImporter {
    public static final String RECEIPT_FILE_NAME = "holoui-import.json";
    public static final int RECEIPT_SCHEMA_VERSION = 1;

    static final String CATEGORY_MENUS = "menus";
    static final String CATEGORY_IMAGES = "images";
    static final String CATEGORY_PANELS = "panels";
    static final String CATEGORY_PREVIEWS = "previews";
    static final String CATEGORY_FILES = "files";
    static final String CATEGORY_CONFIG = "config";
    static final String CATEGORY_SECRETS = "secrets";

    private static final String[] SOURCE_DIRECTORY_NAMES = {"holoui", "HoloUi"};
    private static final String JSON_EXTENSION = ".json";
    private static final String SHIPPED_PREVIEW_RESOURCE_ROOT = "/previews/";
    private static final String LEGACY_PREVIEW_LANG_PREFIX = "holoui.preview.";
    private static final String PREVIEW_LANG_PREFIX = "gloss.preview.";
    private static final String PREVIEW_LANG_REWRITE_DETAIL =
        "lang keys rewritten " + LEGACY_PREVIEW_LANG_PREFIX + "* -> " + PREVIEW_LANG_PREFIX + "*";
    private static final String RETIRED_ENDPOINT_SUFFIX = "/v1";
    private static final Gson RECEIPT_GSON = new GsonBuilder().setPrettyPrinting().disableHtmlEscaping().create();

    private final File dataFolder;
    private final GlossConfigLoader configLoader;

    public record Result(boolean sourcePresent, String sourcePath, List<HoloUiImportEntry> entries) {
        public Result {
            entries = List.copyOf(entries);
        }

        public long count(HoloUiImportDisposition disposition) {
            return entries.stream().filter(entry -> entry.disposition() == disposition).count();
        }
    }

    public HoloUiDataImporter(File dataFolder, GlossConfigLoader configLoader) {
        this.dataFolder = Objects.requireNonNull(dataFolder, "dataFolder").getAbsoluteFile();
        this.configLoader = Objects.requireNonNull(configLoader, "configLoader");
    }

    public File receiptFile() {
        return new File(dataFolder, RECEIPT_FILE_NAME);
    }

    /** The {@code holoui} (or {@code HoloUi}) directory beside the Gloss data folder, or null. */
    public File sourceDirectory() {
        File parent = dataFolder.getParentFile();
        if (parent == null) {
            return null;
        }
        for (String name : SOURCE_DIRECTORY_NAMES) {
            File candidate = new File(parent, name);
            if (candidate.isDirectory()) {
                return candidate;
            }
        }
        return null;
    }

    /** Boot guard: run once, only while a source folder exists and no receipt has been written. */
    public boolean shouldRun() {
        return !receiptFile().exists() && sourceDirectory() != null;
    }

    public Result run(GlossConfigFile config, boolean force) {
        Objects.requireNonNull(config, "config");
        File source = sourceDirectory();
        if (source == null) {
            return new Result(false, null, List.of());
        }
        List<HoloUiImportEntry> entries = new ArrayList<>();
        copyTree(new File(source, "menus"), new File(dataFolder, "menus"), CATEGORY_MENUS, "menus/", true, force, entries);
        copyTree(new File(source, "images"), new File(dataFolder, "images"), CATEGORY_IMAGES, "images/", false, force, entries);
        copyTree(new File(source, "boards"), new File(dataFolder, "panels"), CATEGORY_PANELS, "boards/", true, force, entries);
        copyPreviews(source, force, entries);
        copyRootFile(source, "preview-scales.json", force, entries);
        copyRootFile(source, "language.yml", force, entries);
        recordSecrets(source, entries);
        overlaySettings(source, config, entries);
        writeReceipt(source, force, entries);
        logSummary(source, entries);
        return new Result(true, source.getAbsolutePath(), entries);
    }

    private void copyTree(File sourceRoot, File targetRoot, String category, String reportPrefix,
                          boolean jsonOnly, boolean force, List<HoloUiImportEntry> entries) {
        for (Path sourceFile : importableFiles(sourceRoot, jsonOnly)) {
            Path relative = sourceRoot.toPath().toAbsolutePath().normalize().relativize(sourceFile);
            String reportPath = reportPrefix + relative.toString().replace(File.separatorChar, '/');
            copyOne(sourceFile, targetRoot.toPath().resolve(relative), category, reportPath, force, entries);
        }
    }

    private void copyPreviews(File source, boolean force, List<HoloUiImportEntry> entries) {
        File previewDir = new File(source, "previews");
        File[] files = previewDir.listFiles(file -> file.isFile()
            && file.getName().toLowerCase(Locale.ROOT).endsWith(JSON_EXTENSION)
            && !file.getName().startsWith("."));
        if (files == null) {
            return;
        }
        Arrays.sort(files, Comparator.comparing(File::getName));
        for (File file : files) {
            String reportPath = "previews/" + file.getName();
            byte[] rewritten;
            boolean langKeysRewritten;
            try {
                byte[] sourceBytes = Files.readAllBytes(file.toPath());
                String content = new String(sourceBytes, StandardCharsets.UTF_8);
                String updated = content.replace(LEGACY_PREVIEW_LANG_PREFIX, PREVIEW_LANG_PREFIX);
                langKeysRewritten = !updated.equals(content);
                rewritten = langKeysRewritten ? updated.getBytes(StandardCharsets.UTF_8) : sourceBytes;
                if (matchesShippedPreview(file.getName(), rewritten)) {
                    entries.add(HoloUiImportEntry.of(CATEGORY_PREVIEWS, reportPath,
                        HoloUiImportDisposition.SKIPPED_SHIPPED_IDENTICAL));
                    continue;
                }
            } catch (IOException failure) {
                entries.add(new HoloUiImportEntry(CATEGORY_PREVIEWS, reportPath,
                    HoloUiImportDisposition.ERROR, detail(failure)));
                continue;
            }
            writePreview(rewritten, new File(dataFolder, "previews").toPath().resolve(file.getName()),
                reportPath, force, langKeysRewritten, entries);
        }
    }

    private void writePreview(byte[] content, Path targetFile, String reportPath, boolean force,
                              boolean langKeysRewritten, List<HoloUiImportEntry> entries) {
        try {
            if (!force && Files.exists(targetFile)) {
                entries.add(new HoloUiImportEntry(CATEGORY_PREVIEWS, reportPath,
                    HoloUiImportDisposition.SKIPPED_EXISTING, "destination already exists"));
                return;
            }
            Path parent = targetFile.getParent();
            if (parent != null) {
                Files.createDirectories(parent);
            }
            Files.write(targetFile, content);
            entries.add(new HoloUiImportEntry(CATEGORY_PREVIEWS, reportPath,
                HoloUiImportDisposition.COPIED, langKeysRewritten ? PREVIEW_LANG_REWRITE_DETAIL : null));
        } catch (IOException failure) {
            entries.add(new HoloUiImportEntry(CATEGORY_PREVIEWS, reportPath,
                HoloUiImportDisposition.ERROR, detail(failure)));
        }
    }

    private void copyRootFile(File source, String name, boolean force, List<HoloUiImportEntry> entries) {
        File sourceFile = new File(source, name);
        if (!sourceFile.isFile()) {
            return;
        }
        copyOne(sourceFile.toPath(), new File(dataFolder, name).toPath(), CATEGORY_FILES, name, force, entries);
    }

    private void copyOne(Path sourceFile, Path targetFile, String category, String reportPath,
                         boolean force, List<HoloUiImportEntry> entries) {
        try {
            if (!force && Files.exists(targetFile)) {
                entries.add(new HoloUiImportEntry(category, reportPath,
                    HoloUiImportDisposition.SKIPPED_EXISTING, "destination already exists"));
                return;
            }
            Path parent = targetFile.getParent();
            if (parent != null) {
                Files.createDirectories(parent);
            }
            Files.copy(sourceFile, targetFile, StandardCopyOption.REPLACE_EXISTING);
            entries.add(HoloUiImportEntry.of(category, reportPath, HoloUiImportDisposition.COPIED));
        } catch (IOException failure) {
            entries.add(new HoloUiImportEntry(category, reportPath, HoloUiImportDisposition.ERROR, detail(failure)));
        }
    }

    private void recordSecrets(File source, List<HoloUiImportEntry> entries) {
        recordSecret(source, "editor-sync-sessions.json", "session secrets are never imported", entries);
        recordSecret(source, "editor-sync-transactions", "editor sync state is never imported", entries);
        recordSecret(source, "editor-sync-backups", "editor sync state is never imported", entries);
        recordSecret(source, "custom-items.json", "regenerable via /gloss item export", entries);
    }

    private void recordSecret(File source, String name, String reason, List<HoloUiImportEntry> entries) {
        if (new File(source, name).exists()) {
            entries.add(new HoloUiImportEntry(CATEGORY_SECRETS, name, HoloUiImportDisposition.SKIPPED_SECRET, reason));
        }
    }

    private void overlaySettings(File source, GlossConfigFile config, List<HoloUiImportEntry> entries) {
        File settingsFile = new File(source, "settings.json");
        if (!settingsFile.isFile()) {
            return;
        }
        JsonObject settings;
        try {
            JsonElement parsed = JsonParser.parseString(Files.readString(settingsFile.toPath(), StandardCharsets.UTF_8));
            if (!parsed.isJsonObject()) {
                throw new IllegalArgumentException("settings.json is not a JSON object");
            }
            settings = parsed.getAsJsonObject();
        } catch (IOException | RuntimeException failure) {
            entries.add(new HoloUiImportEntry(CATEGORY_CONFIG, "settings.json",
                HoloUiImportDisposition.ERROR, detail(failure)));
            return;
        }
        overlayBoolean(settings, "debugHitbox", value -> config.debug.hitbox = value, entries);
        overlayBoolean(settings, "debugPosition", value -> config.debug.position = value, entries);
        overlayString(settings, "builderUrl", value -> config.editor.builderUrl = value, entries);
        overlayBoolean(settings, "editorSyncEnabled", value -> config.editor.sync.enabled = value, entries);
        overlaySyncEndpoint(settings, config, entries);
        overlayString(settings, "editorSyncCreateToken", value -> config.editor.sync.createToken = value, entries);
        overlayInt(settings, "editorSyncSessionMinutes", value -> config.editor.sync.sessionMinutes = value, entries);
        overlayInt(settings, "editorSyncPollSeconds", value -> config.editor.sync.pollSeconds = value, entries);
        overlayInt(settings, "editorSyncMaxProjectMiB", value -> config.editor.sync.maxProjectMiB = value, entries);
        overlayBoolean(settings, "previewEnabled", value -> config.features.previews = value, entries);
        overlayDouble(settings, "previewLookDistance", value -> config.preview.lookDistance = value, entries);
        overlayDouble(settings, "previewScale", value -> config.preview.scale = value, entries);
        overlayDouble(settings, "uiScale", value -> config.menus.uiScale = value, entries);
        overlayBoolean(settings, "customItems", value -> config.items.customItems = value, entries);
        overlayString(settings, "customItemProviders",
            value -> config.items.customItemProviders = splitCsv(value), entries);
        try {
            configLoader.save(config);
        } catch (IOException failure) {
            entries.add(new HoloUiImportEntry(CATEGORY_CONFIG, GlossConfigLoader.FILE_NAME,
                HoloUiImportDisposition.ERROR, detail(failure)));
        }
    }

    private void overlaySyncEndpoint(JsonObject settings, GlossConfigFile config, List<HoloUiImportEntry> entries) {
        String key = "editorSyncEndpoint";
        if (!settings.has(key)) {
            return;
        }
        String value;
        try {
            value = settings.get(key).getAsString().trim();
        } catch (RuntimeException failure) {
            entries.add(new HoloUiImportEntry(CATEGORY_CONFIG, settingsPath(key),
                HoloUiImportDisposition.ERROR, detail(failure)));
            return;
        }
        if (isRetiredEndpoint(value)) {
            entries.add(new HoloUiImportEntry(CATEGORY_CONFIG, settingsPath(key),
                HoloUiImportDisposition.SKIPPED_RETIRED_ENDPOINT,
                "retired /v1 endpoint cannot speak sync v2; kept " + GlossConfigFile.EDITOR_SYNC_ENDPOINT_DEFAULT));
            return;
        }
        config.editor.sync.endpoint = value;
        entries.add(HoloUiImportEntry.of(CATEGORY_CONFIG, settingsPath(key),
            HoloUiImportDisposition.OVERLAID_CONFIG_KEY));
    }

    /** A {@code /v1} relay endpoint predates the Gloss sync protocol and never imports. */
    static boolean isRetiredEndpoint(String endpoint) {
        if (endpoint == null) {
            return false;
        }
        String normalized = endpoint.trim().toLowerCase(Locale.ROOT);
        while (normalized.endsWith("/")) {
            normalized = normalized.substring(0, normalized.length() - 1);
        }
        return normalized.endsWith(RETIRED_ENDPOINT_SUFFIX);
    }

    static List<String> splitCsv(String csv) {
        if (csv == null || csv.isBlank()) {
            return new ArrayList<>();
        }
        List<String> values = new ArrayList<>();
        for (String token : csv.split(",")) {
            String cleaned = token.trim();
            if (!cleaned.isEmpty()) {
                values.add(cleaned);
            }
        }
        return values;
    }

    private void overlayBoolean(JsonObject settings, String key, Consumer<Boolean> apply,
                                List<HoloUiImportEntry> entries) {
        overlayValue(settings, key, element -> apply.accept(element.getAsBoolean()), entries);
    }

    private void overlayInt(JsonObject settings, String key, Consumer<Integer> apply,
                            List<HoloUiImportEntry> entries) {
        overlayValue(settings, key, element -> apply.accept(element.getAsInt()), entries);
    }

    private void overlayDouble(JsonObject settings, String key, Consumer<Double> apply,
                               List<HoloUiImportEntry> entries) {
        overlayValue(settings, key, element -> apply.accept(element.getAsDouble()), entries);
    }

    private void overlayString(JsonObject settings, String key, Consumer<String> apply,
                               List<HoloUiImportEntry> entries) {
        overlayValue(settings, key, element -> apply.accept(element.getAsString()), entries);
    }

    private void overlayValue(JsonObject settings, String key, Consumer<JsonElement> apply,
                              List<HoloUiImportEntry> entries) {
        if (!settings.has(key)) {
            return;
        }
        try {
            apply.accept(settings.get(key));
            entries.add(HoloUiImportEntry.of(CATEGORY_CONFIG, settingsPath(key),
                HoloUiImportDisposition.OVERLAID_CONFIG_KEY));
        } catch (RuntimeException failure) {
            entries.add(new HoloUiImportEntry(CATEGORY_CONFIG, settingsPath(key),
                HoloUiImportDisposition.ERROR, detail(failure)));
        }
    }

    private static String settingsPath(String key) {
        return "settings.json:" + key;
    }

    private boolean matchesShippedPreview(String name, byte[] candidate) throws IOException {
        try (InputStream shipped = HoloUiDataImporter.class.getResourceAsStream(
            SHIPPED_PREVIEW_RESOURCE_ROOT + name)) {
            if (shipped == null) {
                return false;
            }
            return Arrays.equals(shipped.readAllBytes(), candidate);
        }
    }

    /** Regular files below the root, excluding dot-segments and symlinks per menu discovery rules. */
    private static List<Path> importableFiles(File root, boolean jsonOnly) {
        if (!root.isDirectory()) {
            return List.of();
        }
        Path rootPath = root.toPath().toAbsolutePath().normalize();
        List<Path> files = new ArrayList<>();
        try (Stream<Path> stream = Files.walk(rootPath)) {
            stream.sorted().forEach(candidate -> {
                if (!Files.isRegularFile(candidate, LinkOption.NOFOLLOW_LINKS)) {
                    return;
                }
                if (jsonOnly && !candidate.getFileName().toString().toLowerCase(Locale.ROOT).endsWith(JSON_EXTENSION)) {
                    return;
                }
                for (Path segment : rootPath.relativize(candidate)) {
                    if (segment.toString().startsWith(".")) {
                        return;
                    }
                }
                files.add(candidate);
            });
        } catch (IOException failure) {
            Gloss.logExceptionStack(false, failure, "HoloUi import could not scan %s.", rootPath);
        }
        return files;
    }

    private void writeReceipt(File source, boolean force, List<HoloUiImportEntry> entries) {
        JsonObject receipt = new JsonObject();
        receipt.addProperty("schemaVersion", RECEIPT_SCHEMA_VERSION);
        receipt.addProperty("importedAtMs", System.currentTimeMillis());
        receipt.addProperty("source", source.getAbsolutePath());
        receipt.addProperty("force", force);
        JsonArray lines = new JsonArray();
        for (HoloUiImportEntry entry : entries) {
            JsonObject line = new JsonObject();
            line.addProperty("path", entry.path());
            line.addProperty("disposition", entry.disposition().id());
            if (entry.detail() != null) {
                line.addProperty("detail", entry.detail());
            }
            lines.add(line);
        }
        receipt.add("entries", lines);
        try {
            if (!dataFolder.isDirectory()) {
                dataFolder.mkdirs();
            }
            Files.writeString(receiptFile().toPath(),
                RECEIPT_GSON.toJson(receipt) + System.lineSeparator(), StandardCharsets.UTF_8);
        } catch (IOException failure) {
            Gloss.logExceptionStack(false, failure, "HoloUi import could not write %s.", RECEIPT_FILE_NAME);
        }
    }

    private void logSummary(File source, List<HoloUiImportEntry> entries) {
        Gloss.info("HoloUi import from %s: %d entries.", source.getAbsolutePath(), entries.size());
        Map<String, EnumMap<HoloUiImportDisposition, Integer>> byCategory = new LinkedHashMap<>();
        for (HoloUiImportEntry entry : entries) {
            byCategory.computeIfAbsent(entry.category(), key -> new EnumMap<>(HoloUiImportDisposition.class))
                .merge(entry.disposition(), 1, Integer::sum);
        }
        for (Map.Entry<String, EnumMap<HoloUiImportDisposition, Integer>> category : byCategory.entrySet()) {
            StringBuilder line = new StringBuilder(category.getKey()).append(':');
            for (Map.Entry<HoloUiImportDisposition, Integer> count : category.getValue().entrySet()) {
                line.append(' ').append(count.getValue()).append(' ').append(count.getKey().id());
            }
            Gloss.info("HoloUi import: %s", line);
        }
    }

    private static String detail(Throwable failure) {
        String message = failure.getMessage();
        return message == null || message.isBlank() ? failure.getClass().getSimpleName() : message;
    }
}
