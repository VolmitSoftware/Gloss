package art.arcane.gloss.names;

import art.arcane.gloss.Gloss;
import art.arcane.gloss.doc.DocumentDelta;
import art.arcane.gloss.doc.DocumentRegistry;
import art.arcane.gloss.doc.GlossDocument;
import art.arcane.gloss.doc.RegistryOwner;
import art.arcane.gloss.doc.ShippedDefaults;
import art.arcane.gloss.doc.ShippedDocumentCatalog;
import art.arcane.gloss.expr.ExprFunctionRegistry;
import art.arcane.volmlib.util.scheduling.SchedulerUtils;

import java.io.File;
import java.util.List;
import java.util.Map;

public final class NamesService implements RegistryOwner {
    private static final NamesCatalog FALLBACK = new NamesCatalog(NamesDoc.DEFAULTS);

    private final Gloss plugin;
    private final DocumentRegistry<NamesDoc> registry;
    private final ShippedDefaults defaults;
    private volatile NamesCatalog catalog = FALLBACK;

    public NamesService(Gloss plugin) {
        this.plugin = plugin;
        registry = DocumentRegistry.singleFile(NamesDoc.KIND,
            new File(plugin.getDataFolder(), "names.json"), NamesDoc::parse, NamesDoc::revision);
        defaults = new ShippedDefaults(NamesDoc.KIND, plugin.getDataFolder(), ShippedDocumentCatalog.NAMES.names());
    }

    public static String name(NameCategory category, String key) {
        Gloss plugin = Gloss.instance;
        NamesService service = plugin == null ? null : plugin.names();
        return (service == null ? FALLBACK : service.catalog).resolve(category, key);
    }

    public void enable() {
        ExprFunctionRegistry.global().register(new ExprFunctionRegistry.Spec("name",
            ExprFunctionRegistry.Kind.STRING,
            List.of(ExprFunctionRegistry.Kind.STRING, ExprFunctionRegistry.Kind.STRING), false,
            (scope, arguments) -> catalog.resolve(NameCategory.parse((String) arguments.getFirst()),
                (String) arguments.get(1))));
        defaults.extractMissing();
        registry.reload();
        rebuild(registry.get(NamesDoc.KIND));
        plugin.watchdog().register(NamesDoc.KIND, this::poll);
    }

    public void disable() {
        plugin.watchdog().unregister(NamesDoc.KIND);
        ExprFunctionRegistry.global().unregister("name");
        registry.close();
        catalog = FALLBACK;
    }

    public void reload() {
        registry.reload();
        rebuild(registry.get(NamesDoc.KIND));
        refreshDisplays();
    }

    @Override
    public Map<String, DocumentRegistry<?>> registries() {
        return Map.of(NamesDoc.KIND, registry);
    }

    private void poll() {
        DocumentDelta delta = registry.poll();
        if (!delta.isEmpty()) {
            if (!registry.dispatch(delta, task -> SchedulerUtils.runGlobal(plugin, task), () -> apply(delta))) {
                Gloss.warnThrottled("names-hotload-scheduling",
                    "Could not apply names on the server thread; the change will be retried.");
            }
        }
    }

    void apply(DocumentDelta delta) {
        rebuild(registry.get(delta, NamesDoc.KIND));
        refreshDisplays();
    }

    private void rebuild(GlossDocument<NamesDoc> document) {
        catalog = new NamesCatalog(document == null ? NamesDoc.DEFAULTS : document.value());
    }

    private void refreshDisplays() {
        if (plugin.text() != null) {
            plugin.text().invalidateRendering();
        }
        if (plugin.drops() != null) {
            plugin.drops().refreshNames();
        }
    }
}
