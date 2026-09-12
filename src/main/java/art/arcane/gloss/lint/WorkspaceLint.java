package art.arcane.gloss.lint;

import art.arcane.gloss.Gloss;
import art.arcane.gloss.doc.DocumentRegistry;
import art.arcane.gloss.doc.GlossDocument;
import art.arcane.gloss.doc.RegistryOwner;
import art.arcane.gloss.glosspack.GlossPackLedger;
import art.arcane.gloss.glosspack.GlossPackLedgers;
import art.arcane.gloss.integrate.IntegrationBridgeService;
import art.arcane.gloss.service.GlossService;
import org.bukkit.Bukkit;
import org.bukkit.permissions.Permission;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Stream;

/**
 * The workspace linter behind {@code /gloss check} and the editor's Problems panel.
 *
 * <p>Building the context reads registries and Bukkit state, so it happens on the caller's thread
 * during a command; the rules themselves are pure over that snapshot.
 */
public final class WorkspaceLint implements GlossService {
    public static final String NAME = "lint";
    private static final int MAX_WARNINGS = 192;
    private static final int MAX_WARNING_CHARACTERS = 512;
    private static final List<LintRule> RULES = List.of(
            new NavigateTargetRule(),
            new PanelRootRule(),
            new ImageRule(),
            new EmojiRule(),
            new AnimationRule(),
            new ReferenceRules(),
            new PapiRule(),
            new MetricRule(),
            new PermissionRule(),
            new VariantPriorityRule(),
            new ConditionNeverTrueRule(),
            new ViewerTokenRule(),
            new SchemaSkippedRule(),
            new ServerCommandRule());

    private final Gloss plugin;

    public WorkspaceLint(Gloss plugin) {
        this.plugin = Objects.requireNonNull(plugin, "plugin");
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

    /** Every rule over every document, optionally narrowed to one kind and one id. */
    public static List<Diagnostic> run(LintContext context, Optional<String> kind,
                                       Optional<String> id) {
        List<Diagnostic> diagnostics = new ArrayList<>();
        for (LintRule rule : RULES) {
            for (Diagnostic diagnostic : rule.check(context)) {
                if (kind.isPresent() && !kind.get().equals(diagnostic.kind())) {
                    continue;
                }
                if (id.isPresent() && !id.get().equals(diagnostic.id())) {
                    continue;
                }
                diagnostics.add(diagnostic);
            }
        }
        diagnostics.sort(Comparator.comparing(Diagnostic::kind)
                .thenComparing(Diagnostic::id)
                .thenComparing(Diagnostic::code)
                .thenComparing(Diagnostic::pointer));
        return List.copyOf(diagnostics);
    }

    public List<Diagnostic> run(Optional<String> kind, Optional<String> id) {
        return run(context(), kind, id);
    }

    /**
     * Diagnostics for a caller's own document set rather than the live registries, in the wire
     * shape. The editor sync snapshot uses this so the Problems panel reports exactly the documents
     * the project carries.
     */
    public List<String> warningsFor(Map<String, Map<String, String>> documents) {
        LintContext.Builder builder = LintContext.builder();
        documents.forEach((kind, byId) ->
                byId.forEach((id, json) -> builder.document(kind, id, json)));
        addImages(builder);
        addPermissions(builder);
        addMetrics(builder);
        addPackOwnership(builder);
        List<String> wires = new ArrayList<>();
        for (Diagnostic diagnostic : run(builder.build(), Optional.empty(), Optional.empty())) {
            if (wires.size() >= MAX_WARNINGS) {
                break;
            }
            String wire = diagnostic.wire();
            wires.add(wire.length() > MAX_WARNING_CHARACTERS
                    ? wire.substring(0, MAX_WARNING_CHARACTERS)
                    : wire);
        }
        return List.copyOf(wires);
    }

    /** A snapshot of the live workspace: registries, assets, permissions, metrics and packs. */
    public LintContext context() {
        LintContext.Builder builder = LintContext.builder();
        for (RegistryOwner owner : owners()) {
            for (Map.Entry<String, DocumentRegistry<?>> registry : owner.registries().entrySet()) {
                for (GlossDocument<?> document : registry.getValue().snapshot().values()) {
                    builder.document(registry.getKey(), document.id(), document.raw());
                }
                for (Map.Entry<String, String> skipped
                        : registry.getValue().unsupportedSchemaDocuments().entrySet()) {
                    builder.skippedSchema(registry.getKey(), skipped.getKey(), skipped.getValue());
                }
            }
        }
        addImages(builder);
        addPermissions(builder);
        addMetrics(builder);
        addPackOwnership(builder);
        return builder.build();
    }

    private void addImages(LintContext.Builder builder) {
        Path images = plugin.getDataFolder().toPath().resolve("images");
        if (!Files.isDirectory(images, LinkOption.NOFOLLOW_LINKS)) {
            return;
        }
        try (Stream<Path> files = Files.walk(images)) {
            for (Path file : files.toList()) {
                if (Files.isRegularFile(file, LinkOption.NOFOLLOW_LINKS)) {
                    builder.image(images.relativize(file).toString()
                            .replace(java.io.File.separatorChar, '/'));
                }
            }
        } catch (IOException unreadable) {
            Gloss.logExceptionStack(false, unreadable, "Lint could not list image assets.");
        }
    }

    private void addPermissions(LintContext.Builder builder) {
        Set<String> nodes = new LinkedHashSet<>();
        for (Permission permission : Bukkit.getPluginManager().getPermissions()) {
            nodes.add(permission.getName());
        }
        builder.permissions(nodes);
    }

    private void addMetrics(LintContext.Builder builder) {
        IntegrationBridgeService integration = plugin.getIntegrationBridge();
        if (integration != null && integration.bridge() != null) {
            builder.metricKeys(integration.bridge().allKeys());
        }
        if (Bukkit.getPluginManager().isPluginEnabled("PlaceholderAPI")) {
            builder.papiExpansions(papiExpansions());
        }
    }

    private Set<String> papiExpansions() {
        Set<String> expansions = new LinkedHashSet<>();
        try {
            Class<?> manager = Class.forName("me.clip.placeholderapi.PlaceholderAPIPlugin");
            Object plugin = manager.getMethod("getInstance").invoke(null);
            Object cloud = manager.getMethod("getLocalExpansionManager").invoke(plugin);
            Object identifiers = cloud.getClass().getMethod("getIdentifiers").invoke(cloud);
            if (identifiers instanceof java.util.Collection<?> values) {
                for (Object value : values) {
                    expansions.add(String.valueOf(value).toLowerCase(Locale.ROOT));
                }
            }
        } catch (ReflectiveOperationException | RuntimeException unavailable) {
            return Set.of();
        }
        return Set.copyOf(expansions);
    }

    private void addPackOwnership(LintContext.Builder builder) {
        Set<String> owned = new LinkedHashSet<>();
        for (GlossPackLedger ledger
                : new GlossPackLedgers(plugin.getDataFolder().toPath()).all()) {
            owned.addAll(ledger.installedHashes().keySet());
        }
        builder.packOwned(owned);
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
}
