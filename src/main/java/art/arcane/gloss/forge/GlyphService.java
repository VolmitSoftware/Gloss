package art.arcane.gloss.forge;

import art.arcane.gloss.Gloss;
import art.arcane.gloss.GlossConfig;
import art.arcane.gloss.doc.DocumentDelta;
import art.arcane.gloss.doc.DocumentHashes;
import art.arcane.gloss.doc.DocumentRegistry;
import art.arcane.gloss.doc.GlossDocument;
import art.arcane.gloss.doc.ShippedDefaults;
import art.arcane.gloss.doc.ShippedDocumentCatalog;
import art.arcane.gloss.emoji.GlyphSubstitution;
import art.arcane.gloss.expr.ExprFunctionRegistry;
import art.arcane.gloss.expr.ExprVariableNamespaces;
import art.arcane.gloss.image.ImageAssets;
import art.arcane.gloss.service.GlossService;
import art.arcane.volmlib.util.scheduling.FoliaScheduler;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.TreeMap;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.RejectedExecutionException;
import java.util.function.LongSupplier;
import java.util.stream.Stream;
import java.util.logging.Level;

/**
 * Owns {@code glyphs/}, the codepoint ledger, the pack build and everything the layout functions
 * read. Builds run on the watchdog IO thread and are debounced, because an author saving a folder
 * of images produces a burst of changes and a pack build reads and hashes every referenced file.
 */
public final class GlyphService implements GlossService {
    public static final String NAME = "forge";
    public static final long DEBOUNCE_SECONDS = 5L;
    public static final String OUT_DIRECTORY = "forge/out";
    public static final String LEDGER_FILE = "forge/ledger.json";

    private final Gloss plugin;
    private final File folder;
    private final Path imagesRoot;
    private final Path outDirectory;
    private final Path ledgerFile;
    private final ShippedDefaults defaults;
    private final DocumentRegistry<GlyphDoc> documents;
    private final FontMetrics metrics;
    private final PackNamespace packNamespace;
    private final PixelFunctions pixelFunctions;
    private final LayoutFunctions layoutFunctions;
    private final GlyphAtlas atlas;
    private final PackFormats packFormats;
    private final PackBuilder builder;
    private final PackDelivery delivery;
    private final PackArtifactStore artifacts;
    private final RebuildGate gate;

    private volatile GlyphRegistry registry = GlyphRegistry.EMPTY;
    private volatile PackArtifact artifact;
    private volatile boolean enabled;
    private long lifecycle;
    private CompletableFuture<Boolean> building;
    private ThreadPoolExecutor worker;
    private int activePackFormat;

    public GlyphService(Gloss plugin) {
        this.plugin = plugin;
        this.folder = new File(plugin.getDataFolder(), GlyphDoc.KIND);
        this.imagesRoot = new File(plugin.getDataFolder(), ImageAssets.KIND).toPath();
        this.outDirectory = new File(plugin.getDataFolder(), OUT_DIRECTORY).toPath();
        this.ledgerFile = new File(plugin.getDataFolder(), LEDGER_FILE).toPath();
        this.defaults = new ShippedDefaults(GlyphDoc.KIND, folder, ShippedDocumentCatalog.GLYPHS.names());
        this.documents = DocumentRegistry.folder(GlyphDoc.KIND, folder, GlyphDoc::parse, GlyphDoc::revision);
        this.metrics = FontMetrics.load();
        this.packNamespace = new PackNamespace();
        this.layoutFunctions = new LayoutFunctions(() -> registry, metrics);
        this.atlas = new GlyphAtlas(() -> registry, layoutFunctions);
        this.pixelFunctions = new PixelFunctions(metrics, () -> layoutFunctions);
        this.packFormats = PackFormats.load();
        this.artifacts = new PackArtifactStore();
        this.builder = new PackBuilder(new PackBuilder.Options(imagesRoot, () -> plugin.cfg().images(),
            () -> plugin.cfg().modules().forge().limits(), artifacts));
        this.delivery = new PackDelivery(new PackDelivery.Options(plugin, packNamespace, artifacts));
        this.gate = new RebuildGate(System::nanoTime, TimeUnit.SECONDS.toNanos(DEBOUNCE_SECONDS));
    }

    /**
     * Debounces rebuilds and refuses to repeat one whose inputs have not moved. An edit reverted
     * inside the window cancels the pending build instead of producing an identical pack.
     */
    public static final class RebuildGate {
        private final LongSupplier clock;
        private long debounceNanos;
        private String built = "";
        private String pending;
        private long dueAt;

        public RebuildGate(LongSupplier clock, long debounceNanos) {
            this.clock = clock;
            this.debounceNanos = debounceNanos;
        }

