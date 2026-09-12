package art.arcane.gloss.glosspack;

import art.arcane.gloss.doc.DocumentEnvelope;
import art.arcane.gloss.doc.DocumentHashes;
import art.arcane.gloss.editor.sync.EditorSyncDocumentKind;
import art.arcane.gloss.history.HistoryKinds;
import art.arcane.gloss.persistence.GlossProjectTransaction;
import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/**
 * Installs, updates and removes packs against a data folder.
 *
 * <p>Nothing is written until the whole plan is known: a pack whose ids collide with documents it
 * does not own refuses outright and names the collisions. An update replaces only the files still
 * carrying the hash the pack wrote; a file the operator edited keeps its content and gets the new
 * version beside it as {@code <id>.<pack>.pack-new.json}, which nothing hot-loads. Removal is the
 * same rule, so an operator edit is never deleted by uninstalling.
 */
public final class GlossPackInstaller {
    static final String PACK_NEW_SUFFIX = ".pack-new.json";
    private static final String IMAGES = "images";
    private static final Gson GSON = new GsonBuilder()
            .serializeNulls()
            .disableHtmlEscaping()
            .setPrettyPrinting()
            .create();

    private final Path dataDirectory;
    private final GlossProjectTransaction transaction;
    private final GlossPackEnvironment environment;
    private final GlossPackLedgers ledgers;
    private final HistoryRecorder history;

    public GlossPackInstaller(Path dataDirectory, GlossProjectTransaction transaction,
                              GlossPackEnvironment environment, HistoryRecorder history) {
        this.dataDirectory = Objects.requireNonNull(dataDirectory, "dataDirectory")
                .toAbsolutePath().normalize();
        this.transaction = Objects.requireNonNull(transaction, "transaction");
        this.environment = Objects.requireNonNull(environment, "environment");
        this.ledgers = new GlossPackLedgers(this.dataDirectory);
        this.history = Objects.requireNonNull(history, "history");
    }

    public GlossPackLedgers ledgers() {
        return ledgers;
    }

    /** What installing or updating would do, without touching anything. */
    public List<GlossPackPreview.Outcome> preview(GlossPackArchive archive) {
        return plan(archive).outcomes();
    }

    public List<GlossPackPreview.Outcome> install(GlossPackArchive archive, String source)
            throws IOException {
        if (ledgers.read(archive.manifest().id()) != null) {
            throw new IllegalStateException("pack is already installed: " + archive.manifest().id()
                    + "; update it instead");
        }
        return write(archive, source);
    }

    public List<GlossPackPreview.Outcome> update(GlossPackArchive archive, String source)
            throws IOException {
        if (ledgers.read(archive.manifest().id()) == null) {
            throw new IllegalArgumentException("pack is not installed: " + archive.manifest().id());
        }
        return write(archive, source);
    }

    public List<GlossPackPreview.Outcome> remove(String id) throws IOException {
        GlossPackLedger ledger = ledgers.read(id);
        if (ledger == null) {
            throw new IllegalArgumentException("pack is not installed: " + id);
        }
        List<GlossPackPreview.Outcome> outcomes = new ArrayList<>();
        Map<Path, GlossProjectTransaction.Mutation> mutations = new LinkedHashMap<>();
        Map<Path, byte[]> expected = new LinkedHashMap<>();
        for (Map.Entry<String, String> owned : ledger.installedHashes().entrySet()) {
            Path target = dataDirectory.resolve(owned.getKey()).normalize();
            byte[] current = currentBytes(target);
            if (current == null) {
                outcomes.add(new GlossPackPreview.Outcome(owned.getKey(),
                        GlossPackPreview.Disposition.SKIP_REQUIREMENT, "already gone"));
                continue;
            }
            if (!DocumentHashes.sha256(current).equals(owned.getValue())) {
                outcomes.add(new GlossPackPreview.Outcome(owned.getKey(),
                        GlossPackPreview.Disposition.KEEP_MODIFIED, "edited since install"));
                continue;
            }
            mutations.put(target, GlossProjectTransaction.Mutation.delete());
            expected.put(target, current);
            outcomes.add(new GlossPackPreview.Outcome(owned.getKey(),
                    GlossPackPreview.Disposition.UPDATE, "removed"));
        }
        if (!mutations.isEmpty()) {
            commit("pack-remove-" + id, mutations, expected);
        }
        Files.deleteIfExists(ledgers.file(id));
        return List.copyOf(outcomes);
    }

