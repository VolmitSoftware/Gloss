package art.arcane.gloss.importer;

import art.arcane.gloss.Gloss;
import art.arcane.gloss.GlossConfig;
import art.arcane.gloss.config.GlossConfigFile;
import art.arcane.gloss.config.GlossConfigLoader;
import art.arcane.gloss.config.MenuComponentData;
import art.arcane.gloss.config.MenuDefinitionData;
import art.arcane.gloss.config.action.MenuActionData;
import art.arcane.gloss.config.components.ButtonComponentData;
import art.arcane.gloss.config.components.ComponentData;
import art.arcane.gloss.config.components.ListComponentData;
import art.arcane.gloss.config.components.ToggleComponentData;
import art.arcane.gloss.config.menu.MenuDocument;
import art.arcane.gloss.doc.DocumentHashes;
import art.arcane.gloss.history.HistoryKinds;
import art.arcane.gloss.lint.Diagnostic;
import art.arcane.gloss.lint.ImageRule;
import art.arcane.gloss.lint.LintContext;
import art.arcane.gloss.lint.LintRule;
import art.arcane.gloss.lint.NavigateTargetRule;
import art.arcane.gloss.lint.PanelRootRule;
import art.arcane.gloss.panel.PanelDefinition;
import art.arcane.gloss.persistence.GlossPersistenceCoordinator;
import art.arcane.gloss.persistence.GlossProjectTransaction;
import art.arcane.volmlib.util.config.TomlCodec;
import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.google.gson.JsonPrimitive;
import org.apache.commons.imaging.Imaging;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.TreeMap;
import java.util.UUID;
import java.util.function.Consumer;

/**
 * Prepares HoloUi content against captured source and destination bytes. Validated changes and
 * their receipt commit together; the source folder and editor credentials remain untouched.
 */
public final class HoloUiDataImporter {
    public static final String RECEIPT_FILE_NAME = "holoui-import.json";
    public static final int RECEIPT_SCHEMA_VERSION = 2;

    private static final Gson JSON = new GsonBuilder().setPrettyPrinting().disableHtmlEscaping().create();
    private static final List<String> COLLECTIONS = List.of("menus", "images", "boards", "previews");
    private static final Set<String> SECRETS = Set.of("editor-sync-sessions.json", "editor-sync-transactions",
        "editor-sync-backups", "custom-items.json");
    private static final Set<String> SETTING_KEYS = Set.of("debugHitbox", "debugPosition", "builderUrl",
        "editorSyncEnabled", "editorSyncCreateToken", "editorSyncSessionMinutes", "editorSyncPollSeconds",
        "editorSyncMaxProjectMiB", "previewEnabled", "previewLookDistance", "previewScale", "uiScale",
        "customItems", "customItemProviders");

    private final Path root;
    private final Services services;

    public record Services(GlossConfigLoader configLoader, GlossProjectTransaction transaction,
                           GlossPersistenceCoordinator coordinator) {
        public Services {
            Objects.requireNonNull(configLoader, "configLoader");
            Objects.requireNonNull(transaction, "transaction");
            Objects.requireNonNull(coordinator, "coordinator");
        }
    }

    public record Result(boolean sourcePresent, String sourcePath, List<HoloUiImportEntry> entries,
                         String backupPath, boolean applied) {
        public Result {
            entries = List.copyOf(entries);
        }

        public long count(HoloUiImportDisposition disposition) {
            return entries.stream().filter(entry -> entry.disposition() == disposition).count();
        }
    }

    public static final class Plan {
        private final HoloUiDataImporter owner;
        private final Path root;
        private final Path source;
        private final List<HoloUiImportEntry> entries;
        private final PreparedImport preparation;

        private Plan(HoloUiDataImporter owner, Draft draft) {
            this.owner = owner;
            this.root = draft.root;
            this.source = draft.source;
            this.entries = List.copyOf(draft.entries);
            this.preparation = draft.preparation;
        }

        public boolean sourcePresent() {
            return source != null;
        }