        /** @return true when this observation left a rebuild pending */
        public synchronized boolean observe(String fingerprint) {
            String value = fingerprint == null ? "" : fingerprint;
            if (value.equals(built)) {
                pending = null;
                return false;
            }
            if (value.equals(pending)) {
                return true;
            }
            pending = value;
            dueAt = clock.getAsLong() + debounceNanos;
            return true;
        }

        /** @return true once the window has elapsed; consumes the pending build */
        public synchronized boolean due() {
            if (pending == null || clock.getAsLong() < dueAt) {
                return false;
            }
            built = pending;
            pending = null;
            return true;
        }

        public synchronized void configure(long nanos) {
            debounceNanos = Math.max(0L, nanos);
        }

        public synchronized void completed(String fingerprint) {
            built = fingerprint;
            if (built.equals(pending)) {
                pending = null;
            }
        }

        public synchronized String built() {
            return built;
        }

        /** Forgets what was built so the next identical observation retries. */
        public synchronized void failed() {
            built = "";
        }
    }

    @Override
    public String name() {
        return NAME;
    }

    @Override
    public void contribute() {
        pixelFunctions.register(ExprFunctionRegistry.global());
        layoutFunctions.register(ExprFunctionRegistry.global());
        ExprVariableNamespaces.global().register(packNamespace);
        packNamespace.install();
    }

    @Override
    public synchronized void enable() {
        if (!plugin.cfg().modules().forge().enabled()) {
            return;
        }
        enabled = true;
        atlas.install();
        lifecycle++;
        gate.configure(TimeUnit.MILLISECONDS.toNanos(plugin.cfg().modules().forge().buildDebounceTicks() * 50L));
        activePackFormat = packFormats.forServer(Bukkit.getBukkitVersion(), plugin.cfg().modules().forge().packFormat());
        defaults.extractMissing();
        build();
        plugin.watchdog().register(GlyphDoc.KIND, this::poll);
        delivery.enable(deliverySettings(), artifact);
    }

    @Override
    public synchronized void disable() {
        enabled = false;
        atlas.uninstall();
        lifecycle++;
        plugin.watchdog().unregister(GlyphDoc.KIND);
        delivery.disable();
        documents.close();
        plugin.emoji().setGlyphSubstitution(null);
        metrics.replaceDeclared(Map.of());
        registry = GlyphRegistry.EMPTY;
        artifact = null;
        packNamespace.publish("");
    }

    @Override
    public synchronized void reload() {
        if (!plugin.cfg().modules().forge().enabled()) {
            disable();
            return;
        }
        if (!enabled) {
            enable();
            return;
        }
        long generation = ++lifecycle;
        gate.configure(TimeUnit.MILLISECONDS.toNanos(plugin.cfg().modules().forge().buildDebounceTicks() * 50L));
        activePackFormat = packFormats.forServer(Bukkit.getBukkitVersion(), plugin.cfg().modules().forge().packFormat());
        delivery.reconfigure(deliverySettings());
        if (building != null && !building.isDone()) {
            building.whenComplete((ignored, failure) -> rebuildAfterReload(generation));
        } else {
            build();
        }
    }

    private synchronized void rebuildAfterReload(long generation) {
        if (enabled && lifecycle == generation) {
            build();
        }
    }

    @Override
    public boolean reloadOnConfigChange(GlossConfig previous, GlossConfig next) {
        return !previous.modules().forge().equals(next.modules().forge())
            || !previous.images().equals(next.images());
    }

    public GlyphRegistry glyphs() {
        return registry;
    }

    public FontMetrics metrics() {
        return metrics;
    }

    public PackNamespace pack() {
        return packNamespace;
    }

    public PackDelivery delivery() {
        return delivery;
    }

    public PackFormats formats() {
        return packFormats;
    }

    public Optional<PackArtifact> artifact() {
        return Optional.ofNullable(artifact);
    }

    public List<String> documentIds() {
        return List.copyOf(new TreeMap<>(documents.snapshot()).keySet());
    }

    public List<String> resetToDefault(String nameOrStar) {
        return defaults.resetToDefault(nameOrStar);
    }

    public synchronized CompletableFuture<Boolean> build() {
        if (!enabled) {
            return CompletableFuture.completedFuture(false);
        }
        if (building != null && !building.isDone()) {
            return building;
        }
        CompletableFuture<Boolean> result = new CompletableFuture<>();
        building = result;
        long generation = lifecycle;
        GlossConfig.Forge config = plugin.cfg().modules().forge();
        int format = activePackFormat;
        try {
            worker().execute(() -> rebuild(generation, config, format, result));
        } catch (RejectedExecutionException failure) {
            gate.failed();
            result.complete(false);
            Gloss.logExceptionStack(false, failure, "forge: build queue is full; retry later.");
        }
        return result;
    }

