package art.arcane.gloss.importer;


import art.arcane.gloss.condition.ShowCondition;
import art.arcane.gloss.Gloss;
import art.arcane.volmlib.util.config.TomlCodec;
import art.arcane.gloss.animation.AnimationDoc;
import art.arcane.gloss.bubble.BubbleStyleDoc;
import art.arcane.gloss.config.GlossConfigFile;
import art.arcane.gloss.config.GlossConfigLoader;
import art.arcane.gloss.doc.DocumentHashes;
import art.arcane.gloss.persistence.GlossPersistenceCoordinator;
import art.arcane.gloss.persistence.GlossProjectTransaction;
import art.arcane.gloss.doc.DocumentEnvelope;
import art.arcane.gloss.drop.RealDropSettingsDoc;
import art.arcane.gloss.emoji.EmojiDoc;
import art.arcane.gloss.hologram.HologramDoc;
import art.arcane.gloss.history.HistoryKinds;
import art.arcane.gloss.editor.sync.EditorSyncDocumentKind;
import art.arcane.gloss.motd.MotdDoc;
import art.arcane.gloss.doc.DocumentParsers;
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

/**
 * Prepares supported older Gloss content before publishing a validated transaction. Original
 * documents are archived by the transaction; config.yml remains unchanged and a source-hash
 * receipt prevents applying the same configuration overlay twice.
 */
public final class LegacyGlossDataImporter {
    public static final String RECEIPT_FILE_NAME = "legacy-import.json";
    public static final String LEGACY_CONFIG_FILE_NAME = "config.yml";

    private static final String JSON_EXTENSION = ".json";
    private static final String LEGACY_NO_TRIGGER = "<uses :id:>";

    public enum Status {
        MIGRATED,
        APPROXIMATED,
        UNSUPPORTED,
        SKIPPED_ENVELOPE,
        SKIPPED_NOTE,
        CONFLICT,
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
    private final Services services;
    private PreparedImport preparation;

    public record Services(GlossConfigLoader configLoader, GlossProjectTransaction transaction,
                           GlossPersistenceCoordinator coordinator) {
        public Services {
            Objects.requireNonNull(configLoader, "configLoader");
            Objects.requireNonNull(transaction, "transaction");
            Objects.requireNonNull(coordinator, "coordinator");
        }
    }

    public static final class Plan {
        private final LegacyGlossDataImporter owner;
        private final List<Entry> entries;
        private final PreparedImport preparation;

        private Plan(LegacyGlossDataImporter owner, List<Entry> entries, PreparedImport preparation) {
            this.owner = owner;
            this.entries = List.copyOf(entries);
            this.preparation = preparation;
        }

        public List<Entry> entries() {
            return entries;
        }

        public long retainedBytes() {
            return preparation.retainedBytes();
        }

        public List<String> targets() {
            return preparation.targets();
        }

        public boolean ready() {
            return entries.stream().noneMatch(entry -> entry.status() == Status.ERROR
                || entry.status() == Status.CONFLICT || entry.status() == Status.UNSUPPORTED);
        }
    }

    public LegacyGlossDataImporter(File dataFolder, Services services) {
        this.dataFolder = Objects.requireNonNull(dataFolder, "dataFolder").getAbsoluteFile();
        this.services = Objects.requireNonNull(services, "services");
    }