        public String sourcePath() {
            return source == null ? null : source.toString();
        }

        public List<HoloUiImportEntry> entries() {
            return entries;
        }

        public long retainedBytes() {
            return preparation.retainedBytes();
        }

        public List<String> targets() {
            return preparation.targets();
        }

        public boolean ready() {
            return sourcePresent() && entries.stream().noneMatch(entry -> switch (entry.disposition()) {
                case ERROR, CONFLICT, UNSUPPORTED -> true;
                default -> false;
            });
        }
    }

    public HoloUiDataImporter(File dataFolder, Services services) {
        this.root = Objects.requireNonNull(dataFolder, "dataFolder").toPath().toAbsolutePath().normalize();
        this.services = Objects.requireNonNull(services, "services");
    }

    public File receiptFile() {
        return root.resolve(RECEIPT_FILE_NAME).toFile();
    }

    /** The {@code holoui} (or {@code HoloUi}) directory beside the Gloss data folder, or null. */
    public File sourceDirectory() {
        Path parent = root.getParent();
        if (parent == null) {
            return null;
        }
        for (String name : List.of("holoui", "HoloUi")) {
            Path source = parent.resolve(name);
            if (Files.isDirectory(source, LinkOption.NOFOLLOW_LINKS)) {
                return source.toFile();
            }
        }
        return null;
    }

    /** Boot guard: run once, only while a source folder exists and no receipt has been written. */
    public boolean shouldRun() {
        return !receiptFile().exists() && sourceDirectory() != null;
    }

    public Plan preview(boolean overwrite) {
        return preview(overwrite, GlossConfig.current().imports());
    }

    Plan preview(boolean overwrite, GlossConfig.Imports limits) {
        File directory = sourceDirectory();
        Draft draft = new Draft(root, directory == null ? null : directory.toPath(), limits);
        if (draft.source == null) {
            return new Plan(this, draft);
        }
        try {
            draft.sourceFiles.addAll(sourceFiles(draft.source, draft.limits));
            List<Path> capturedFiles = List.copyOf(draft.sourceFiles);
            draft.preparation.checkBeforeApply(() -> {
                if (!capturedFiles.equals(sourceFiles(draft.source, draft.limits))) {
                    throw new IOException("HoloUi source file set changed after preview");
                }
            });
            JsonObject settings = readObject(draft, draft.source.resolve("settings.json"));
            for (String collection : COLLECTIONS) {
                prepareCollection(draft, collection, settings, overwrite);
            }
            prepareScales(draft, overwrite);
            prepareSettings(draft, settings, overwrite);
            recordExcluded(draft);
            validateProject(draft);
            draft.preparation.validateDocuments();
            prepareReceipt(draft, overwrite);
        } catch (IOException | RuntimeException failure) {
            draft.error("import", ".", failure);
        }
        return new Plan(this, draft);
    }

    public Result run(boolean overwrite) {
        return apply(preview(overwrite));
    }

    public Result apply(Plan plan) {
        Objects.requireNonNull(plan, "plan");
        if (plan.owner != this || !root.equals(plan.root)) {
            throw new IllegalArgumentException("Import plan belongs to another data directory");
        }
        if (!plan.ready()) {
            return unapplied(plan, null);
        }
        try {
            String backup = plan.preparation.apply("import-holoui", services.transaction(), services.coordinator());
            return new Result(true, plan.sourcePath(), plan.entries(), backup, true);
        } catch (IOException | RuntimeException failure) {
            Gloss.logExceptionStack(false, failure, "HoloUi import did not commit.");
            return unapplied(plan, failure);
        }
    }