    public synchronized CompletableFuture<Integer> exportAsync(Path target) {
        CompletableFuture<Integer> result = new CompletableFuture<>();
        if (!enabled) {
            return CompletableFuture.failedFuture(new IOException("Forge is disabled"));
        }
        long generation = lifecycle;
        try {
            worker().execute(() -> {
                try {
                    synchronized (this) {
                        if (!enabled || lifecycle != generation) {
                            throw new IOException("Forge lifecycle changed before export");
                        }
                    }
                    result.complete(export(target));
                } catch (IOException | RuntimeException failure) {
                    Gloss.logExceptionStack(false, failure, "forge: pack export failed.");
                    result.completeExceptionally(failure);
                }
            });
        } catch (RejectedExecutionException failure) {
            result.completeExceptionally(failure);
        }
        return result;
    }

    private ThreadPoolExecutor worker() {
        if (worker == null) {
            worker = new ThreadPoolExecutor(1, 1, 30, TimeUnit.SECONDS, new ArrayBlockingQueue<>(plugin.cfg().modules().forge().buildQueueCapacity()), work -> {
                Thread thread = new Thread(work, "Gloss pack preparation");
                thread.setDaemon(true);
                return thread;
            });
            worker.allowCoreThreadTimeOut(true);
        }
        return worker;
    }

    /** Copies the mergeable pack directory somewhere an operator names. */
    public int export(Path target) throws IOException {
        PackArtifact current = artifact;
        if (current == null) {
            return 0;
        }
        Path source = current.directory();
        List<Path> files;
        try (Stream<Path> walk = Files.walk(source)) {
            files = walk.filter(Files::isRegularFile).sorted().toList();
        }
        for (Path file : files) {
            Path destination = target.resolve(source.relativize(file).toString());
            Files.createDirectories(destination.getParent());
            Files.copy(file, destination, StandardCopyOption.REPLACE_EXISTING);
        }
        return files.size();
    }

    private PackDelivery.Settings deliverySettings() {
        GlossConfig.Forge forge = plugin.cfg().modules().forge();
        return new PackDelivery.Settings(forge.url(), forge.serve(), forge.serveBind(), forge.servePort(),
            forge.prompt(), forge.required(), forge.listenerThreads(), forge.listenerBacklog());
    }

    private void poll() {
        DocumentDelta delta = documents.poll();
        if (!delta.isEmpty()) {
            documents.acknowledge(delta);
        }
        if (!enabled) {
            return;
        }
        gate.observe(fingerprint());
        if (gate.due()) {
            build();
        }
    }

    private void rebuild(long generation, GlossConfig.Forge config, int format, CompletableFuture<Boolean> result) {
        synchronized (this) {
            if (!enabled || lifecycle != generation) {
                result.complete(false);
                return;
            }
        }
        try {
            documents.reload();
            PreparedPack built = preparePack(config, format);
            if (built == null) {
                result.complete(false);
                return;
            }
            if (!FoliaScheduler.runGlobal(plugin, () -> publish(generation, built, result))) {
                gate.failed();
                result.complete(false);
                Gloss.logExceptionStack(false, new IllegalStateException("Owner scheduler rejected pack publication"),
                    "forge: cannot publish prepared pack; retaining the previous runtime registry.");
            }
        } catch (RuntimeException failure) {
            gate.failed();
            result.complete(false);
            Gloss.logExceptionStack(false, failure, "forge: pack preparation failed.");
        }
    }

