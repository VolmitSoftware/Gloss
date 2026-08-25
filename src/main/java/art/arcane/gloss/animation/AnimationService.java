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
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicLong;

public final class AnimationService {
    private static final String FUNCTION_PREFIX = "animation.";

    private final Gloss plugin;
    private final File folder;
    private final ShippedDefaults defaults;
    private final DocumentRegistry<AnimationDoc> registry;
    private final List<String> registeredFunctions;
    private final AnimationFrameCache frameCache;
    private final AtomicLong generation;
    private volatile List<AnimationClip> clips;
    private volatile Map<String, AnimationClip> clipsById;

    public AnimationService(Gloss plugin) {
        this.plugin = plugin;
        this.folder = new File(plugin.getDataFolder(), AnimationDoc.KIND);
        this.defaults = new ShippedDefaults(AnimationDoc.KIND, folder, ShippedDocumentCatalog.ANIMATIONS.names());
        this.registry = DocumentRegistry.folder(AnimationDoc.KIND, folder, AnimationDoc::parse, AnimationDoc::revision);
        this.registeredFunctions = new ArrayList<>();
        this.frameCache = new AnimationFrameCache(raw -> plugin.text().renderStatic(raw));
        this.generation = new AtomicLong();
        this.clips = List.of();
        this.clipsById = Map.of();
    }

    public void enable() {
        if (!plugin.cfg().animations().enabled()) {
            return;
        }
        defaults.extractMissing();
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
        generation.incrementAndGet();
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
        if (hasDynamicFrames(clip, new HashSet<>())) {
            return null;
        }
        return frameCache.staticFrames(clip, TextPipeline.emojiGeneration());
    }

    public long generation() {
        return generation.get();
    }

    public boolean framesViewerSpecific(String raw) {
        return inspectAnimationTokens(raw, true, new HashSet<>());
    }

    public boolean hasDynamicAnimationContent(List<String> lines) {
        for (String line : lines) {
            if (hasDynamicTextOutsideAnimationTokens(line)
                || inspectAnimationTokens(line, false, new HashSet<>())) {
                return true;
            }
        }
        return false;
    }

    public boolean hasFastDynamicAnimationContent(List<String> lines) {
        for (String line : lines) {
            if (TextPipeline.timeDependent(line)
                || inspectAnimationTimeDependencies(line, new HashSet<>())) {
                return true;
            }
        }
        return false;
    }

    public List<String> resetToDefault(String nameOrStar) {
        return defaults.resetToDefault(nameOrStar);
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
        generation.incrementAndGet();
    }

    private boolean inspectAnimationTokens(String raw, boolean viewerSpecific, Set<String> visiting) {
        if (raw == null || raw.isEmpty()) {
            return false;
        }
        int open = raw.indexOf('|');
        while (open >= 0) {
            int close = raw.indexOf('|', open + 1);
            if (close < 0) {
                return false;
            }
            String name = raw.substring(open + 1, close);
            if (name.startsWith(FUNCTION_PREFIX)) {
                String id = name.substring(FUNCTION_PREFIX.length());
                AnimationClip clip = clipsById.get(id);
                if (clip != null && visiting.add(id)) {
                    try {
                        for (String frame : clip.frames()) {
                            boolean direct = viewerSpecific
                                ? TextPipeline.viewerSpecific(frame)
                                : hasDynamicTextOutsideAnimationTokens(frame);
                            if (direct || inspectAnimationTokens(frame, viewerSpecific, visiting)) {
                                return true;
                            }
                        }
                    } finally {
                        visiting.remove(id);
                    }
                }
            }
            open = raw.indexOf('|', close + 1);
        }
        return false;
    }

    private boolean hasDynamicFrames(AnimationClip clip, Set<String> visiting) {
        if (!visiting.add(clip.id())) {
            return true;
        }
        try {
            for (String frame : clip.frames()) {
                if (hasDynamicTextOutsideAnimationTokens(frame)
                    || inspectAnimationTokens(frame, false, visiting)) {
                    return true;
                }
            }
            return false;
        } finally {
            visiting.remove(clip.id());
        }
    }

    private boolean inspectAnimationTimeDependencies(String raw, Set<String> visiting) {
        int open = raw.indexOf('|');
        while (open >= 0) {
            int close = raw.indexOf('|', open + 1);
            if (close < 0) {
                return false;
            }
            String name = raw.substring(open + 1, close);
            if (name.startsWith(FUNCTION_PREFIX)) {
                String id = name.substring(FUNCTION_PREFIX.length());
                AnimationClip clip = clipsById.get(id);
                if (clip != null && visiting.add(id)) {
                    try {
                        for (String frame : clip.frames()) {
                            if (TextPipeline.timeDependent(frame)
                                || inspectAnimationTimeDependencies(frame, visiting)) {
                                return true;
                            }
                        }
                    } finally {
                        visiting.remove(id);
                    }
                }
            }
            open = raw.indexOf('|', close + 1);
        }
        return false;
    }

    private static boolean hasDynamicTextOutsideAnimationTokens(String raw) {
        StringBuilder retained = null;
        int cursor = 0;
        int open = raw.indexOf('|');
        while (open >= 0) {
            int close = raw.indexOf('|', open + 1);
            if (close < 0) {
                break;
            }
            String name = raw.substring(open + 1, close);
            if (name.startsWith(FUNCTION_PREFIX)) {
                if (retained == null) {
                    retained = new StringBuilder(raw.length());
                }
                retained.append(raw, cursor, open);
                cursor = close + 1;
            }
            open = raw.indexOf('|', close + 1);
        }
        if (retained == null) {
            return TextPipeline.viewerDependent(raw);
        }
        retained.append(raw, cursor, raw.length());
        return TextPipeline.viewerDependent(retained.toString());
    }

    private synchronized void unregisterFunctions() {
        for (String name : registeredFunctions) {
            plugin.text().unregisterFunction(name);
        }
        registeredFunctions.clear();
    }
}