    private Result unapplied(Plan plan, Throwable failure) {
        List<HoloUiImportEntry> entries = new ArrayList<>();
        for (HoloUiImportEntry entry : plan.entries()) {
            entries.add(switch (entry.disposition()) {
                case COPIED, OVERLAID_CONFIG_KEY, APPROXIMATED -> new HoloUiImportEntry(entry.category(),
                    entry.path(), HoloUiImportDisposition.NOT_APPLIED, "not applied: the import did not commit");
                default -> entry;
            });
        }
        if (failure != null) {
            entries.add(new HoloUiImportEntry("transaction", RECEIPT_FILE_NAME,
                HoloUiImportDisposition.ERROR, detail(failure)));
        }
        return new Result(plan.sourcePresent(), plan.sourcePath(), entries, null, false);
    }

    private void prepareCollection(Draft draft, String collection, JsonObject settings, boolean overwrite) {
        String destination = collection.equals("boards") ? "panels" : collection;
        Path sourceRoot = draft.source.resolve(collection);
        for (Path source : draft.sourceFiles) {
            if (!source.startsWith(sourceRoot)) {
                continue;
            }
            String relative = slash(sourceRoot.relativize(source));
            String report = collection + "/" + relative;
            Path target = root.resolve(destination).resolve(relative);
            try {
                byte[] content = draft.read(source);
                String conversion = "exact";
                if (collection.equals("previews")) {
                    JsonObject preview = object(content);
                    rewritePreviewKeys(preview);
                    for (String key : List.of("previewLookDistance", "previewScale")) {
                        if (settings.has(key)) {
                            double value = finiteNumber(settings.get(key));
                            preview.addProperty(key.equals("previewScale") ? "scale" : "viewDistance", value);
                        }
                    }
                    byte[] updated = encode(preview);
                    if (!preview.equals(object(content))) {
                        content = updated;
                        conversion = "exact: preview language namespace and global settings converted";
                    }
                } else if (collection.equals("images")) {
                    if (Imaging.getBufferedImage(content) == null) {
                        throw new IOException("Image decoder returned no image");
                    }
                }
                if (collection.equals("images")) {
                    draft.images.put(relative, content);
                } else {
                    HistoryKinds.DocumentPath document = HistoryKinds.resolve(root, slash(root.relativize(target)));
                    if (document == null) {
                        throw new IOException("Unsupported document path");
                    }
                    document.kind().parse(document.id(), new String(content, StandardCharsets.UTF_8));
                    draft.documents.put(target, content);
                }
                stage(draft, target, content, destination, report, overwrite, conversion);
            } catch (IOException | RuntimeException failure) {
                draft.error(destination, report, failure);
            }
        }
    }

    private void prepareScales(Draft draft, boolean overwrite) {
        Path source = draft.source.resolve("preview-scales.json");
        try {
            byte[] content = draft.read(source);
            if (content == null) {
                return;
            }
            JsonObject scales = object(content);
            for (Map.Entry<String, JsonElement> entry : scales.entrySet()) {
                UUID.fromString(entry.getKey());
                double factor = finiteNumber(entry.getValue());
                if (factor < 0.25D || factor > 2.5D) {
                    throw new IllegalArgumentException("Preview scale must be between 0.25 and 2.5");
                }
            }
            stage(draft, root.resolve("preview-scales.json"), content, "files", "preview-scales.json",
                overwrite, "exact");
        } catch (IOException | RuntimeException failure) {
            draft.error("files", "preview-scales.json", failure);
        }
    }