    private PreparedPack preparePack(GlossConfig.Forge config, int format) {
        long started = System.nanoTime();
        String fingerprint = fingerprint();
        Map<String, GlyphDoc> loaded = new LinkedHashMap<>();
        for (Map.Entry<String, GlossDocument<GlyphDoc>> entry : new TreeMap<>(documents.snapshot()).entrySet()) {
            loaded.put(entry.getKey(), entry.getValue().value());
        }

        GlyphLedger ledger;
        try {
            ledger = GlyphLedger.load(ledgerFile, config.codepointBase());
        } catch (RuntimeException refused) {
            gate.failed();
            Gloss.logExceptionStack(false, refused, "forge: ledger could not be loaded.");
            return null;
        }

        GlyphRegistry built;
        try {
            built = GlyphRegistry.build(loaded, ledger, builder.probe());
        } catch (RuntimeException refused) {
            gate.failed();
            Gloss.logExceptionStack(false, refused, "forge: glyph documents refused, keeping the previous pack.");
            return null;
        }

        PackArtifact freshly;
        try {
            freshly = builder.build(built, outDirectory, format);
        } catch (IOException | RuntimeException failure) {
            gate.failed();
            Gloss.logExceptionStack(false, failure, "forge: pack build failed, keeping the previous pack.");
            return null;
        }

        return new PreparedPack(built, freshly, TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - started), fingerprint);
    }

    private void publish(long generation, PreparedPack built, CompletableFuture<Boolean> result) {
        boolean accepted;
        try {
            synchronized (this) {
                accepted = enabled && lifecycle == generation;
                if (accepted) {
                    registry = built.registry();
                    artifact = built.artifact();
                    metrics.replaceDeclared(registry.declaredWidths());
                    packNamespace.publish(artifact.sha1Hex());
                    installEmojiSubstitution(registry);
                    delivery.publish(artifact);
                    gate.completed(built.fingerprint());
                }
            }
        } catch (RuntimeException failure) {
            gate.failed();
            result.complete(false);
            Gloss.logExceptionStack(false, failure, "forge: prepared pack publication failed.");
            return;
        }
        result.complete(accepted);
        if (accepted) {
            Gloss.log(Level.INFO, "forge: pack %s built in %dms (%d glyphs, %d spaces)",
                built.artifact().sha1Hex().substring(0, 8), built.elapsedMillis(),
                built.registry().all().size(), built.registry().spaces().codepoints().size());
        }
    }

    private record PreparedPack(GlyphRegistry registry, PackArtifact artifact, long elapsedMillis, String fingerprint) {
    }

    private void installEmojiSubstitution(GlyphRegistry built) {
        Map<String, String> mapping = new LinkedHashMap<>();
        for (Map.Entry<String, String> entry : built.emojiMappings().entrySet()) {
            layoutFunctions.fontTag(entry.getValue()).ifPresent(glyph -> mapping.put(entry.getKey(), glyph));
        }
        if (mapping.isEmpty()) {
            plugin.emoji().setGlyphSubstitution(null);
            return;
        }
        Map<String, String> glyphs = Map.copyOf(mapping);
        plugin.emoji().setGlyphSubstitution(new GlyphSubstitution() {
            @Override
            public boolean appliesTo(Player viewer) {
                return PackNamespace.loaded(viewer);
            }

            @Override
            public Optional<String> glyphFor(String emojiId) {
                return Optional.ofNullable(glyphs.get(emojiId));
            }
        });
    }

    /**
     * One hash over every document's content plus the size and modification time of every image it
     * references, so an edited PNG rebuilds the pack without a document change.
     */
    private String fingerprint() {
        List<String> parts = new ArrayList<>();
        for (Map.Entry<String, GlossDocument<GlyphDoc>> entry : new TreeMap<>(documents.snapshot()).entrySet()) {
            parts.add(entry.getKey() + "=" + documents.preparedFingerprint(entry.getKey()));
        }
        parts.add("base=" + plugin.cfg().modules().forge().codepointBase());
        parts.add("format=" + plugin.cfg().modules().forge().packFormat());
        for (Map.Entry<String, GlossDocument<GlyphDoc>> entry : new TreeMap<>(documents.snapshot()).entrySet()) {
            for (GlyphDoc.Glyph glyph : entry.getValue().value().glyphs()) {
                parts.add(glyph.image() + "=" + stamp(imagesRoot.resolve(glyph.image())));
            }
            for (GlyphDoc.Overlay overlay : entry.getValue().value().overlays()) {
                parts.add(overlay.image() + "=" + stamp(imagesRoot.resolve(overlay.image())));
            }
            for (GlyphDoc.WaypointStyleAsset style : entry.getValue().value().waypointStyles()) {
                for (GlyphDoc.WaypointSprite sprite : style.sprites()) {
                    parts.add(sprite.image() + "=" + stamp(imagesRoot.resolve(sprite.image())));
                }
            }
        }
        return DocumentHashes.sha256(String.join("\n", parts));
    }

    private static String stamp(Path file) {
        try {
            return Files.isRegularFile(file)
                ? Files.size(file) + ":" + Files.getLastModifiedTime(file).toMillis() : "missing";
        } catch (IOException unreadable) {
            return "unreadable";
        }
    }

    /** The status lines {@code /gloss forge status} prints. */
    public List<String> status() {
        PackArtifact current = artifact;
        List<String> lines = new ArrayList<>();
        lines.add("documents: " + documentIds().size() + ", glyphs: " + registry.all().size()
            + ", spaces: " + registry.spaces().codepoints().size());
        lines.add("pack: " + (current == null ? "not built"
            : current.sha1Hex() + " " + current.zip().getFileName()));
        lines.add("delivery: " + delivery.describe());
        lines.add("viewers loaded: " + packNamespace.loadedCount());
        return List.copyOf(lines);
    }
}
