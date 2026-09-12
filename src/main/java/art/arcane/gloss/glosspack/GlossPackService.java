package art.arcane.gloss.glosspack;

import art.arcane.gloss.Gloss;
import art.arcane.gloss.GlossConfig;
import art.arcane.gloss.editor.sync.EditorSyncDocumentKind;
import art.arcane.gloss.history.HistoryKinds;
import art.arcane.gloss.history.HistoryService;
import art.arcane.gloss.service.GlossService;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import org.bukkit.Bukkit;
import org.bukkit.plugin.Plugin;

/**
 * The operator-facing side of {@code .glosspack} installs. It builds the environment a pack is
 * checked against, hands replaced copies to {@link HistoryService}, and reloads every kind a pack
 * touched once the transaction has committed.
 */
public final class GlossPackService implements GlossService {
    public static final String NAME = "glosspacks";

    private final Gloss plugin;
    private final GlossPackFetcher fetcher;

    public GlossPackService(Gloss plugin) {
        this.plugin = Objects.requireNonNull(plugin, "plugin");
        this.fetcher = new GlossPackFetcher();
    }

    @Override
    public String name() {
        return NAME;
    }

    @Override
    public void enable() {
    }

    @Override
    public void disable() {
    }

    public boolean enabled() {
        GlossConfig config = plugin.cfg();
        return config != null && config.modules().glosspacks().enabled();
    }

    public List<GlossPackLedger> installed() {
        return ledgers().all();
    }

    public GlossPackLedger ledger(String id) {
        return ledgers().read(id);
    }

    /** Reads an archive from a local file under the data folder or from an HTTPS URL. */
    public GlossPackArchive open(String source) throws IOException, InterruptedException {
        byte[] bytes = GlossPackFetcher.isUrl(source)
                ? fetcher.fetch(source)
                : Files.readAllBytes(localFile(source));
        return GlossPackArchive.read(bytes);
    }

    public List<GlossPackPreview.Outcome> preview(GlossPackArchive archive) {
        return installer().preview(archive);
    }

    public List<GlossPackPreview.Outcome> install(GlossPackArchive archive, String source)
            throws IOException {
        List<GlossPackPreview.Outcome> outcomes = plugin.getPersistenceCoordinator()
                .writeExternally(() -> installer().install(archive, source));
        publish(archive);
        return outcomes;
    }

    public List<GlossPackPreview.Outcome> update(String id)
            throws IOException, InterruptedException {
        GlossPackLedger ledger = ledgers().read(id);
        if (ledger == null) {
            throw new IllegalArgumentException("pack is not installed: " + id);
        }
        if (ledger.source().isBlank()) {
            throw new IllegalArgumentException("pack " + id + " has no recorded source to update from");
        }
        GlossPackArchive archive = open(ledger.source());
        if (!archive.manifest().id().equals(id)) {
            throw new IllegalArgumentException("pack source now carries a different pack: "
                    + archive.manifest().id());
        }
        List<GlossPackPreview.Outcome> outcomes = plugin.getPersistenceCoordinator()
                .writeExternally(() -> installer().update(archive, ledger.source()));
        publish(archive);
        return outcomes;
    }

    public List<GlossPackPreview.Outcome> remove(String id) throws IOException {
        GlossPackLedger ledger = ledgers().read(id);
        if (ledger == null) {
            throw new IllegalArgumentException("pack is not installed: " + id);
        }
        List<GlossPackPreview.Outcome> outcomes = plugin.getPersistenceCoordinator()
                .writeExternally(() -> installer().remove(id));
        publish(kindsOf(ledger.installedHashes().keySet()), imagesTouched(ledger.installedHashes().keySet()));
        return outcomes;
    }

    private void publish(GlossPackArchive archive) {
        Set<String> paths = new LinkedHashSet<>();
        for (GlossPackManifest.DocumentRef document : archive.manifest().documents()) {
            paths.add(document.kind() + "/" + document.id() + ".json");
        }
        publish(kindsOf(paths), !archive.manifest().images().isEmpty());
    }

    private void publish(Set<EditorSyncDocumentKind> kinds, boolean imagesChanged) {
        if (kinds.isEmpty() && !imagesChanged) {
            return;
        }
        plugin.publishEditorSyncRuntime(kinds, imagesChanged);
    }

    private Set<EditorSyncDocumentKind> kindsOf(Set<String> relativePaths) {
        Set<EditorSyncDocumentKind> kinds = new LinkedHashSet<>();
        for (String path : relativePaths) {
            HistoryKinds.DocumentPath document =
                    HistoryKinds.resolve(plugin.getDataFolder().toPath(), path);
            if (document != null) {
                kinds.add(document.kind());
            }
        }
        return Set.copyOf(kinds);
    }

    private static boolean imagesTouched(Set<String> relativePaths) {
        return relativePaths.stream().anyMatch(path -> path.startsWith("images/"));
    }

    private GlossPackInstaller installer() {
        Path data = plugin.getDataFolder().toPath();
        return new GlossPackInstaller(data, plugin.getProjectTransaction(), environment(),
                this::recordHistory);
    }

    private void recordHistory(String kind, String id, byte[] content, String source) {
        HistoryService history = plugin.service(HistoryService.class);
        if (history != null) {
            history.record(kind, id, content, source);
        }
    }

    private GlossPackLedgers ledgers() {
        return new GlossPackLedgers(plugin.getDataFolder().toPath());
    }

    private GlossPackEnvironment environment() {
        Set<String> plugins = new LinkedHashSet<>();
        for (Plugin installed : Bukkit.getPluginManager().getPlugins()) {
            if (installed.isEnabled()) {
                plugins.add(installed.getName());
            }
        }
        GlossConfig config = plugin.cfg();
        boolean allowServerCommands = config != null
                && config.modules().glosspacks().allowServerCommands();
        return new GlossPackEnvironment(plugin.getDescription().getVersion(), plugins,
                allowServerCommands);
    }

    private Path localFile(String source) {
        Path data = plugin.getDataFolder().toPath().toAbsolutePath().normalize();
        Path candidate = Path.of(source);
        Path target = (candidate.isAbsolute() ? candidate : data.resolve(candidate)).normalize();
        if (!target.startsWith(data)) {
            throw new IllegalArgumentException("pack files must live under the Gloss data folder");
        }
        if (!Files.isRegularFile(target, LinkOption.NOFOLLOW_LINKS)) {
            throw new IllegalArgumentException("pack file does not exist: " + source);
        }
        return target;
    }
}