    private List<GlossPackPreview.Outcome> write(GlossPackArchive archive, String source)
            throws IOException {
        Plan plan = plan(archive);
        List<String> conflicts = plan.outcomes().stream()
                .filter(outcome -> outcome.disposition() == GlossPackPreview.Disposition.CONFLICT)
                .map(GlossPackPreview.Outcome::path)
                .toList();
        if (!conflicts.isEmpty()) {
            throw new IllegalStateException("pack ids already belong to other documents: "
                    + String.join(", ", conflicts));
        }
        if (!plan.blockers().isEmpty()) {
            throw new IllegalStateException("pack requirements are not met: "
                    + String.join("; ", plan.blockers()));
        }
        Map<Path, GlossProjectTransaction.Mutation> mutations = new LinkedHashMap<>();
        Map<Path, byte[]> expected = new LinkedHashMap<>();
        for (Map.Entry<String, byte[]> write : plan.writes().entrySet()) {
            Path target = dataDirectory.resolve(write.getKey()).normalize();
            byte[] current = currentBytes(target);
            mutations.put(target, GlossProjectTransaction.Mutation.write(write.getValue()));
            if (current != null) {
                expected.put(target, current);
            }
        }
        for (String removal : plan.removals()) {
            Path target = dataDirectory.resolve(removal).normalize();
            byte[] current = currentBytes(target);
            if (current == null) {
                continue;
            }
            mutations.put(target, GlossProjectTransaction.Mutation.delete());
            expected.put(target, current);
        }
        if (!mutations.isEmpty()) {
            commit("pack-" + archive.manifest().id(), mutations, expected);
        }
        recordHistory(archive.manifest().id(), plan);
        ledgers.write(new GlossPackLedger(archive.manifest(), source,
                System.currentTimeMillis(), plan.installedHashes()));
        return plan.outcomes();
    }

    private void commit(String label, Map<Path, GlossProjectTransaction.Mutation> mutations,
                        Map<Path, byte[]> expected) throws IOException {
        GlossProjectTransaction.Pending pending = transaction.apply(label, mutations, expected);
        transaction.commit(pending);
    }

    private void recordHistory(String id, Plan plan) {
        for (Map.Entry<String, byte[]> replaced : plan.replaced().entrySet()) {
            HistoryKinds.DocumentPath document =
                    HistoryKinds.resolve(dataDirectory, replaced.getKey());
            if (document == null) {
                continue;
            }
            history.record(document.collection(), document.id(), replaced.getValue(), "pack:" + id);
        }
    }

    private Plan plan(GlossPackArchive archive) {
        GlossPackManifest manifest = archive.manifest();
        GlossPackLedger installed = ledgers.read(manifest.id());
        List<GlossPackPreview.Outcome> outcomes = new ArrayList<>();
        Map<String, byte[]> writes = new LinkedHashMap<>();
        Map<String, byte[]> replaced = new LinkedHashMap<>();
        Map<String, String> installedHashes = new LinkedHashMap<>();
        List<String> blockers = new ArrayList<>(manifest.unmetRequirements(environment));
        for (String blocker : blockers) {
            outcomes.add(new GlossPackPreview.Outcome(GlossPackManifest.FILE_NAME,
                    GlossPackPreview.Disposition.SKIP_REQUIREMENT, blocker));
        }
        if (!blockers.isEmpty()) {
            return new Plan(List.copyOf(outcomes), Map.of(), Map.of(), Map.of(), Set.of(),
                    List.copyOf(blockers));
        }
        Set<String> owned = new LinkedHashSet<>();
        for (GlossPackManifest.DocumentRef document : manifest.documents()) {
            planDocument(archive, manifest, installed, document, outcomes, writes, replaced,
                    installedHashes, owned);
        }
        for (GlossPackManifest.ImageRef image : manifest.images()) {
            planImage(archive, manifest, installed, image, outcomes, writes, replaced,
                    installedHashes, owned);
        }
        Set<String> removals = new LinkedHashSet<>();
        if (installed != null) {
            for (Map.Entry<String, String> previous : installed.installedHashes().entrySet()) {
                if (owned.contains(previous.getKey())) {
                    continue;
                }
                Path target = dataDirectory.resolve(previous.getKey()).normalize();
                byte[] current = currentBytes(target);
                if (current != null && !DocumentHashes.sha256(current).equals(previous.getValue())) {
                    outcomes.add(new GlossPackPreview.Outcome(previous.getKey(),
                            GlossPackPreview.Disposition.KEEP_MODIFIED,
                            "dropped by the new version but edited here"));
                    installedHashes.put(previous.getKey(), previous.getValue());
                    continue;
                }
                removals.add(previous.getKey());
                outcomes.add(new GlossPackPreview.Outcome(previous.getKey(),
                        GlossPackPreview.Disposition.UPDATE, "dropped by the new version"));
            }
        }
        return new Plan(List.copyOf(outcomes), Map.copyOf(writes), Map.copyOf(replaced),
                Map.copyOf(installedHashes), Set.copyOf(removals), List.of());
    }