    public synchronized Plan preview() {
        preparation = new PreparedImport(dataFolder.toPath());
        List<Entry> entries = new ArrayList<>();
        GlossConfigFile candidate;
        try {
            byte[] current = preparation.read(services.configLoader().file().toPath());
            candidate = current == null ? new GlossConfigFile() : TomlCodec.fromToml(
                new String(current, StandardCharsets.UTF_8), GlossConfigFile.class);
            Objects.requireNonNull(candidate, "configuration").normalize();
        } catch (IOException | RuntimeException failure) {
            entries.add(new Entry("config", GlossConfigLoader.FILE_NAME, Status.ERROR, detail(failure)));
            Gloss.logExceptionStack(false, failure, "Unable to prepare Gloss configuration.");
            return new Plan(this, entries, preparation);
        }
        migrateKind(HologramDoc.KIND, LegacyGlossDataImporter::convertHologram, entries);
        migrateKind(EmojiDoc.KIND, LegacyGlossDataImporter::convertEmoji, entries);
        migrateKind(AnimationDoc.KIND, LegacyGlossDataImporter::convertAnimation, entries);
        for (String kind : List.of("boards", "bubbles", "damage-indicators", "real-drops", "entity-overlays", "tablist", "channels", "behaviors")) {
            migrateKind(kind, null, entries);
        }
        overlayLegacyConfig(candidate, entries);
        if (entries.stream().noneMatch(entry -> entry.status() == Status.ERROR
            || entry.status() == Status.UNSUPPORTED || entry.status() == Status.CONFLICT)) {
            try {
                preparation.validateDocuments();
            } catch (IOException | RuntimeException failure) {
                entries.add(new Entry("project", "documents", Status.ERROR, detail(failure)));
                Gloss.logExceptionStack(false, failure, "Unable to validate imported Gloss project.");
            }
        }
        return new Plan(this, entries, preparation);
    }

    public Result run() {
        return apply(preview());
    }

    public Result apply(Plan plan) {
        Objects.requireNonNull(plan, "plan");
        if (plan.owner != this) {
            throw new IllegalArgumentException("Import plan belongs to another importer");
        }
        if (!plan.ready()) {
            return unapplied(plan, null);
        }
        try {
            String backupPath = plan.preparation.apply("import-legacy", services.transaction(), services.coordinator());
            logReport(plan.entries());
            return new Result(plan.entries(), backupPath);
        } catch (IOException | RuntimeException failure) {
            Gloss.logExceptionStack(false, failure, "Legacy Gloss import did not complete.");
            return unapplied(plan, failure);
        }
    }

    private Result unapplied(Plan plan, Throwable failure) {
        List<Entry> entries = new ArrayList<>();
        for (Entry entry : plan.entries()) {
            boolean changed = entry.status() == Status.MIGRATED || entry.status() == Status.APPROXIMATED || entry.status() == Status.OVERLAID
                || entry.status() == Status.ABSORBED;
            entries.add(changed ? new Entry(entry.kind(), entry.path(), Status.SKIPPED_NOTE,
                "not applied: the import did not commit") : entry);
        }
        if (failure != null) {
            entries.add(new Entry("transaction", "legacy", Status.ERROR, detail(failure)));
        }
        logReport(entries);
        return new Result(entries, null);
    }

    private void migrateKind(String kind, Function<JsonObject, Object> converter, List<Entry> entries) {
        EditorSyncDocumentKind documentKind = HistoryKinds.requireCollection(kind);
        if (documentKind.layout() == EditorSyncDocumentKind.Layout.SINGLE) {
            File file = documentKind.path(dataFolder.toPath(), documentKind.singletonId()).toFile();
            if (file.exists()) {
                migrateFile(kind, file, converter, entries);
            }
            return;
        }
        File folder = new File(dataFolder, kind);
        File[] files = folder.listFiles(file -> file.isFile()
            && file.getName().toLowerCase(Locale.ROOT).endsWith(JSON_EXTENSION)
            && !file.getName().startsWith("."));
        if (files == null) {
            return;
        }
        Arrays.sort(files, Comparator.comparing(File::getName));
        for (File file : files) {
            migrateFile(kind, file, converter, entries);
        }
    }

