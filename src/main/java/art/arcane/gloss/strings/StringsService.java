package art.arcane.gloss.strings;

import art.arcane.gloss.Gloss;
import art.arcane.gloss.GlossConfig;
import art.arcane.gloss.doc.DocumentDelta;
import art.arcane.gloss.doc.DocumentRegistry;
import art.arcane.gloss.doc.GlossDocument;
import art.arcane.gloss.doc.ShippedDefaults;
import art.arcane.gloss.doc.ShippedDocumentCatalog;
import art.arcane.gloss.expr.ExprFunctionRegistry;
import art.arcane.gloss.locale.GlossLocalization;
import art.arcane.gloss.locale.GlossMessages;
import art.arcane.gloss.locale.LangArguments;
import art.arcane.gloss.service.GlossService;
import art.arcane.volmlib.util.localization.LanguageAudience;
import art.arcane.volmlib.util.localization.TextKey;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;

import java.io.File;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Owns {@code strings/}: one document per locale, the {@code lang} registry function, and the
 * viewer's content locale. Resolution order is the viewer's locale, that document's fallback
 * chain, {@code en_US}, the plugin's own message catalog, then the key itself.
 */
public final class StringsService implements GlossService, LangResolver {
    public static final String NAME = "strings";

    private final Gloss plugin;
    private final ShippedDefaults defaults;
    private final DocumentRegistry<StringsDoc> registry;
    private volatile StringsCatalog catalog = StringsCatalog.EMPTY;
    private volatile boolean started;

    public StringsService(Gloss plugin) {
        this.plugin = plugin;
        File folder = new File(plugin.getDataFolder(), StringsDoc.KIND);
        this.defaults = new ShippedDefaults(StringsDoc.KIND, folder, ShippedDocumentCatalog.STRINGS.names());
        this.registry = DocumentRegistry.folder(StringsDoc.KIND, folder, StringsDoc::parse, StringsDoc::revision);
    }

    @Override
    public String name() {
        return NAME;
    }

    @Override
    public void contribute() {
        ExprFunctionRegistry.global().register(LangFunction.spec(this));
    }

    @Override
    public void enable() {
        if (!plugin.cfg().modules().strings().enabled()) {
            return;
        }
        started = true;
        reload();
        plugin.watchdog().register(StringsDoc.KIND, this::poll);
    }

    @Override
    public void disable() {
        started = false;
        plugin.watchdog().unregister(StringsDoc.KIND);
        registry.close();
        catalog = StringsCatalog.EMPTY;
        ExprFunctionRegistry.global().unregister(LangFunction.NAME);
    }

    @Override
    public void reload() {
        if (!started) {
            return;
        }
        defaults.extractMissing();
        registry.reload();
        rebuild(registry.snapshot());
    }

    @Override
    public boolean reloadOnConfigChange(GlossConfig previous, GlossConfig next) {
        return previous.modules().strings().enabled() != next.modules().strings().enabled()
            || !previous.language().equals(next.language());
    }

    public StringsCatalog catalog() {
        return catalog;
    }

    public List<String> locales() {
        List<String> locales = new ArrayList<>(catalog.locales());
        locales.sort(String::compareTo);
        return List.copyOf(locales);
    }

    public List<String> missing(String locale) {
        String canonical = normalizeLocale(locale);
        return canonical == null ? List.of() : catalog.missing(canonical);
    }

    public List<String> resetToDefault(String nameOrStar) {
        List<String> restored = defaults.resetToDefault(nameOrStar);
        if (!restored.isEmpty()) {
            registry.reload();
            rebuild(registry.snapshot());
        }
        return restored;
    }

    @Override
    public String resolve(UUID viewerId, String key, List<Object> args) {
        return resolve(catalog, contentLocale(viewerId), viewerId, key, args);
    }

    /** The whole {@code lang} chain: authored strings, the plugin catalog, then the key itself. */
    static String resolve(StringsCatalog catalog, String locale, UUID viewerId, String key, List<Object> args) {
        String template = catalog.resolve(locale, key);
        if (template != null) {
            return StringsCatalog.bind(template, args);
        }
        if (GlossMessages.catalog().key(key) == null) {
            return key;
        }
        TextKey resolved = LangArguments.messageKey(key);
        return LanguageAudience.call(viewerId,
            () -> GlossLocalization.globalText(resolved, LangArguments.arguments(resolved, args)));
    }

    /** The locale a viewer reads authored content in. */
    public String contentLocale(UUID viewerId) {
        if (viewerId == null) {
            return serverLocale();
        }
        return contentLocale(explicitLocale(viewerId), clientLocale(viewerId), plugin.cfg().language());
    }

    /**
     * The explicit {@code /gloss language self} choice, else the normalized client locale, else the
     * server language, else {@code en_US}.
     */
    public static String contentLocale(String explicit, String clientLocale, String serverLanguage) {
        String chosen = normalizeLocale(explicit);
        if (chosen != null) {
            return chosen;
        }
        String client = normalizeLocale(clientLocale);
        if (client != null) {
            return client;
        }
        String server = normalizeLocale(serverLanguage);
        return server == null ? StringsCatalog.ROOT_LOCALE : server;
    }

    /** @return the canonical {@code language_COUNTRY} id, or null when the value is not one */
    public static String normalizeLocale(String value) {
        return StringsDoc.canonicalLocale(value);
    }

    private String serverLocale() {
        String server = normalizeLocale(plugin.cfg().language());
        return server == null ? StringsCatalog.ROOT_LOCALE : server;
    }

    private String explicitLocale(UUID viewerId) {
        GlossLocalization localization = plugin.getLocalization();
        return localization == null ? null : localization.playerLocale(viewerId).orElse(null);
    }

    private String clientLocale(UUID viewerId) {
        Player viewer = Bukkit.getPlayer(viewerId);
        return viewer == null ? null : viewer.getLocale();
    }

    private void poll() {
        DocumentDelta delta = registry.poll();
        if (delta.isEmpty()) {
            return;
        }
        registry.apply(delta, () -> rebuild(registry.snapshot(delta)));
    }

    private void rebuild(Map<String, GlossDocument<StringsDoc>> documents) {
        List<StringsDoc> loaded = new ArrayList<>(documents.size());
        for (GlossDocument<StringsDoc> document : documents.values()) {
            loaded.add(document.value());
        }
        catalog = StringsCatalog.of(loaded);
    }
}