    private void prepareSettings(Draft draft, JsonObject settings, boolean overwrite) {
        if (settings.size() == 0) {
            return;
        }
        try {
            byte[] current = draft.preparation.read(services.configLoader().file().toPath());
            GlossConfigFile candidate = current == null ? new GlossConfigFile()
                : TomlCodec.fromToml(new String(current, StandardCharsets.UTF_8), GlossConfigFile.class);
            Objects.requireNonNull(candidate, "configuration").normalize();
            GlossConfigFile defaults = new GlossConfigFile();
            defaults.normalize();
            JsonObject previousReceipt = readObject(draft, root.resolve(RECEIPT_FILE_NAME));
            String settingsHash = draft.hashes.get("settings.json");
            boolean alreadyImported = previousReceipt.has("settingsSha256")
                && Objects.equals(settingsHash, previousReceipt.get("settingsSha256").getAsString());
            for (String key : settings.keySet()) {
                if (!SETTING_KEYS.contains(key)) {
                    draft.add("config", "settings.json:" + key, HoloUiImportDisposition.UNSUPPORTED,
                        "no verified conversion for this setting");
                }
            }
            if (settings.has("editorSyncCreateToken")) {
                draft.add("secrets", "settings.json:editorSyncCreateToken", HoloUiImportDisposition.SKIPPED_SECRET,
                    "editor credentials are never imported");
            }
            for (String key : List.of("previewLookDistance", "previewScale")) {
                if (settings.has(key)) {
                    boolean hasPreviews = draft.documents.keySet().stream().anyMatch(path -> path.startsWith(root.resolve("previews")));
                    draft.add("config", "settings.json:" + key, hasPreviews ? HoloUiImportDisposition.OVERLAID_CONFIG_KEY
                        : HoloUiImportDisposition.UNSUPPORTED, hasPreviews ? "exact: moved into imported preview documents"
                        : "no preview documents available to receive this setting");
                }
            }
            if (alreadyImported && !overwrite) {
                draft.add("config", "settings.json", HoloUiImportDisposition.UNCHANGED,
                    "this settings snapshot was already imported; destination settings retained");
                return;
            }
            JsonObject before = JSON.toJsonTree(candidate).getAsJsonObject();
            overlay(draft, settings, "debugHitbox", value -> candidate.debug.hitbox = bool(value));
            overlay(draft, settings, "debugPosition", value -> candidate.debug.position = bool(value));
            overlay(draft, settings, "builderUrl", value -> candidate.editor.builderUrl = string(value));
            overlay(draft, settings, "editorSyncEnabled", value -> candidate.editor.sync.enabled = bool(value));
            overlay(draft, settings, "editorSyncSessionMinutes", value -> candidate.editor.sync.sessionMinutes = integer(value));
            overlay(draft, settings, "editorSyncPollSeconds", value -> candidate.editor.sync.pollSeconds = integer(value));
            overlay(draft, settings, "editorSyncMaxProjectMiB", value -> candidate.editor.sync.maxProjectMiB = integer(value));
            overlay(draft, settings, "previewEnabled", value -> candidate.features.previews = bool(value));
            overlay(draft, settings, "uiScale", value -> candidate.menus.uiScale = finiteNumber(value));
            overlay(draft, settings, "customItems", value -> candidate.items.customItems = bool(value));
            overlay(draft, settings, "customItemProviders", value -> candidate.items.customItemProviders = splitCsv(string(value)));
            JsonObject authored = JSON.toJsonTree(candidate).getAsJsonObject();
            byte[] encoded = services.configLoader().encode(candidate);
            JsonObject normalized = JSON.toJsonTree(candidate).getAsJsonObject();
            if (!authored.equals(normalized)) {
                draft.add("config", "settings.json", HoloUiImportDisposition.APPROXIMATED,
                    "configuration values normalize to current supported ranges and provider identifiers");
            }
            if (!overwrite && conflicts(before, normalized, JSON.toJsonTree(defaults).getAsJsonObject())) {
                draft.add("config", GlossConfigLoader.FILE_NAME, HoloUiImportDisposition.CONFLICT,
                    "import would replace customized destination settings; preview with overwrite to replace them");
            }
            if (!before.equals(normalized)) {
                draft.preparation.stage(services.configLoader().file().toPath(), encoded);
            }
        } catch (IOException | RuntimeException failure) {
            draft.error("config", "settings.json", failure);
        }
    }

    private static boolean conflicts(JsonObject before, JsonObject after, JsonObject defaults) {
        for (String key : after.keySet()) {
            JsonElement oldValue = before.get(key);
            JsonElement next = after.get(key);
            JsonElement baseline = defaults.get(key);
            if (Objects.equals(oldValue, next)) {
                continue;
            }
            if (oldValue != null && oldValue.isJsonObject() && next.isJsonObject()
                && baseline != null && baseline.isJsonObject()) {
                if (conflicts(oldValue.getAsJsonObject(), next.getAsJsonObject(), baseline.getAsJsonObject())) {
                    return true;
                }
            } else if (!Objects.equals(oldValue, baseline)) {
                return true;
            }
        }
        return false;
    }