    private void migrateFile(String kind, File file, Function<JsonObject, Object> converter, List<Entry> entries) {
        String path = dataFolder.toPath().relativize(file.toPath()).toString();
        try {
            String raw = new String(preparation.read(file.toPath()), StandardCharsets.UTF_8);
            JsonElement parsed = JsonParser.parseString(raw);
            if (!parsed.isJsonObject()) {
                throw new IllegalArgumentException("document is not a JSON object");
            }
            JsonObject legacy = parsed.getAsJsonObject();
            if (legacy.has("schemaVersion")) {
                migrateEnvelope(kind, file, legacy, entries);
                return;
            }
            if (converter == null) {
                entries.add(new Entry(kind, path, Status.UNSUPPORTED, "No supported unversioned format for " + kind));
                return;
            }
            legacy.addProperty("_fileName", baseName(file));
            Object converted = converter.apply(legacy);
            writeDocument(file.toPath(), converted);
            entries.add(Entry.of(kind, path, Status.MIGRATED));
        } catch (IOException | RuntimeException failure) {
            Status status = failure instanceof VersionedGlossConverter.UnsupportedFormatException
                || DocumentEnvelope.isUnsupportedSchemaVersion(failure) ? Status.UNSUPPORTED : Status.ERROR;
            entries.add(new Entry(kind, path, status, detail(failure)));
            Gloss.logExceptionStack(false, failure, "Unable to prepare legacy document %s.", path);
        }
    }

    private void migrateEnvelope(String kind, File file, JsonObject source, List<Entry> entries) throws IOException {
        String path = dataFolder.toPath().relativize(file.toPath()).toString();
        if (kind.equals(EmojiDoc.KIND) || kind.equals(AnimationDoc.KIND)) {
            HistoryKinds.requireCollection(kind).parse(baseName(file), source.toString());
            entries.add(Entry.of(kind, path, Status.SKIPPED_ENVELOPE));
            return;
        }
        VersionedGlossConverter.Conversion conversion;
        if (kind.equals("channels")) {
            ChannelSchemaConverter.Conversion channel = ChannelSchemaConverter.convert(baseName(file), source);
            conversion = new VersionedGlossConverter.Conversion(channel.document(), channel.warnings());
        } else {
            conversion = VersionedGlossConverter.convert(kind, baseName(file), source);
        }
        if (source.equals(conversion.document())) {
            entries.add(Entry.of(kind, path, Status.SKIPPED_ENVELOPE));
            return;
        }
        JsonObject converted = conversion.document();
        converted.addProperty("revision", DocumentEnvelope.requireRevision(kind, converted.get("revision").getAsLong() + 1L));
        writeDocument(file.toPath(), converted);
        entries.add(new Entry(kind, path, conversion.warnings().isEmpty() ? Status.MIGRATED : Status.APPROXIMATED,
            conversion.warnings().isEmpty() ? "Exact schema conversion" : String.join(" ", conversion.warnings())));
    }

