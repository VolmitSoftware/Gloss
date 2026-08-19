package art.arcane.gloss.animation;

import art.arcane.gloss.Gloss;
import art.arcane.gloss.doc.DocumentRegistry;
import art.arcane.gloss.doc.GlossDocument;
import art.arcane.gloss.doc.ShippedDefaults;
import art.arcane.gloss.doc.ShippedDocumentCatalog;
import art.arcane.gloss.text.TextPipeline;
import art.arcane.volmlib.util.math.M;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.logging.Level;

public final class AnimationService {
    private static final String FUNCTION_PREFIX = "animation.";
    private static final String RAINBOW_NAME = "rainbow";
    private static final String LEGACY_RAINBOW_DEFAULT = """
        {
          "schemaVersion": 1,
          "revision": 1,
          "mode": "ascend",
          "frameIntervalMs": 500,
          "frames": [
            "&cGloss",
            "&6Gloss",
            "&aGloss",
            "&bGloss"
          ]
        }
        """;

    private final Gloss plugin;
    private final File folder;
    private final ShippedDefaults defaults;
    private final DocumentRegistry<AnimationDoc> registry;
    private final List<String> registeredFunctions;
    private final AnimationFrameCache frameCache;
    private volatile List<AnimationClip> clips;
    private volatile Map<String, AnimationClip> clipsById;

    public AnimationService(Gloss plugin) {
        this.plugin = plugin;
        this.folder = new File(plugin.getDataFolder(), AnimationDoc.KIND);
        this.defaults = new ShippedDefaults(AnimationDoc.KIND, folder, ShippedDocumentCatalog.ANIMATIONS.names());
        this.registry = DocumentRegistry.folder(AnimationDoc.KIND, folder, AnimationDoc::parse, AnimationDoc::revision);
        this.registeredFunctions = new ArrayList<>();
        this.frameCache = new AnimationFrameCache(raw -> plugin.text().renderStatic(raw));
        this.clips = List.of();
        this.clipsById = Map.of();
    }

    public void enable() {
        if (!plugin.cfg().animations().enabled()) {
            return;
        }
        defaults.extractMissing();
        if (upgradeLegacyRainbowDefault(defaults)) {
            Gloss.log(Level.INFO,
                "animations/rainbow.json: upgraded the unchanged legacy shipped default.");
        }
        registry.reload();
        rebuild();
        plugin.watchdog().register("animations", this::pollRegistry);
    }

    public void disable() {
        plugin.watchdog().unregister("animations");
        unregisterFunctions();
        frameCache.clear();
        clips = List.of();
        clipsById = Map.of();
    }

    public void reload() {
        disable();
        enable();
    }

    public List<String> names() {
        List<AnimationClip> snapshot = clips;
        List<String> out = new ArrayList<>(snapshot.size());
        for (AnimationClip clip : snapshot) {
            out.add(clip.id());
        }
        return out;
    }

    public AnimationClip clip(String id) {
        return id == null ? null : clipsById.get(id);
    }

    public List<String> staticFrames(AnimationClip clip) {
        return frameCache.staticFrames(clip, TextPipeline.emojiGeneration());
    }

    public List<String> resetToDefault(String nameOrStar) {
        return defaults.resetToDefault(nameOrStar);
    }

    static boolean upgradeLegacyRainbowDefault(ShippedDefaults defaults) {
        return defaults.replaceIfExact(RAINBOW_NAME, LEGACY_RAINBOW_DEFAULT.getBytes(StandardCharsets.UTF_8));
    }

    private void pollRegistry() {
        if (registry.poll().isEmpty()) {
            return;
        }

        rebuild();
    }

    private synchronized void rebuild() {
        unregisterFunctions();
        frameCache.clear();
        List<AnimationClip> loaded = new ArrayList<>(registry.snapshot().size());
        for (GlossDocument<AnimationDoc> document : registry.snapshot().values()) {
            AnimationDoc doc = document.value();
            loaded.add(new AnimationClip(document.id(), 1000.0D / doc.frameIntervalMs(), doc.toMode(), doc.frames()));
        }

        loaded.sort(Comparator.comparing(AnimationClip::id));
        clips = List.copyOf(loaded);
        Map<String, AnimationClip> byId = new HashMap<>(loaded.size() * 2);
        for (AnimationClip clip : loaded) {
            byId.put(clip.id(), clip);
        }

        clipsById = Map.copyOf(byId);
        for (AnimationClip clip : clips) {
            String name = FUNCTION_PREFIX + clip.id();
            plugin.text().registerFunction(name, player -> clip.frameAt(M.ms()));
            registeredFunctions.add(name);
        }
    }

    private synchronized void unregisterFunctions() {
        for (String name : registeredFunctions) {
            plugin.text().unregisterFunction(name);
        }
        registeredFunctions.clear();
    }
}