    private static void overlay(Draft draft, JsonObject settings, String key, Consumer<JsonElement> setter) {
        if (!settings.has(key)) {
            return;
        }
        try {
            setter.accept(settings.get(key));
            draft.add("config", "settings.json:" + key, HoloUiImportDisposition.OVERLAID_CONFIG_KEY, "exact");
        } catch (RuntimeException failure) {
            draft.error("config", "settings.json:" + key, failure);
        }
    }

    private static void stage(Draft draft, Path target, byte[] content, String category,
                              String report, boolean overwrite, String conversion) throws IOException {
        byte[] previous = draft.preparation.read(target);
        if (Arrays.equals(previous, content)) {
            draft.add(category, report, HoloUiImportDisposition.UNCHANGED, "destination already matches");
        } else if (previous != null && !overwrite) {
            draft.add(category, report, HoloUiImportDisposition.CONFLICT, "destination differs from imported content");
        } else {
            draft.preparation.stage(target, content);
            draft.add(category, report, HoloUiImportDisposition.COPIED, conversion);
        }
    }

    private void validateProject(Draft draft) throws IOException {
        LintContext.Builder builder = LintContext.builder();
        Map<UUID, String> panelIds = new LinkedHashMap<>();
        ImportSourceFiles sources = new ImportSourceFiles(draft.limits);
        for (String collection : List.of("menus", "panels", "previews")) {
            for (Path file : files(sources, root.resolve(collection), true)) {
                if (!draft.documents.containsKey(file)) {
                    draft.documents.put(file, draft.preparation.read(file));
                }
            }
        }
        for (Map.Entry<Path, byte[]> entry : draft.documents.entrySet()) {
            HistoryKinds.DocumentPath document = HistoryKinds.resolve(root, slash(root.relativize(entry.getKey())));
            String raw = new String(entry.getValue(), StandardCharsets.UTF_8);
            Object parsed = document.kind().parse(document.id(), raw).value();
            if (parsed instanceof MenuDocument menu) {
                validateComponents(menu.definition().getComponents());
                for (MenuDefinitionData.Variant variant : menu.definition().getVariants()) {
                    validateComponents(variant.components());
                }
            }
            if (parsed instanceof PanelDefinition panel) {
                String previous = panelIds.putIfAbsent(panel.uuid(), panel.id());
                if (previous != null) {
                    throw new IOException("Panel UUID is shared by " + previous + " and " + panel.id());
                }
            }
            builder.document(document.collection(), document.id(), raw);
        }
        for (Path file : files(sources, root.resolve("images"), false)) {
            draft.preparation.read(file);
            builder.image(slash(root.resolve("images").relativize(file)));
        }
        draft.images.keySet().forEach(builder::image);
        LintContext context = builder.build();
        for (LintRule rule : List.of(new PanelRootRule(), new NavigateTargetRule(), new ImageRule())) {
            for (Diagnostic diagnostic : rule.check(context)) {
                draft.add(diagnostic.kind(), diagnostic.id(), HoloUiImportDisposition.ERROR, diagnostic.message());
            }
        }
    }

    private static void recordExcluded(Draft draft) {
        for (String secret : SECRETS) {
            if (Files.exists(draft.source.resolve(secret), LinkOption.NOFOLLOW_LINKS)) {
                draft.add("secrets", secret, HoloUiImportDisposition.SKIPPED_SECRET,
                    "credentials, editor state, and regenerable provider exports are not imported");
            }
        }
        if (Files.exists(draft.source.resolve("language.yml"), LinkOption.NOFOLLOW_LINKS)) {
            draft.add("config", "language.yml", HoloUiImportDisposition.UNSUPPORTED,
                "no verified conversion to the current localization catalog");
        }
    }