    private void planDocument(GlossPackArchive archive, GlossPackManifest manifest,
                              GlossPackLedger installed, GlossPackManifest.DocumentRef document,
                              List<GlossPackPreview.Outcome> outcomes, Map<String, byte[]> writes,
                              Map<String, byte[]> replaced, Map<String, String> installedHashes,
                              Set<String> owned) {
        EditorSyncDocumentKind kind = HistoryKinds.byCollection(document.kind());
        if (kind == null) {
            outcomes.add(new GlossPackPreview.Outcome(document.kind() + "/" + document.id() + ".json",
                    GlossPackPreview.Disposition.SKIP_REQUIREMENT,
                    "this server has no " + document.kind() + " documents"));
            return;
        }
        Path target;
        try {
            target = kind.path(dataDirectory, kind.layout() == EditorSyncDocumentKind.Layout.SINGLE
                    ? kind.singletonId() : document.id());
        } catch (RuntimeException invalid) {
            outcomes.add(new GlossPackPreview.Outcome(document.kind() + "/" + document.id() + ".json",
                    GlossPackPreview.Disposition.SKIP_REQUIREMENT, invalid.getMessage()));
            return;
        }
        String relative = relative(target);
        byte[] content = archive.entry(document.archivePath());
        String source = new String(content, StandardCharsets.UTF_8);
        Stripped stripped = stripServerCommands(source, manifest.allowsServerCommands(environment));
        if (stripped.removed() > 0) {
            outcomes.add(new GlossPackPreview.Outcome(relative,
                    GlossPackPreview.Disposition.STRIP_SERVER_COMMAND,
                    stripped.removed() + " server command action(s) removed"));
        }
        byte[] finalContent = stripped.source().getBytes(StandardCharsets.UTF_8);
        try {
            kind.parse(kind.layout() == EditorSyncDocumentKind.Layout.SINGLE
                    ? kind.singletonId() : document.id(), stripped.source());
        } catch (RuntimeException failure) {
            outcomes.add(new GlossPackPreview.Outcome(relative,
                    GlossPackPreview.Disposition.SKIP_REQUIREMENT,
                    DocumentEnvelope.isUnsupportedSchemaVersion(failure)
                            ? "declares a schemaVersion this server does not read"
                            : "does not parse: " + failure.getMessage()));
            return;
        }
        place(relative, finalContent, installed, outcomes, writes, replaced, installedHashes, owned,
                manifest.id());
    }

    private void planImage(GlossPackArchive archive, GlossPackManifest manifest,
                           GlossPackLedger installed, GlossPackManifest.ImageRef image,
                           List<GlossPackPreview.Outcome> outcomes, Map<String, byte[]> writes,
                           Map<String, byte[]> replaced, Map<String, String> installedHashes,
                           Set<String> owned) {
        Path target = dataDirectory.resolve(IMAGES).resolve(image.path()).normalize();
        if (!target.startsWith(dataDirectory.resolve(IMAGES))) {
            outcomes.add(new GlossPackPreview.Outcome(IMAGES + "/" + image.path(),
                    GlossPackPreview.Disposition.SKIP_REQUIREMENT, "escapes the images folder"));
            return;
        }
        place(relative(target), archive.entry(image.archivePath()), installed, outcomes, writes,
                replaced, installedHashes, owned, manifest.id());
    }

