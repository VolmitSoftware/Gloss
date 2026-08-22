package art.arcane.gloss.animation;

import art.arcane.gloss.Gloss;
import art.arcane.gloss.doc.DocumentDelta;
import art.arcane.gloss.doc.DocumentRegistry;
import art.arcane.gloss.doc.GlossDocument;
import art.arcane.gloss.doc.ShippedDefaults;
import art.arcane.gloss.doc.ShippedDocumentCatalog;
import art.arcane.gloss.text.TextPipeline;
import art.arcane.volmlib.util.math.M;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
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
    private static final String LEGACY_PHASE_LOCKED_RAINBOW_RESOURCE =
        "/legacy-defaults/animations/rainbow-50ms.json";
    private static final String LEGACY_NAMED_RAINBOW_DEFAULT = """
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
    private static final String LEGACY_STEPPED_RAINBOW_DEFAULT = """
        {
          "schemaVersion": 1,
          "revision": 1,
          "mode": "ascend",
          "frameIntervalMs": 500,
          "frames": [
            "&c",
            "&6",
            "&a",
            "&b"
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
                "animations/rainbow.json: upgraded the unchanged prior shipped default to the 53 ms RGB cycle.");
        }
        registry.reload();
        rebuild(registry.snapshot());
        plugin.watchdog().register("animations", this::pollRegistry);
    }

    public void disable() {
        plugin.watchdog().unregister("animations");
        registry.close();
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
        if (defaults.replaceIfExact(RAINBOW_NAME, legacyPhaseLockedRainbowDefault())) {
            return true;
        }
        if (defaults.replaceIfExact(
            RAINBOW_NAME,
            LEGACY_NAMED_RAINBOW_DEFAULT.getBytes(StandardCharsets.UTF_8))) {
            return true;
        }
        return defaults.replaceIfExact(
            RAINBOW_NAME,
            LEGACY_STEPPED_RAINBOW_DEFAULT.getBytes(StandardCharsets.UTF_8));
    }

    private static byte[] legacyPhaseLockedRainbowDefault() {
        try (InputStream stream = AnimationService.class.getResourceAsStream(
            LEGACY_PHASE_LOCKED_RAINBOW_RESOURCE)) {
            if (stream == null) {
                throw new IllegalStateException("Missing shipped migration resource: "
                    + LEGACY_PHASE_LOCKED_RAINBOW_RESOURCE);
            }
            return stream.readAllBytes();
        } catch (IOException failure) {
            throw new IllegalStateException("Cannot read shipped migration resource: "
                + LEGACY_PHASE_LOCKED_RAINBOW_RESOURCE, failure);
        }
    }

    private void pollRegistry() {
        DocumentDelta delta = registry.poll();
        if (delta.isEmpty()) {
            return;
        }
        registry.apply(delta, () -> rebuild(registry.snapshot(delta)));
    }

    private synchronized void rebuild(Map<String, GlossDocument<AnimationDoc>> documents) {
        unregisterFunctions();
        frameCache.clear();
        List<AnimationClip> loaded = new ArrayList<>(documents.size());
        for (GlossDocument<AnimationDoc> document : documents.values()) {
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