    private static void validateComponents(List<MenuComponentData> components) {
        if (components == null) {
            throw new IllegalArgumentException("Menu components are missing");
        }
        for (MenuComponentData component : components) {
            if (component == null || component.data() == null) {
                throw new IllegalArgumentException("Menu contains an incomplete component");
            }
            validateComponent(component.data());
        }
    }

    private static void validateComponent(ComponentData component) {
        if (component instanceof ButtonComponentData button) {
            validateActions(button.actions());
        } else if (component instanceof ToggleComponentData toggle) {
            validateActions(toggle.trueActions());
            validateActions(toggle.falseActions());
        } else if (component instanceof ListComponentData list) {
            validateComponent(list.template());
        }
    }

    private static void validateActions(List<MenuActionData> actions) {
        if (actions == null) {
            return;
        }
        for (MenuActionData action : actions) {
            if (action == null) {
                throw new IllegalArgumentException("Menu contains an empty action");
            }
            String invalid = action.invalidReason();
            if (invalid != null) {
                throw new IllegalArgumentException(invalid);
            }
            if (action.createAction() == null) {
                throw new IllegalArgumentException("Unsupported menu action");
            }
            validateActions(action.nestedActions());
        }
    }

    private void prepareReceipt(Draft draft, boolean overwrite) throws IOException {
        byte[] previous = draft.preparation.read(root.resolve(RECEIPT_FILE_NAME));
        if (previous != null && draft.preparation.targets().isEmpty()) {
            return;
        }
        JsonObject receipt = new JsonObject();
        receipt.addProperty("schemaVersion", RECEIPT_SCHEMA_VERSION);
        receipt.addProperty("source", draft.source.toString());
        receipt.addProperty("overwrite", overwrite);
        receipt.addProperty("settingsSha256", draft.hashes.get("settings.json"));
        receipt.add("sourceHashes", JSON.toJsonTree(draft.hashes));
        JsonArray entries = new JsonArray();
        for (HoloUiImportEntry entry : draft.entries) {
            JsonObject row = new JsonObject();
            row.addProperty("category", entry.category());
            row.addProperty("path", entry.path());
            row.addProperty("disposition", entry.disposition().id());
            row.addProperty("detail", entry.detail());
            entries.add(row);
        }
        receipt.add("entries", entries);
        draft.preparation.stage(root.resolve(RECEIPT_FILE_NAME), encode(receipt));
    }

    private static JsonObject readObject(Draft draft, Path file) throws IOException {
        byte[] content = file.startsWith(draft.source) ? draft.read(file) : draft.preparation.read(file);
        return content == null ? new JsonObject() : object(content);
    }

    private static JsonObject object(byte[] content) {
        JsonElement parsed = JsonParser.parseString(new String(content, StandardCharsets.UTF_8));
        if (!parsed.isJsonObject()) {
            throw new IllegalArgumentException("Expected a JSON object");
        }
        return parsed.getAsJsonObject();
    }

    private static void rewritePreviewKeys(JsonElement node) {
        if (node.isJsonObject()) {
            for (Map.Entry<String, JsonElement> entry : node.getAsJsonObject().entrySet()) {
                if (entry.getValue().isJsonPrimitive() && entry.getValue().getAsJsonPrimitive().isString()) {
                    entry.setValue(new JsonPrimitive(entry.getValue().getAsString()
                        .replace("holoui.preview.", "gloss.preview.")));
                } else {
                    rewritePreviewKeys(entry.getValue());
                }
            }
        } else if (node.isJsonArray()) {
            for (int index = 0; index < node.getAsJsonArray().size(); index++) {
                JsonElement value = node.getAsJsonArray().get(index);
                if (value.isJsonPrimitive() && value.getAsJsonPrimitive().isString()) {
                    node.getAsJsonArray().set(index, new JsonPrimitive(value.getAsString()
                        .replace("holoui.preview.", "gloss.preview.")));
                } else {
                    rewritePreviewKeys(value);
                }
            }
        }
    }