    private void place(String relative, byte[] content, GlossPackLedger installed,
                       List<GlossPackPreview.Outcome> outcomes, Map<String, byte[]> writes,
                       Map<String, byte[]> replaced, Map<String, String> installedHashes,
                       Set<String> owned, String packId) {
        owned.add(relative);
        Path target = dataDirectory.resolve(relative).normalize();
        byte[] current = currentBytes(target);
        if (current == null) {
            writes.put(relative, content);
            installedHashes.put(relative, DocumentHashes.sha256(content));
            outcomes.add(new GlossPackPreview.Outcome(relative,
                    GlossPackPreview.Disposition.CREATE, ""));
            return;
        }
        String currentHash = DocumentHashes.sha256(current);
        String installedHash = installed == null ? null : installed.installedHashes().get(relative);
        if (installedHash == null) {
            outcomes.add(new GlossPackPreview.Outcome(relative,
                    GlossPackPreview.Disposition.CONFLICT,
                    "already exists and is not owned by " + packId));
            return;
        }
        if (installedHash.equals(currentHash)) {
            writes.put(relative, content);
            replaced.put(relative, current);
            installedHashes.put(relative, DocumentHashes.sha256(content));
            outcomes.add(new GlossPackPreview.Outcome(relative,
                    GlossPackPreview.Disposition.UPDATE, ""));
            return;
        }
        String sidecar = sidecarPath(relative, packId);
        writes.put(sidecar, content);
        installedHashes.put(relative, installedHash);
        // The sidecar is the pack's file too: owned so a later update rewrites rather than drops
        // it, and in the ledger so removing the pack takes it under the same unmodified-only rule.
        owned.add(sidecar);
        installedHashes.put(sidecar, DocumentHashes.sha256(content));
        outcomes.add(new GlossPackPreview.Outcome(relative,
                GlossPackPreview.Disposition.KEEP_MODIFIED, "new version written to " + sidecar));
    }

    static String sidecarPath(String relative, String packId) {
        int extension = relative.lastIndexOf(".json");
        return extension < 0
                ? relative + "." + packId + PACK_NEW_SUFFIX
                : relative.substring(0, extension) + "." + packId + PACK_NEW_SUFFIX;
    }

    private String relative(Path target) {
        return dataDirectory.relativize(target).toString().replace(java.io.File.separatorChar, '/');
    }

    private byte[] currentBytes(Path target) {
        if (!Files.isRegularFile(target, LinkOption.NOFOLLOW_LINKS)) {
            return null;
        }
        try {
            return Files.readAllBytes(target);
        } catch (IOException failure) {
            throw new IllegalStateException("cannot read " + target, failure);
        }
    }

    /** Removes {@code command} actions that run as the server, reporting how many went. */
    static Stripped stripServerCommands(String source, boolean allowed) {
        if (allowed) {
            return new Stripped(source, 0);
        }
        JsonElement parsed;
        try {
            parsed = JsonParser.parseString(source);
        } catch (RuntimeException notJson) {
            return new Stripped(source, 0);
        }
        int removed = strip(parsed);
        return removed == 0
                ? new Stripped(source, 0)
                : new Stripped(GSON.toJson(parsed) + System.lineSeparator(), removed);
    }

    private static int strip(JsonElement element) {
        int removed = 0;
        if (element.isJsonArray()) {
            JsonArray array = element.getAsJsonArray();
            for (int index = array.size() - 1; index >= 0; index--) {
                JsonElement child = array.get(index);
                if (isServerCommand(child)) {
                    array.remove(index);
                    removed++;
                    continue;
                }
                removed += strip(child);
            }
            return removed;
        }
        if (element.isJsonObject()) {
            for (JsonElement child : element.getAsJsonObject().asMap().values()) {
                removed += strip(child);
            }
        }
        return removed;
    }

    private static boolean isServerCommand(JsonElement element) {
        if (!element.isJsonObject()) {
            return false;
        }
        JsonObject object = element.getAsJsonObject();
        JsonElement type = object.get("type");
        JsonElement source = object.get("source");
        return type != null && type.isJsonPrimitive() && "command".equals(type.getAsString())
                && source != null && source.isJsonPrimitive() && "server".equals(source.getAsString());
    }

    record Stripped(String source, int removed) {
    }

    private record Plan(List<GlossPackPreview.Outcome> outcomes, Map<String, byte[]> writes,
                        Map<String, byte[]> replaced, Map<String, String> installedHashes,
                        Set<String> removals, List<String> blockers) {
    }

    /** Where a pack hands the copy it is about to replace, so the history keeps it. */
    @FunctionalInterface
    public interface HistoryRecorder {
        void record(String kind, String id, byte[] content, String source);
    }
}
