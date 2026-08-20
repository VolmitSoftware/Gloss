package art.arcane.gloss.emoji;

import art.arcane.gloss.service.GlossTelemetry;
import art.arcane.gloss.Gloss;
import art.arcane.gloss.doc.DocumentRegistry;
import art.arcane.gloss.doc.GlossDocument;
import art.arcane.gloss.doc.ShippedDefaults;
import art.arcane.gloss.doc.ShippedDocumentCatalog;
import art.arcane.gloss.text.TextPipeline;
import org.bukkit.entity.Player;

import java.io.File;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

public final class EmojiService {
    private static final String PERMISSION_PREFIX = "gloss.emoji.";

    private final Gloss plugin;
    private final File folder;
    private final ShippedDefaults defaults;
    private final DocumentRegistry<EmojiDoc> registry;
    private volatile List<EmojiEntry> entries;
    private volatile EmojiReplacer replacer;
    private volatile Map<String, String> permissionNodes;

    public EmojiService(Gloss plugin) {
        this.plugin = plugin;
        this.folder = new File(plugin.getDataFolder(), EmojiDoc.KIND);
        this.defaults = new ShippedDefaults(EmojiDoc.KIND, folder, ShippedDocumentCatalog.EMOJI.names());
        this.registry = DocumentRegistry.folder(EmojiDoc.KIND, folder, EmojiDoc::parse, EmojiDoc::revision);
        this.entries = List.of();
        this.replacer = new EmojiReplacer(List.of());
        this.permissionNodes = Map.of();
    }

    public void enable() {
        if (!plugin.cfg().emoji().enabled()) {
            return;
        }
        defaults.extractMissing();
        registry.reload();
        rebuild();
        plugin.text().setEmojiFilter(this::apply);
        plugin.text().setViewerEmojiFilter(this::applyFor);
        plugin.watchdog().register("emoji", this::pollRegistry);
    }

    public void disable() {
        plugin.watchdog().unregister("emoji");
        plugin.text().setViewerEmojiFilter(null);
        plugin.text().setEmojiFilter(null);
        entries = List.of();
        replacer = new EmojiReplacer(List.of());
        permissionNodes = Map.of();
        TextPipeline.publishEmojiTriggers(List.of());
    }

    public void reload() {
        disable();
        enable();
    }

    public List<EmojiEntry> all() {
        return entries;
    }

    public List<String> resetToDefault(String nameOrStar) {
        return defaults.resetToDefault(nameOrStar);
    }

    public String apply(String message) {
        return counted(message, replacer.apply(message));
    }

    public String applyFor(Player sender, String message) {
        if (sender == null || !plugin.cfg().emoji().emojiSpecificPermissions()) {
            return counted(message, replacer.apply(message));
        }

        Map<String, String> nodes = permissionNodes;
        return counted(message, replacer.apply(message,
            id -> sender.hasPermission(nodes.getOrDefault(id, PERMISSION_PREFIX + id))));
    }

    private static String counted(String input, String output) {
        if (output != null && output != input && !output.equals(input)) {
            GlossTelemetry.countEmojiReplacement();
        }
        return output;
    }

    public List<String> suggestions(String prefix) {
        List<EmojiEntry> snapshot = entries;
        String needle = prefix == null ? "" : prefix.toLowerCase(Locale.ROOT);
        List<String> out = new ArrayList<>(snapshot.size());
        for (EmojiEntry entry : snapshot) {
            if (!entry.enabled()) {
                continue;
            }

            String token = entry.token();
            if (needle.isEmpty() || token.toLowerCase(Locale.ROOT).startsWith(needle)) {
                out.add(token);
            }
        }

        return out;
    }

    private void pollRegistry() {
        if (registry.poll().isEmpty()) {
            return;
        }

        rebuild();
    }

    private void rebuild() {
        List<EmojiEntry> loaded = new ArrayList<>(registry.snapshot().size());
        for (GlossDocument<EmojiDoc> document : registry.snapshot().values()) {
            EmojiDoc doc = document.value();
            loaded.add(new EmojiEntry(document.id(), doc.trigger(), UnicodeText.parse(doc.emoji()), doc.enabled()));
        }

        loaded.sort(Comparator.comparing(EmojiEntry::id));
        entries = List.copyOf(loaded);
        replacer = new EmojiReplacer(entries);

        Map<String, String> nodes = new HashMap<>(loaded.size() * 2);
        List<String> triggers = new ArrayList<>();
        for (EmojiEntry entry : entries) {
            nodes.put(entry.id(), PERMISSION_PREFIX + entry.id());
            if (entry.enabled() && entry.hasTrigger()) {
                triggers.add(entry.trigger());
            }
        }
        permissionNodes = Map.copyOf(nodes);
        TextPipeline.publishEmojiTriggers(triggers);
    }
}
