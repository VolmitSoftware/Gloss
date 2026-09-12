package art.arcane.gloss.motion;

import art.arcane.gloss.Gloss;
import art.arcane.gloss.animation.clip.Clip;
import art.arcane.gloss.animation.clip.ClipPlan;
import art.arcane.gloss.animation.clip.ClipSet;
import art.arcane.gloss.animation.clip.Keyframe;
import art.arcane.gloss.animation.clip.LoopMode;
import art.arcane.gloss.animation.clip.Profile;
import art.arcane.gloss.animation.clip.Track;
import art.arcane.gloss.animation.clip.Trigger;
import art.arcane.gloss.doc.DocumentDelta;
import art.arcane.gloss.doc.DocumentRegistry;
import art.arcane.gloss.doc.DocumentReviser;
import art.arcane.gloss.doc.DocumentStore;
import art.arcane.gloss.doc.GlossDocument;
import art.arcane.gloss.doc.ShippedDefaults;
import art.arcane.gloss.doc.ShippedDocumentCatalog;
import art.arcane.gloss.service.GlossService;

import java.io.File;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

public final class MotionService implements GlossService {
    public static final String NAME = "motion";
    public static final Trigger PLAY = Trigger.of("play");
    private static final String PROFILE_ID = "motion";
    private static final DocumentReviser<MotionDoc> REVISER = new DocumentReviser<>() {
        @Override
        public long revisionOf(MotionDoc value) {
            return value.revision();
        }

        @Override
        public MotionDoc withRevision(MotionDoc value, long revision) {
            return value.withRevision(revision);
        }
    };

    private final Gloss plugin;
    private final ShippedDefaults defaults;
    private final DocumentStore<MotionDoc> store;
    private final DocumentRegistry<MotionDoc> registry;
    private final Map<String, Compiled> compiled = new ConcurrentHashMap<>();
    private final TransformStreamer streamer;

    public MotionService(Gloss plugin) {
        this.plugin = plugin;
        File folder = new File(plugin.getDataFolder(), MotionDoc.KIND);
        this.defaults = new ShippedDefaults(MotionDoc.KIND, folder, ShippedDocumentCatalog.MOTION.names());
        this.store = new DocumentStore<>(MotionDoc.KIND, folder, REVISER);
        this.registry = DocumentRegistry.folder(MotionDoc.KIND, folder, MotionDoc::parse, MotionDoc::revision,
            store::isOwnWrite);
        this.streamer = new TransformStreamer(plugin);
    }

    /** The one transform worker every rig and hologram clip streams through. */
    public TransformStreamer streamer() {
        return streamer;
    }

    public static ClipSet compile(MotionDoc doc) {
        return compile(doc.durationTicks(), doc.loopMode(), doc.tracks());
    }

    static ClipSet compile(double durationTicks, LoopMode loopMode, List<MotionDoc.MotionTrack> tracks) {
        List<Track> converted = new ArrayList<>(tracks.size());
        for (MotionDoc.MotionTrack track : tracks) {
            MotionChannel channel = track.channelKind();
            List<Keyframe> keyframes = new ArrayList<>(track.keyframes().size());
            for (MotionDoc.MotionKeyframe keyframe : track.keyframes()) {
                keyframes.add(new Keyframe(keyframe.tick(), channel.value(keyframe.value()), "", keyframe.curve()));
            }
            converted.add(new Track(track.bone(), channel.target(), track.blendKind(), List.copyOf(keyframes)));
        }
        Clip clip = new Clip(PLAY, durationTicks, loopMode, List.copyOf(converted));
        return new ClipSet(true, Map.of(), List.of(new Profile(PROFILE_ID, 0, List.of("*"), List.of(clip))));
    }

    @Override
    public String name() {
        return NAME;
    }

    @Override
    public void enable() {
        reload();
        plugin.watchdog().register(MotionDoc.KIND, this::poll);
        streamer.start();
    }

    @Override
    public void disable() {
        plugin.watchdog().unregister(MotionDoc.KIND);
        streamer.stop();
        registry.close();
        compiled.clear();
        store.forgetAll();
    }

    @Override
    public void reload() {
        defaults.extractMissing();
        registry.reload();
        compiled.clear();
        for (Map.Entry<String, GlossDocument<MotionDoc>> entry : registry.snapshot().entrySet()) {
            compiled.put(entry.getKey(), Compiled.of(entry.getValue().value()));
        }
        if (!compiled.isEmpty()) {
            Gloss.info("Loaded " + compiled.size() + " motion clips.");
        }
    }

    public Optional<Compiled> compiled(String id) {
        return id == null ? Optional.empty() : Optional.ofNullable(compiled.get(id));
    }

    public Optional<ClipPlan> plan(String id) {
        return compiled(id).map(Compiled::plan);
    }

    public Optional<MotionDoc> doc(String id) {
        return compiled(id).map(Compiled::doc);
    }

    public Set<String> ids() {
        return Set.copyOf(compiled.keySet());
    }

    public DocumentStore<MotionDoc> store() {
        return store;
    }

    public List<String> resetToDefault(String name) {
        List<String> restored = defaults.resetToDefault(name);
        if (!restored.isEmpty()) {
            reload();
        }
        return restored;
    }

    private void poll() {
        DocumentDelta delta = registry.poll();
        if (delta.isEmpty()) {
            return;
        }
        registry.apply(delta, () -> {
            for (String id : delta.loaded()) {
                GlossDocument<MotionDoc> document = registry.get(delta, id);
                if (document != null) {
                    compiled.put(id, Compiled.of(document.value()));
                    Gloss.info("Hotloaded motion " + id + ".json");
                }
            }
            for (String id : delta.removed()) {
                compiled.remove(id);
            }
        });
    }

    public record Compiled(MotionDoc doc, ClipPlan plan) {
        public static Compiled of(MotionDoc doc) {
            return new Compiled(doc, ClipPlan.compile(compile(doc)));
        }
    }
}
