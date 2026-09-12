package art.arcane.gloss.rig;

import art.arcane.gloss.animation.clip.LoopMode;
import art.arcane.gloss.api.IconBillboard;
import art.arcane.gloss.api.IconDisplayStyle;
import art.arcane.gloss.condition.ConditionCompiler;
import art.arcane.gloss.condition.ShowCondition;
import art.arcane.gloss.config.action.MenuActionData;
import art.arcane.gloss.config.icon.MenuIconData;
import art.arcane.gloss.doc.DocumentEnvelope;
import art.arcane.gloss.doc.DocumentParsers;
import com.github.retrooper.packetevents.util.Vector3f;
import com.google.gson.TypeAdapter;
import com.google.gson.annotations.JsonAdapter;
import com.google.gson.stream.JsonReader;
import com.google.gson.stream.JsonToken;
import com.google.gson.stream.JsonWriter;

import java.io.IOException;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

public record RigDoc(
    int schemaVersion,
    long revision,
    ShowCondition show,
    List<BoneDef> bones,
    List<PartDef> parts,
    Map<String, ClipRef> clips,
    Graph graph,
    List<Hitbox> hitboxes,
    Lod lod,
    Audience audience
) {
    public static final String KIND = "rigs";
    public static final int CURRENT_SCHEMA_VERSION = 1;
    public static final int MAX_PARTS = 256;
    public static final int MAX_BONES = 256;
    public static final int MAX_BRIGHTNESS = 15;

    public RigDoc {
        DocumentEnvelope.requireSchemaVersion(KIND, schemaVersion, CURRENT_SCHEMA_VERSION);
        DocumentEnvelope.requireRevision(KIND, revision);
        show = show == null ? ShowCondition.ALWAYS : show;
        bones = copyBones(bones);
        parts = copyParts(parts, bones);
        clips = copyClips(clips);
        graph = validateGraph(graph, clips);
        hitboxes = copyHitboxes(hitboxes, parts);
        lod = validateLod(lod == null ? Lod.DEFAULTS : lod, parts);
        audience = audience == null ? Audience.EVERYONE : audience;
    }

    public static RigDoc parse(String fileName, String raw) {
        return DocumentParsers.parseJson(fileName, raw, RigDoc.class);
    }

    public RigDoc withRevision(long revision) {
        return new RigDoc(schemaVersion, revision, show, bones, parts, clips, graph, hitboxes, lod, audience);
    }

    public RigModel model() {
        List<Bone> converted = new ArrayList<>(bones.size());
        for (BoneDef bone : bones) {
            converted.add(bone.toBone());
        }
        return RigModel.of(converted);
    }

    public List<Part> toParts() {
        List<Part> converted = new ArrayList<>(parts.size());
        for (PartDef part : parts) {
            converted.add(part.toPart());
        }
        return List.copyOf(converted);
    }

    public void requirePartCap(int cap) {
        if (parts.size() > cap) {
            throw new IllegalArgumentException("rig declares " + parts.size() + " parts; the configured limit is " + cap);
        }
    }

    private static List<BoneDef> copyBones(List<BoneDef> bones) {
        if (bones == null || bones.isEmpty()) {
            throw new IllegalArgumentException("rig needs at least one bone");
        }
        if (bones.size() > MAX_BONES) {
            throw new IllegalArgumentException("rig may declare at most " + MAX_BONES + " bones");
        }
        List<BoneDef> copied = new ArrayList<>(bones.size());
        List<Bone> converted = new ArrayList<>(bones.size());
        for (BoneDef bone : bones) {
            Objects.requireNonNull(bone, "rig bones must not contain null entries");
            copied.add(bone);
            converted.add(bone.toBone());
        }
        RigModel.of(converted);
        return List.copyOf(copied);
    }

    private static List<PartDef> copyParts(List<PartDef> parts, List<BoneDef> bones) {
        if (parts == null || parts.isEmpty()) {
            throw new IllegalArgumentException("rig needs at least one part");
        }
        if (parts.size() > MAX_PARTS) {
            throw new IllegalArgumentException("rig may declare at most " + MAX_PARTS + " parts");
        }
        Set<String> boneIds = new HashSet<>(bones.size() * 2);
        for (BoneDef bone : bones) {
            boneIds.add(bone.id());
        }
        Set<String> partIds = new HashSet<>(parts.size() * 2);
        List<PartDef> copied = new ArrayList<>(parts.size());
        for (PartDef part : parts) {
            Objects.requireNonNull(part, "rig parts must not contain null entries");
            if (!partIds.add(part.id())) {
                throw new IllegalArgumentException("rig part " + part.id() + " is declared twice");
            }
            if (!boneIds.contains(part.bone())) {
                throw new IllegalArgumentException("rig part " + part.id() + " names unknown bone " + part.bone());
            }
            copied.add(part);
        }
        return List.copyOf(copied);
    }

    private static Map<String, ClipRef> copyClips(Map<String, ClipRef> clips) {
        if (clips == null || clips.isEmpty()) {
            return Map.of();
        }
        Map<String, ClipRef> copied = new LinkedHashMap<>(clips.size() * 2);
        for (Map.Entry<String, ClipRef> entry : clips.entrySet()) {
            String name = entry.getKey() == null ? "" : entry.getKey().trim();
            if (name.isEmpty()) {
                throw new IllegalArgumentException("rig clip has no name");
            }
            if (entry.getValue() == null) {
                throw new IllegalArgumentException("rig clip " + name + " has no motion");
            }
            copied.put(name, entry.getValue());
        }
        return Map.copyOf(copied);
    }

    private static Graph validateGraph(Graph graph, Map<String, ClipRef> clips) {
        if (graph == null) {
            return null;
        }
        for (Map.Entry<String, State> entry : graph.states().entrySet()) {
            String clip = entry.getValue().clip();
            if (clip != null && !clips.containsKey(clip)) {
                throw new IllegalArgumentException("rig state " + entry.getKey() + " names unknown clip " + clip);
            }
            String then = entry.getValue().then();
            if (then != null && !graph.states().containsKey(then)) {
                throw new IllegalArgumentException("rig state " + entry.getKey() + " chains to unknown state " + then);
            }
        }
        if (!graph.states().containsKey(graph.initial())) {
            throw new IllegalArgumentException("rig graph initial state " + graph.initial() + " is not declared");
        }
        for (Transition transition : graph.transitions()) {
            if (!transition.from().equals(CompiledGraph.ANY_STATE) && !graph.states().containsKey(transition.from())) {
                throw new IllegalArgumentException("rig transition from unknown state " + transition.from());
            }
            if (!graph.states().containsKey(transition.to())) {
                throw new IllegalArgumentException("rig transition to unknown state " + transition.to());
            }
        }
        return graph;
    }

    private static List<Hitbox> copyHitboxes(List<Hitbox> hitboxes, List<PartDef> parts) {
        if (hitboxes == null || hitboxes.isEmpty()) {
            return List.of();
        }
        Set<String> partIds = new HashSet<>(parts.size() * 2);
        for (PartDef part : parts) {
            partIds.add(part.id());
        }
        List<Hitbox> copied = new ArrayList<>(hitboxes.size());
        for (Hitbox hitbox : hitboxes) {
            Objects.requireNonNull(hitbox, "rig hitboxes must not contain null entries");
            if (!partIds.contains(hitbox.part())) {
                throw new IllegalArgumentException("rig hitbox names unknown part " + hitbox.part());
            }
            copied.add(hitbox);
        }
        return List.copyOf(copied);
    }

    private static Lod validateLod(Lod lod, List<PartDef> parts) {
        if (lod.minimalPart() != null) {
            boolean found = false;
            for (PartDef part : parts) {
                found |= part.id().equals(lod.minimalPart());
            }
            if (!found) {
                throw new IllegalArgumentException("rig lod names unknown minimalPart " + lod.minimalPart());
            }
        }
        return lod;
    }

    static Vector3f vector(List<Double> values, String name, float fallback) {
        if (values == null) {
            return new Vector3f(fallback, fallback, fallback);
        }
        if (values.size() != 3) {
            throw new IllegalArgumentException(name + " needs exactly three numbers");
        }
        float[] components = new float[3];
        for (int index = 0; index < 3; index++) {
            Double value = values.get(index);
            if (value == null || !Double.isFinite(value)) {
                throw new IllegalArgumentException(name + " needs finite numbers");
            }
            components[index] = value.floatValue();
        }
        return new Vector3f(components[0], components[1], components[2]);
    }

    public record TransformDef(List<Double> translation, List<Double> rotation, List<Double> scale) {
        public Transform toTransform() {
            Vector3f rotationDegrees = vector(rotation, "rotation", 0.0F);
            return new Transform(vector(translation, "translation", 0.0F),
                Quaternions.fromEulerDegrees(rotationDegrees.getX(), rotationDegrees.getY(), rotationDegrees.getZ()),
                vector(scale, "scale", 1.0F));
        }
    }

    public record BoneDef(String id, String parent, TransformDef rest) {
        public BoneDef {
            if (id == null || id.isBlank()) {
                throw new IllegalArgumentException("rig bone needs an id");
            }
            id = id.trim();
            parent = parent == null || parent.isBlank() ? null : parent.trim();
            if (rest != null) {
                rest.toTransform();
            }
        }

        public Bone toBone() {
            return new Bone(id, parent, rest == null ? Transform.identity() : rest.toTransform());
        }
    }

    public record PartDef(
        String id,
        String bone,
        String type,
        String block,
        MenuIconData item,
        String text,
        TransformDef transform,
        String billboard,
        Integer brightness,
        IconDisplayStyle style
    ) {
        public PartDef {
            if (id == null || id.isBlank()) {
                throw new IllegalArgumentException("rig part needs an id");
            }
            id = id.trim();
            if (bone == null || bone.isBlank()) {
                throw new IllegalArgumentException("rig part " + id + " needs a bone");
            }
            bone = bone.trim();
            PartType kind = PartType.parse(type);
            type = kind.name().toLowerCase(Locale.ROOT);
            if (billboard != null) {
                billboard = requireBillboard(id, billboard);
            }
            if (brightness != null && (brightness < 0 || brightness > MAX_BRIGHTNESS)) {
                throw new IllegalArgumentException("rig part " + id + " brightness must be 0.." + MAX_BRIGHTNESS);
            }
            if (transform != null) {
                transform.toTransform();
            }
            toPart(id, bone, kind, block, item, text, transform, billboard, brightness, style);
        }

        public PartType partType() {
            return PartType.parse(type);
        }

        public Part toPart() {
            return toPart(id, bone, partType(), block, item, text, transform, billboard, brightness, style);
        }

        public IconBillboard billboardKind() {
            if (billboard != null) {
                return IconBillboard.valueOf(billboard.toUpperCase(Locale.ROOT));
            }
            if (style != null) {
                return style.billboard();
            }
            return partType() == PartType.TEXT ? IconBillboard.CENTER : IconBillboard.FIXED;
        }

        private static Part toPart(String id, String bone, PartType kind, String block, MenuIconData item, String text,
                                   TransformDef transform, String billboard, Integer brightness, IconDisplayStyle style) {
            return new Part(id, bone, kind, block, item, text,
                transform == null ? Transform.identity() : transform.toTransform(), billboard, brightness, style);
        }

        private static String requireBillboard(String id, String billboard) {
            String normalized = billboard.trim().toLowerCase(Locale.ROOT);
            for (IconBillboard candidate : IconBillboard.values()) {
                if (candidate.name().toLowerCase(Locale.ROOT).equals(normalized)) {
                    return normalized;
                }
            }
            throw new IllegalArgumentException("rig part " + id + " billboard must be fixed, vertical, horizontal or center");
        }
    }

    @JsonAdapter(ClipRef.Adapter.class)
    public record ClipRef(String motion, String loop) {
        public ClipRef {
            if (motion == null || motion.isBlank()) {
                throw new IllegalArgumentException("rig clip needs a motion id");
            }
            motion = motion.trim();
            if (loop != null) {
                loop = loop.trim().toLowerCase(Locale.ROOT);
                LoopMode.parse(loop);
            }
        }

        public LoopMode loopOverride() {
            return loop == null ? null : LoopMode.parse(loop);
        }

        public static final class Adapter extends TypeAdapter<ClipRef> {
            @Override
            public void write(JsonWriter out, ClipRef value) throws IOException {
                if (value == null) {
                    out.nullValue();
                    return;
                }
                if (value.loop() == null) {
                    out.value(value.motion());
                    return;
                }
                out.beginObject().name("motion").value(value.motion()).name("loop").value(value.loop()).endObject();
            }

            @Override
            public ClipRef read(JsonReader in) throws IOException {
                JsonToken token = in.peek();
                if (token == JsonToken.NULL) {
                    in.nextNull();
                    return null;
                }
                if (token == JsonToken.STRING) {
                    return new ClipRef(in.nextString(), null);
                }
                if (token != JsonToken.BEGIN_OBJECT) {
                    throw new IllegalArgumentException("rig clip must be a motion id or { motion, loop } at " + in.getPath());
                }
                String motion = null;
                String loop = null;
                in.beginObject();
                while (in.hasNext()) {
                    String name = in.nextName();
                    switch (name) {
                        case "motion" -> motion = in.nextString();
                        case "loop" -> loop = in.nextString();
                        default -> throw new IllegalArgumentException("rig clip has unknown key " + name + " at " + in.getPath());
                    }
                }
                in.endObject();
                return new ClipRef(motion, loop);
            }
        }
    }

    public record State(String clip, String then) {
        public State {
            clip = clip == null || clip.isBlank() ? null : clip.trim();
            then = then == null || then.isBlank() ? null : then.trim();
        }
    }

    public record Transition(String from, String to, String when) {
        public Transition {
            if (from == null || from.isBlank() || to == null || to.isBlank()) {
                throw new IllegalArgumentException("rig transition needs from and to");
            }
            from = from.trim();
            to = to.trim();
            if (when == null || when.isBlank()) {
                throw new IllegalArgumentException("rig transition " + from + " -> " + to + " needs a when condition");
            }
            when = when.trim();
            ConditionCompiler.compile(when);
        }
    }

    public record Graph(String initial, Map<String, State> states, List<Transition> transitions) {
        public Graph {
            if (initial == null || initial.isBlank()) {
                throw new IllegalArgumentException("rig graph needs an initial state");
            }
            initial = initial.trim();
            if (states == null || states.isEmpty()) {
                throw new IllegalArgumentException("rig graph needs at least one state");
            }
            Map<String, State> copied = new LinkedHashMap<>(states.size() * 2);
            for (Map.Entry<String, State> entry : states.entrySet()) {
                if (entry.getKey() == null || entry.getKey().isBlank() || entry.getValue() == null) {
                    throw new IllegalArgumentException("rig graph state needs a name and a body");
                }
                copied.put(entry.getKey().trim(), entry.getValue());
            }
            states = Map.copyOf(copied);
            transitions = transitions == null ? List.of() : List.copyOf(transitions);
        }
    }

    public record Hitbox(String part, List<Double> size, List<MenuActionData> actions) {
        public static final float MAX_SIZE = 64.0F;

        public Hitbox {
            if (part == null || part.isBlank()) {
                throw new IllegalArgumentException("rig hitbox needs a part");
            }
            part = part.trim();
            Vector3f extent = vector(size, "hitbox size", 1.0F);
            if (extent.getX() <= 0.0F || extent.getY() <= 0.0F || extent.getZ() <= 0.0F
                || extent.getX() > MAX_SIZE || extent.getY() > MAX_SIZE || extent.getZ() > MAX_SIZE) {
                throw new IllegalArgumentException("rig hitbox size must be within 0.." + MAX_SIZE + " on every axis");
            }
            size = size == null ? List.of(1.0D, 1.0D, 1.0D) : List.copyOf(size);
            actions = actions == null ? List.of() : List.copyOf(actions);
        }

        public Vector3f extent() {
            return vector(size, "hitbox size", 1.0F);
        }
    }

    public record Lod(Double reducedAt, Double minimalAt, Double cullAt, String minimalPart) {
        public static final double DEFAULT_REDUCED_AT = 32.0D;
        public static final double DEFAULT_MINIMAL_AT = 64.0D;
        public static final double DEFAULT_CULL_AT = 96.0D;
        public static final double MAX_RANGE = 512.0D;
        static final Lod DEFAULTS = new Lod(null, null, null, null);

        public Lod {
            reducedAt = range(reducedAt, DEFAULT_REDUCED_AT);
            minimalAt = range(minimalAt, DEFAULT_MINIMAL_AT);
            cullAt = range(cullAt, DEFAULT_CULL_AT);
            if (reducedAt > minimalAt || minimalAt > cullAt) {
                throw new IllegalArgumentException("rig lod must satisfy reducedAt <= minimalAt <= cullAt");
            }
            minimalPart = minimalPart == null || minimalPart.isBlank() ? null : minimalPart.trim();
        }

        private static double range(Double value, double fallback) {
            if (value == null) {
                return fallback;
            }
            if (!Double.isFinite(value) || value < 0.0D || value > MAX_RANGE) {
                throw new IllegalArgumentException("rig lod distances must be within 0.." + MAX_RANGE);
            }
            return value;
        }
    }

    public record Audience(ShowCondition when) {
        public static final Audience EVERYONE = new Audience(ShowCondition.ALWAYS);

        public Audience {
            when = when == null ? ShowCondition.ALWAYS : when;
        }
    }
}
