package art.arcane.gloss.history;

import art.arcane.gloss.Gloss;
import art.arcane.gloss.GlossConfig;
import art.arcane.gloss.doc.DocumentHashes;
import art.arcane.gloss.doc.DocumentRegistry;
import art.arcane.gloss.doc.GlossDocument;
import art.arcane.gloss.doc.RegistryOwner;
import art.arcane.gloss.editor.sync.EditorSyncDocumentKind;
import art.arcane.gloss.persistence.GlossPersistenceCoordinator;
import art.arcane.gloss.service.GlossService;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Keeps previous copies of every document the server can parse.
 *
 * <p>The copies come from three places: a {@link art.arcane.gloss.doc.DataWatchdog} entry that
 * notices raw text changing under a registry, the editor publish path and the pack installer, which
 * both call {@link #record} with their own source. The editor-sync backups are read as history too,
 * so one timeline covers hand edits, editor publications and pack installs.
 */
public final class HistoryService implements GlossService {
    public static final String NAME = "history";
    private static final String WATCHDOG_ENTRY = "history";
    private static final int MAX_RECORDS_PER_PASS = 32;

    private final Gloss plugin;
    private final HistoryStore store;
    /**
     * Concurrent because the watchdog IO thread's pass and the pack, import and restore paths -
     * which run on a command thread - both write it.
     */
    private final Map<String, String> lastSeenHashes;
    private boolean baselineTaken;
    private boolean registered;

    public HistoryService(Gloss plugin) {
        this.plugin = Objects.requireNonNull(plugin, "plugin");
        this.store = new HistoryStore(plugin.getDataFolder().toPath());
        this.lastSeenHashes = new ConcurrentHashMap<>();
    }

    @Override
    public String name() {
        return NAME;
    }

    @Override
    public void enable() {
        if (!settings().enabled() || registered) {
            return;
        }
        plugin.watchdog().register(WATCHDOG_ENTRY, this::pass);
        registered = true;
    }

    @Override
    public void disable() {
        if (!registered) {
            return;
        }
        plugin.watchdog().unregister(WATCHDOG_ENTRY);
        registered = false;
        lastSeenHashes.clear();
        baselineTaken = false;
    }

    @Override
    public void reload() {
        if (settings().enabled()) {
            enable();
        } else {
            disable();
        }
    }

    @Override
    public boolean reloadOnConfigChange(GlossConfig previous, GlossConfig next) {
        return !previous.modules().history().equals(next.modules().history());
    }

    public HistoryStore store() {
        return store;
    }

    /** Stores one copy of {@code content} for a document, attributing it to {@code source}. */
    public HistoryEntry record(String kind, String id, byte[] content, String source) {
        if (!settings().enabled()) {
            return null;
        }
        try {
            HistoryEntry entry = store.record(kind, id, content, source);
            lastSeenHashes.put(key(kind, id), DocumentHashes.sha256(
                    new String(content, StandardCharsets.UTF_8)));
            return entry;
        } catch (IOException failure) {
            Gloss.logExceptionStack(false, failure, "History could not record %s %s.", kind, id);
            return null;
        }
    }

    /** Every stored copy of one document, newest first, including the editor-sync backups. */
    public List<HistoryEntry> versions(String kind, String id) {
        List<HistoryEntry> versions = new ArrayList<>(store.list(kind, id));
        versions.addAll(transactionVersions(kind, id));
        versions.sort(Comparator.comparingLong(HistoryEntry::epochMillis).reversed()
                .thenComparing(HistoryEntry::source));
        return List.copyOf(versions);
    }

    public HistoryEntry version(String kind, String id, long epochMillis) {
        for (HistoryEntry entry : versions(kind, id)) {
            if (entry.epochMillis() == epochMillis) {
                return entry;
            }
        }
        return null;
    }

    /** Writes {@code entry} back over the live document after recording the copy it replaces. */
    public void restore(String kind, String id, HistoryEntry entry) throws IOException {
        Path data = plugin.getDataFolder().toPath();
        HistoryRestore restore = new HistoryRestore(data, plugin.getProjectTransaction(), store);
        GlossPersistenceCoordinator.ExternalTransaction lease;
        try {
            lease = plugin.getPersistenceCoordinator().beginExternalTransaction();
        } catch (InterruptedException interruption) {
            Thread.currentThread().interrupt();
            throw new IOException("restore was interrupted", interruption);
        }
        EditorSyncDocumentKind documentKind;
        try {
            documentKind = restore.apply(kind, id, entry);
        } finally {
            lease.close();
        }
        lastSeenHashes.remove(key(kind, id));
        plugin.publishEditorSyncRuntime(Set.of(documentKind), false);
    }

    void pass() {
        GlossConfig.History settings = settings();
        if (!settings.enabled()) {
            return;
        }
        int recorded = 0;
        for (RegistryOwner owner : owners()) {
            for (Map.Entry<String, DocumentRegistry<?>> registry : owner.registries().entrySet()) {
                recorded += recordChanged(registry.getKey(), registry.getValue(),
                        MAX_RECORDS_PER_PASS - recorded);
                if (recorded >= MAX_RECORDS_PER_PASS) {
                    baselineTaken = true;
                    return;
                }
            }
        }
        baselineTaken = true;
        if (recorded > 0) {
            prune(settings);
        }
    }

    private int recordChanged(String collection, DocumentRegistry<?> registry, int budget) {
        if (budget <= 0) {
            return 0;
        }
        int recorded = 0;
        for (GlossDocument<?> document : registry.snapshot().values()) {
            String key = key(collection, document.id());
            String previous = lastSeenHashes.put(key, document.contentHash());
            if (Objects.equals(previous, document.contentHash())) {
                continue;
            }
            if (!baselineTaken || previous == null) {
                continue;
            }
            try {
                store.record(collection, document.id(),
                        document.raw().getBytes(StandardCharsets.UTF_8), "watchdog");
                recorded++;
            } catch (IOException | RuntimeException failure) {
                Gloss.logExceptionStack(false, failure, "History could not record %s %s.",
                        collection, document.id());
            }
            if (recorded >= budget) {
                return recorded;
            }
        }
        return recorded;
    }

    private void prune(GlossConfig.History settings) {
        try {
            store.prune(settings.maxVersions(), settings.maxAgeDays());
        } catch (IOException failure) {
            Gloss.logExceptionStack(false, failure, "History could not prune stored versions.");
        }
    }

    private List<HistoryEntry> transactionVersions(String kind, String id) {
        List<HistoryEntry> entries = new ArrayList<>();
        boolean read = plugin.getPersistenceCoordinator().tryRead(() -> {
            try {
                entries.addAll(TransactionRecords.of(
                        plugin.getProjectTransaction().committedTransactions(), kind, id));
            } catch (IOException failure) {
                Gloss.logExceptionStack(false, failure,
                        "History could not read the editor sync backups.");
            }
        });
        return read ? List.copyOf(entries) : List.of();
    }

    private List<RegistryOwner> owners() {
        List<RegistryOwner> owners = new ArrayList<>();
        addOwner(owners, plugin.animations());
        addOwner(owners, plugin.boards());
        addOwner(owners, plugin.bubbles());
        addOwner(owners, plugin.drops());
        addOwner(owners, plugin.emoji());
        addOwner(owners, plugin.entityOverlays());
        addOwner(owners, plugin.holograms());
        addOwner(owners, plugin.indicators());
        addOwner(owners, plugin.motd());
        addOwner(owners, plugin.tablist());
        addOwner(owners, plugin.getMenuCatalog());
        addOwner(owners, plugin.getPreviewRegistry());
        for (GlossService service : plugin.laneServices()) {
            addOwner(owners, service);
        }
        return owners;
    }

    private static void addOwner(List<RegistryOwner> owners, Object candidate) {
        if (candidate instanceof RegistryOwner owner) {
            owners.add(owner);
        }
    }

    private GlossConfig.History settings() {
        GlossConfig config = plugin.cfg();
        return config == null ? new GlossConfig.History(false, 20, 30) : config.modules().history();
    }

    private static String key(String kind, String id) {
        return kind + "/" + id;
    }
}