    private static List<Path> sourceFiles(Path source, GlossConfig.Imports limits) throws IOException {
        ImportSourceFiles sources = new ImportSourceFiles(limits);
        List<Path> paths = new ArrayList<>();
        for (String collection : COLLECTIONS) {
            paths.addAll(files(sources, source.resolve(collection), !collection.equals("images")));
        }
        for (String name : List.of("settings.json", "preview-scales.json")) {
            paths.addAll(sources.single(source.resolve(name)));
        }
        paths.sort(Path::compareTo);
        return List.copyOf(paths);
    }

    private static List<Path> files(ImportSourceFiles sources, Path directory, boolean jsonOnly) throws IOException {
        return sources.collect(directory, true, path ->
            (!jsonOnly || path.getFileName().toString().endsWith(".json"))
                && visible(directory.relativize(path)));
    }

    static List<String> splitCsv(String csv) {
        return csv == null || csv.isBlank() ? new ArrayList<>()
            : new ArrayList<>(Arrays.stream(csv.split(",")).map(String::trim).filter(value -> !value.isEmpty()).toList());
    }

    private static double finiteNumber(JsonElement value) {
        if (!value.isJsonPrimitive() || !value.getAsJsonPrimitive().isNumber() || !Double.isFinite(value.getAsDouble())) {
            throw new IllegalArgumentException("Expected a finite number");
        }
        return value.getAsDouble();
    }

    private static int integer(JsonElement value) {
        finiteNumber(value);
        return value.getAsBigDecimal().intValueExact();
    }

    private static boolean bool(JsonElement value) {
        if (!value.isJsonPrimitive() || !value.getAsJsonPrimitive().isBoolean()) {
            throw new IllegalArgumentException("Expected a boolean");
        }
        return value.getAsBoolean();
    }

    private static String string(JsonElement value) {
        if (!value.isJsonPrimitive() || !value.getAsJsonPrimitive().isString()) {
            throw new IllegalArgumentException("Expected a string");
        }
        return value.getAsString();
    }

    private static byte[] encode(JsonElement value) {
        return (JSON.toJson(value) + System.lineSeparator()).getBytes(StandardCharsets.UTF_8);
    }

    private static String slash(Path path) {
        return path.toString().replace(File.separatorChar, '/');
    }

    private static String detail(Throwable failure) {
        return failure.getMessage() == null ? failure.getClass().getSimpleName() : failure.getMessage();
    }

    private static final class Draft {
        private final Path root;
        private final Path source;
        private final GlossConfig.Imports limits;
        private final PreparedImport preparation;
        private final List<Path> sourceFiles = new ArrayList<>();
        private final List<HoloUiImportEntry> entries = new ArrayList<>();
        private final Map<String, String> hashes = new TreeMap<>();
        private final Map<Path, byte[]> documents = new LinkedHashMap<>();
        private final Map<String, byte[]> images = new LinkedHashMap<>();

        private Draft(Path root, Path source, GlossConfig.Imports limits) {
            this.root = root;
            this.source = source;
            this.limits = limits;
            this.preparation = new PreparedImport(root, limits);
        }

        private byte[] read(Path file) throws IOException {
            byte[] content = preparation.read(file);
            if (content != null) {
                hashes.put(slash(source.relativize(file)), DocumentHashes.sha256(content));
            }
            return content;
        }

        private void add(String category, String path, HoloUiImportDisposition disposition, String detail) {
            entries.add(new HoloUiImportEntry(category, path, disposition, detail));
        }

        private void error(String category, String path, Throwable failure) {
            add(category, path, HoloUiImportDisposition.ERROR, HoloUiDataImporter.detail(failure));
        }
    }

    private static boolean visible(Path relative) {
        for (Path segment : relative) {
            if (segment.toString().startsWith(".")) {
                return false;
            }
        }
        return true;
    }
}