    private static HologramDoc convertHologram(JsonObject legacy) {
        Vector position = new Vector(
            legacy.get("x").getAsDouble(),
            legacy.get("y").getAsDouble(),
            legacy.get("z").getAsDouble());
        HologramDoc.Anchor anchor = new HologramDoc.Anchor(legacy.get("world").getAsString(), position);
        return new HologramDoc(HologramDoc.CURRENT_SCHEMA_VERSION, DocumentEnvelope.INITIAL_REVISION,
            anchor, stringList(legacy.getAsJsonArray("lines")), null, null, 0.0D, 0.0D, List.of(), ShowCondition.ALWAYS, null, null);
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
        try {
            byte[] source = preparation.read(legacyConfig.toPath());
            String sourceHash = DocumentHashes.sha256(source);
            Path receiptPath = dataFolder.toPath().resolve(RECEIPT_FILE_NAME);
            byte[] receiptBytes = preparation.read(receiptPath);
            if (receiptBytes != null) {
                JsonObject receipt = JsonParser.parseString(new String(receiptBytes, StandardCharsets.UTF_8)).getAsJsonObject();
                if (sourceHash.equals(optString(receipt, "configSha256", ""))) {
                    return;
                }
            }
            YamlConfiguration yaml = new YamlConfiguration();
            yaml.loadFromString(new String(source, StandardCharsets.UTF_8));
            overlayMechanics(yaml, config, entries);
            overlayTablistContent(yaml, entries);
            overlayBubbleContent(yaml, entries);
            overlayIndicatorContent(yaml, entries);
            overlayMotdContent(yaml, entries);
            overlayDropLabelContent(yaml, entries);
            preparation.stage(services.configLoader().file().toPath(), services.configLoader().encode(config));
            JsonObject receipt = new JsonObject();
            receipt.addProperty("schemaVersion", 1);
            receipt.addProperty("configSha256", sourceHash);
            preparation.stage(receiptPath, (DocumentParsers.GSON.toJson(receipt)
                + System.lineSeparator()).getBytes(StandardCharsets.UTF_8));
        } catch (IOException | InvalidConfigurationException | RuntimeException failure) {
            entries.add(new Entry("config", LEGACY_CONFIG_FILE_NAME, Status.ERROR, detail(failure)));
            Gloss.logExceptionStack(false, failure, "Unable to prepare legacy configuration.");
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
        overlay(yaml, "commands.sounds", entries, () -> config.commands.sounds = yaml.getBoolean("commands.sounds"));
    }

    private void overlay(YamlConfiguration yaml, String key, List<Entry> entries, Runnable apply) {
        if (!yaml.contains(key)) {
            return;
        }
        apply.run();
        entries.add(Entry.of("config", LEGACY_CONFIG_FILE_NAME + ":" + key, Status.OVERLAID));
    }

    private void overlayTablistContent(YamlConfiguration yaml, List<Entry> entries) {
        if (!yaml.contains("tablist.header") && !yaml.contains("tablist.footer")
            && !yaml.contains("tablist.use-header-footers") && !yaml.contains("tablist.group-list-names")) {
            return;
        }
        try {
            String path = "tablist.json";
            JsonObject document = shippedDestination(path, "/defaults/tablist/tablist.json", entries);
            if (document == null) {
                return;
            }
            JsonObject header = document.getAsJsonObject("headerFooter");
            JsonObject presentation = header.getAsJsonObject("presentation");
            header.addProperty("enabled", yaml.getBoolean("tablist.use-header-footers", header.get("enabled").getAsBoolean()));
            presentation.addProperty("header", yaml.getString("tablist.header", presentation.get("header").getAsString()));
            presentation.addProperty("footer", yaml.getString("tablist.footer", presentation.get("footer").getAsString()));
            JsonObject names = document.getAsJsonObject("listNames");
            names.addProperty("enabled", yaml.getBoolean("tablist.group-list-names", names.get("enabled").getAsBoolean()));
            writeDocument(dataFolder.toPath().resolve(path), document);
            entries.add(Entry.of("config", LEGACY_CONFIG_FILE_NAME + ":tablist", Status.OVERLAID));
        } catch (IOException | RuntimeException failure) {
            entries.add(new Entry("config", "tablist.json", Status.ERROR, detail(failure)));
            Gloss.logExceptionStack(false, failure, "Unable to prepare imported tablist settings.");
        }
    }

    private void overlayIndicatorContent(YamlConfiguration yaml, List<Entry> entries) {
        if (!yaml.contains("damage-indicators")) {
            return;
        }
        String path = "damage-indicators/default.json";
        try {
            JsonObject document = shippedDestination(path, "/defaults/" + path, entries);
            if (document == null) {
                return;
            }
            JsonObject limits = document.getAsJsonObject("limits");
            limits.addProperty("maxPerSecond", yaml.getInt("damage-indicators.max-indicators-per-second", 40));
            limits.addProperty("lifetimeMs", yaml.getLong("damage-indicators.max-ms-alive", 3000));
            limits.addProperty("decimals", yaml.getInt("damage-indicators.decimals", 0));
            double ticks = yaml.getInt("holograms.temporary-update-interval-ticks", 2);
            if (ticks <= 0) {
                throw new IllegalArgumentException("holograms.temporary-update-interval-ticks must be positive");
            }
            double stepsPerSecond = 20.0D / ticks;
            double horizontal = yaml.getDouble("damage-indicators.motion.random-throw-force", 0.08D) * stepsPerSecond;
            double vertical = yaml.getDouble("damage-indicators.motion.initial-up-force", 0.13D) * stepsPerSecond;
            double acceleration = yaml.getDouble("damage-indicators.motion.gravity-factor", 0.0093D)
                * stepsPerSecond * stepsPerSecond;
            for (String kind : List.of("damage", "healing")) {
                boolean damage = kind.equals("damage");
                JsonObject style = document.getAsJsonObject(kind);
                style.addProperty("when", damage || yaml.getBoolean("damage-indicators.show-heals", true) ? "true" : "false");
                JsonObject presentation = style.getAsJsonObject("presentation");
                presentation.addProperty("format", yaml.getString(damage ? "damage-indicators.damage-indicator-prefix"
                    : "damage-indicators.heal-indicator-prefix", damage ? "&c&l" : "&a&l") + "{amount}");
                JsonObject motion = presentation.getAsJsonObject("motion");
                motion.addProperty("horizontalSpeed", horizontal);
                motion.addProperty("verticalSpeed", vertical);
                motion.addProperty("verticalAcceleration", damage ? -acceleration : acceleration / 19.5D);
                JsonObject transform = presentation.getAsJsonObject("transform");
                transform.addProperty("startScale", 1);
                transform.addProperty("endScale", 1);
                transform.addProperty("fadeStartFraction", 1);
            }
            writeDocument(dataFolder.toPath().resolve(path), document);
            entries.add(new Entry("config", LEGACY_CONFIG_FILE_NAME + ":damage-indicators", Status.APPROXIMATED,
                "Lifetime, rate, text, healing visibility and motion rates are retained; continuous motion uses the current scatter distribution."));
        } catch (IOException | RuntimeException failure) {
            entries.add(new Entry("config", path, Status.ERROR, detail(failure)));
            Gloss.logExceptionStack(false, failure, "Unable to prepare imported damage-indicator settings.");
        }
    }

    private JsonObject shippedDestination(String path, String resource, List<Entry> entries) throws IOException {
        byte[] shipped = readResource(resource);
        Path target = dataFolder.toPath().resolve(path);
        byte[] existing = preparation.read(target);
        if (existing != null && !Arrays.equals(shipped, existing)) {
            entries.add(new Entry("config", path, Status.CONFLICT,
                path + " was already customized; config.yml content not applied"));
            return null;
        }
        JsonObject document = JsonParser.parseString(new String(shipped, StandardCharsets.UTF_8)).getAsJsonObject();
        document.addProperty("revision", document.get("revision").getAsLong() + 1L);
        return document;
    }

    private void overlayBubbleContent(YamlConfiguration yaml, List<Entry> entries) {
        if (!yaml.contains("chat-bubbles")) {
            return;
        }
        boolean present = yaml.contains("chat-bubbles.message.prefix") || yaml.contains("chat-bubbles.message.offset")
            || yaml.contains("chat-bubbles.word-wrap-break-chars") || yaml.contains("chat-bubbles.max-time-alive")
            || yaml.contains("chat-bubbles.line-stagger-ticks") || yaml.contains("chat-bubbles.fly-away")
            || yaml.contains("chat-bubbles.follow-players") || yaml.contains("chat-bubbles.hide-own-messages")
            || yaml.contains("chat-bubbles.blacklist-worlds");
        if (!present) {
            return;
        }
        File styleFile = new File(new File(dataFolder, BubbleStyleDoc.KIND), "default" + JSON_EXTENSION);
        try {
            byte[] shipped = readResource("/defaults/" + BubbleStyleDoc.KIND + "/default" + JSON_EXTENSION);
            if (styleFile.isFile() && !Arrays.equals(shipped, preparation.read(styleFile.toPath()))) {
                entries.add(new Entry("config", "bubbles/default" + JSON_EXTENSION, Status.CONFLICT,
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
                new BubbleStyleDoc.Shimmer(false, false, null, null, null, null, null),
                base.select(),
                base.particleLayers(), base.show(), null, null,
                yaml.getDouble("holograms.stack-distance", base.stackDistance()),
                yaml.getStringList("chat-bubbles.blacklist-worlds"), null, null);
            writeDocument(styleFile.toPath(), updated);
            entries.add(new Entry("config", LEGACY_CONFIG_FILE_NAME + ":chat-bubbles", Status.APPROXIMATED,
                "Wrapped messages use one display block; per-line spawning and line-stagger-ticks are not retained."));
        } catch (IOException | RuntimeException failure) {
            entries.add(new Entry("config", "bubbles/default" + JSON_EXTENSION, Status.ERROR, detail(failure)));
            Gloss.logExceptionStack(false, failure, "Unable to prepare imported bubble settings.");
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
            if (motdFile.isFile() && !Arrays.equals(shipped, preparation.read(motdFile.toPath()))) {
                entries.add(new Entry("config", MotdDoc.KIND + JSON_EXTENSION, Status.CONFLICT,
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
                motdEntries.add(MotdDoc.MotdEntry.ofLines(lines));
            }
            if (motdEntries.isEmpty()) {
                return;
            }
            MotdDoc updated = new MotdDoc(MotdDoc.CURRENT_SCHEMA_VERSION, DocumentEnvelope.INITIAL_REVISION, ShowCondition.ALWAYS,
                null, motdEntries, List.of(), null, null, null, null);
            writeDocument(motdFile.toPath(), updated);
            entries.add(Entry.of("config", LEGACY_CONFIG_FILE_NAME + ":motd.texts", Status.OVERLAID));
        } catch (IOException | RuntimeException failure) {
            entries.add(new Entry("config", MotdDoc.KIND + JSON_EXTENSION, Status.ERROR, detail(failure)));
            Gloss.logExceptionStack(false, failure, "Unable to prepare imported MOTD settings.");
        }
    }

    private void overlayDropLabelContent(YamlConfiguration yaml, List<Entry> entries) {
        String format = yaml.getString("drops.name-format");
        if (format == null || format.isBlank()) {
            return;
        }
        String documentName = RealDropSettingsDoc.KIND + "/" + RealDropSettingsDoc.DEFAULT_ID + JSON_EXTENSION;
        File documentFile = new File(new File(dataFolder, RealDropSettingsDoc.KIND),
            RealDropSettingsDoc.DEFAULT_ID + JSON_EXTENSION);
        try {
            byte[] shipped = readResource("/defaults/" + documentName);
            if (documentFile.isFile() && !Arrays.equals(shipped, preparation.read(documentFile.toPath()))) {
                entries.add(new Entry("config", documentName, Status.CONFLICT,
                    documentName + " was already customized; config.yml drops.name-format not applied"));
                return;
            }
            JsonObject document = JsonParser.parseString(new String(shipped, StandardCharsets.UTF_8)).getAsJsonObject();
            document.addProperty("revision", document.get("revision").getAsLong() + 1L);
            document.getAsJsonObject("presentation").getAsJsonObject("labels").addProperty("format", format);
            String updated = DocumentParsers.GSON.toJson(document);
            RealDropSettingsDoc.parse(documentName, updated);
            preparation.document(documentFile.toPath(),
                (updated + System.lineSeparator()).getBytes(StandardCharsets.UTF_8));
            entries.add(Entry.of("config", LEGACY_CONFIG_FILE_NAME + ":drops.name-format", Status.OVERLAID));
        } catch (IOException | RuntimeException failure) {
            entries.add(new Entry("config", documentName, Status.ERROR, detail(failure)));
            Gloss.logExceptionStack(false, failure, "Unable to prepare imported drop settings.");
        }
    }

    private void writeDocument(Path file, Object document) throws IOException {
        preparation.document(file,
            (DocumentParsers.GSON.toJson(document) + System.lineSeparator()).getBytes(StandardCharsets.UTF_8));
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
                + counts[Status.SKIPPED_NOTE.ordinal()] + counts[Status.CONFLICT.ordinal()]
                + counts[Status.APPROXIMATED.ordinal()] + counts[Status.UNSUPPORTED.ordinal()];
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
