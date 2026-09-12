package art.arcane.gloss.rig;

import art.arcane.gloss.config.icon.CustomItemIconData;
import art.arcane.gloss.config.icon.MenuIconData;
import art.arcane.gloss.doc.DocumentEnvelope;
import art.arcane.gloss.motion.MotionDoc;
import art.arcane.gloss.motion.MotionService;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * Reads a Blockbench {@code .bbmodel} into a rig document and one motion document per animation.
 *
 * <p>Lossy by design: groups become bones, cubes become block or item parts sized to the cube, and
 * bone animators become keyframe tracks. Textures, UV, meshes and locators are reported back to the
 * operator rather than converted.
 */
public final class BbmodelImporter {
    public static final String DEFAULT_BLOCK = "minecraft:white_concrete";
    /** Blockbench works in sixteenths of a block. */
    public static final double UNITS_PER_BLOCK = 16.0D;
    public static final double TICKS_PER_SECOND = 20.0D;
    public static final long MAX_FILE_BYTES = 8L * 1024L * 1024L;

    public record Result(int bones, int parts, int animations, List<String> unsupported) {
    }

    public record Documents(RigDoc rig, Map<String, MotionDoc> motions, Result result) {
    }

    private record Group(String id, String parent, double[] origin, double[] rotation, List<String> cubes) {
    }

    private record Cube(String id, double[] from, double[] to) {
    }

    private final RigService rigs;
    private final MotionService motion;

    public BbmodelImporter(RigService rigs, MotionService motion) {
        this.rigs = rigs;
        this.motion = motion;
    }

    /** Reads {@code file} and writes the rig and its motion documents through the owning services. */
    public Result importFile(Path file, String rigId, String textureItem) throws IOException {
        if (!Files.isRegularFile(file)) {
            throw new IllegalArgumentException("no file at " + file);
        }
        if (Files.size(file) > MAX_FILE_BYTES) {
            throw new IllegalArgumentException("bbmodel is larger than " + MAX_FILE_BYTES + " bytes");
        }
        Documents documents = convert(Files.readString(file, StandardCharsets.UTF_8), rigId, textureItem);
        for (Map.Entry<String, MotionDoc> entry : documents.motions().entrySet()) {
            motion.store().write(entry.getKey(), entry.getValue());
        }
        rigs.rigStore().write(rigId, documents.rig());
        motion.reload();
        rigs.reload();
        return documents.result();
    }

    /** Converts raw bbmodel JSON without touching disk. */
    public static Documents convert(String raw, String rigId, String textureItem) {
        JsonObject model = JsonParser.parseString(raw).getAsJsonObject();
        Set<String> unsupported = new LinkedHashSet<>();
        Map<String, Cube> cubes = readCubes(model, unsupported);
        List<Group> groups = readGroups(model, unsupported);
        if (groups.isEmpty() || cubes.isEmpty()) {
            throw new IllegalArgumentException("bbmodel declares no groups or no cubes");
        }
        Map<String, Group> byUuid = new LinkedHashMap<>();
        for (Group group : groups) {
            byUuid.put(group.id(), group);
        }

        List<RigDoc.BoneDef> bones = new ArrayList<>(groups.size());
        List<RigDoc.PartDef> parts = new ArrayList<>();
        double[] rigOrigin = groups.getFirst().origin();
        Map<String, String> boneNames = new LinkedHashMap<>();
        Set<String> usedBoneNames = new LinkedHashSet<>();
        Set<String> usedPartNames = new LinkedHashSet<>();
        for (Group group : groups) {
            String boneName = unique(usedBoneNames, name(model, group.id()));
            boneNames.put(group.id(), boneName);
        }
        for (Group group : groups) {
            String boneName = boneNames.get(group.id());
            Group parent = group.parent() == null ? null : byUuid.get(group.parent());
            double[] reference = parent == null ? rigOrigin : parent.origin();
            bones.add(new RigDoc.BoneDef(boneName, parent == null ? null : boneNames.get(parent.id()),
                new RigDoc.TransformDef(scaled(group.origin(), reference), degrees(group.rotation()), null)));
            for (String cubeId : group.cubes()) {
                Cube cube = cubes.get(cubeId);
                if (cube == null) {
                    continue;
                }
                parts.add(part(unique(usedPartNames, cube.id()), boneName, cube, group.origin(), textureItem));
            }
        }

        Map<String, MotionDoc> motions = new LinkedHashMap<>();
        Map<String, RigDoc.ClipRef> clips = new LinkedHashMap<>();
        int animations = 0;
        for (JsonElement element : array(model, "animations")) {
            JsonObject animation = element.getAsJsonObject();
            String clipName = sanitize(string(animation, "name", "animation"));
            MotionDoc document = motion(animation, boneNames);
            if (document == null) {
                continue;
            }
            animations++;
            String motionId = rigId + "-" + clipName;
            motions.put(motionId, document);
            clips.put(clipName, new RigDoc.ClipRef(motionId, document.loop()));
        }

        RigDoc.Graph graph = null;
        if (!clips.isEmpty()) {
            Map<String, RigDoc.State> states = new LinkedHashMap<>();
            for (String clipName : clips.keySet()) {
                states.put(clipName, new RigDoc.State(clipName, null));
            }
            graph = new RigDoc.Graph(states.keySet().iterator().next(), states, List.of());
        }
        RigDoc rig = new RigDoc(RigDoc.CURRENT_SCHEMA_VERSION, DocumentEnvelope.INITIAL_REVISION, null,
            List.copyOf(bones), List.copyOf(parts), clips, graph, List.of(), null, null);
        return new Documents(rig, motions, new Result(bones.size(), parts.size(), animations, List.copyOf(unsupported)));
    }

    private static RigDoc.PartDef part(String id, String bone, Cube cube, double[] origin, String textureItem) {
        double[] center = new double[]{
            (cube.from()[0] + cube.to()[0]) / 2.0D,
            (cube.from()[1] + cube.to()[1]) / 2.0D,
            (cube.from()[2] + cube.to()[2]) / 2.0D};
        List<Double> translation = scaled(center, origin);
        List<Double> scale = List.of(
            Math.abs(cube.to()[0] - cube.from()[0]) / UNITS_PER_BLOCK,
            Math.abs(cube.to()[1] - cube.from()[1]) / UNITS_PER_BLOCK,
            Math.abs(cube.to()[2] - cube.from()[2]) / UNITS_PER_BLOCK);
        RigDoc.TransformDef transform = new RigDoc.TransformDef(translation, null, scale);
        MenuIconData item = item(textureItem);
        return new RigDoc.PartDef(id, bone, item == null ? "block" : "item", item == null ? DEFAULT_BLOCK : null,
            item, null, transform, null, null, null);
    }

    private static MenuIconData item(String textureItem) {
        if (textureItem == null || textureItem.isBlank()) {
            return null;
        }
        int separator = textureItem.indexOf(':');
        if (separator <= 0 || separator == textureItem.length() - 1) {
            throw new IllegalArgumentException("texture must read provider:id, got '" + textureItem + "'");
        }
        return new CustomItemIconData(textureItem.substring(0, separator), textureItem.substring(separator + 1), 1, null);
    }

    private static MotionDoc motion(JsonObject animation, Map<String, String> boneNames) {
        double lengthTicks = number(animation, "length", 0.0D) * TICKS_PER_SECOND;
        String loop = switch (string(animation, "loop", "once").toLowerCase(Locale.ROOT)) {
            case "loop" -> "loop";
            case "pingpong" -> "pingpong";
            default -> "once";
        };
        List<MotionDoc.MotionTrack> tracks = new ArrayList<>();
        JsonObject animators = animation.has("animators") && animation.get("animators").isJsonObject()
            ? animation.getAsJsonObject("animators")
            : new JsonObject();
        for (Map.Entry<String, JsonElement> entry : animators.entrySet()) {
            String bone = boneNames.get(entry.getKey());
            if (bone == null || !entry.getValue().isJsonObject()) {
                continue;
            }
            tracks.addAll(tracks(bone, entry.getValue().getAsJsonObject()));
        }
        if (tracks.isEmpty()) {
            return null;
        }
        double duration = Math.max(lengthTicks, lastTick(tracks));
        return new MotionDoc(MotionDoc.CURRENT_SCHEMA_VERSION, DocumentEnvelope.INITIAL_REVISION, duration, loop,
            null, List.copyOf(tracks));
    }

    private static List<MotionDoc.MotionTrack> tracks(String bone, JsonObject animator) {
        Map<String, List<MotionDoc.MotionKeyframe>> byChannel = new LinkedHashMap<>();
        Map<String, Boolean> moved = new LinkedHashMap<>();
        for (JsonElement element : array(animator, "keyframes")) {
            JsonObject keyframe = element.getAsJsonObject();
            String channel = string(keyframe, "channel", "");
            String prefix = switch (channel.toLowerCase(Locale.ROOT)) {
                case "rotation" -> "rotation";
                case "position" -> "translation";
                case "scale" -> "scale";
                default -> null;
            };
            if (prefix == null) {
                continue;
            }
            double tick = number(keyframe, "time", 0.0D) * TICKS_PER_SECOND;
            String easing = easing(string(keyframe, "interpolation", "linear"));
            JsonArray points = array(keyframe, "data_points");
            if (points.isEmpty()) {
                continue;
            }
            JsonObject point = points.get(0).getAsJsonObject();
            double neutral = "scale".equals(prefix) ? 1.0D : 0.0D;
            double divisor = "translation".equals(prefix) ? UNITS_PER_BLOCK : 1.0D;
            for (String axis : List.of("x", "y", "z")) {
                double raw = number(point, axis, neutral);
                double value = raw / divisor;
                String name = prefix + "." + axis;
                byChannel.computeIfAbsent(name, ignored -> new ArrayList<>())
                    .add(new MotionDoc.MotionKeyframe(tick, value, easing));
                moved.merge(name, value != neutral, (previous, current) -> previous || current);
            }
        }
        List<MotionDoc.MotionTrack> tracks = new ArrayList<>(byChannel.size());
        for (Map.Entry<String, List<MotionDoc.MotionKeyframe>> entry : byChannel.entrySet()) {
            if (!Boolean.TRUE.equals(moved.get(entry.getKey()))) {
                continue;
            }
            tracks.add(new MotionDoc.MotionTrack(bone, entry.getKey(), null, List.copyOf(entry.getValue())));
        }
        return tracks;
    }

    private static String easing(String interpolation) {
        return switch (interpolation.toLowerCase(Locale.ROOT)) {
            case "catmullrom", "bezier", "smooth" -> "ease_in_out";
            case "step" -> "hold";
            default -> "linear";
        };
    }

    private static double lastTick(List<MotionDoc.MotionTrack> tracks) {
        double last = 0.0D;
        for (MotionDoc.MotionTrack track : tracks) {
            for (MotionDoc.MotionKeyframe keyframe : track.keyframes()) {
                last = Math.max(last, keyframe.tick());
            }
        }
        return last;
    }

    private static Map<String, Cube> readCubes(JsonObject model, Set<String> unsupported) {
        Map<String, Cube> cubes = new LinkedHashMap<>();
        for (JsonElement element : array(model, "elements")) {
            JsonObject cube = element.getAsJsonObject();
            String type = string(cube, "type", "cube").toLowerCase(Locale.ROOT);
            if (!"cube".equals(type)) {
                unsupported.add(type.isBlank() ? "meshes" : type + "es");
                continue;
            }
            if (cube.has("faces")) {
                for (Map.Entry<String, JsonElement> face : cube.getAsJsonObject("faces").entrySet()) {
                    if (face.getValue().isJsonObject() && face.getValue().getAsJsonObject().has("uv")) {
                        unsupported.add("uv");
                        break;
                    }
                }
            }
            if (!cube.has("from") || !cube.has("to")) {
                continue;
            }
            String uuid = string(cube, "uuid", "");
            cubes.put(uuid, new Cube(sanitize(string(cube, "name", uuid)), vector(cube, "from"), vector(cube, "to")));
        }
        if (!array(model, "textures").isEmpty()) {
            unsupported.add("textures");
        }
        return cubes;
    }

    private static List<Group> readGroups(JsonObject model, Set<String> unsupported) {
        List<Group> groups = new ArrayList<>();
        appendGroups(array(model, "outliner"), null, groups, unsupported);
        return groups;
    }

    private static void appendGroups(JsonArray outliner, String parent, List<Group> groups, Set<String> unsupported) {
        for (JsonElement element : outliner) {
            if (!element.isJsonObject()) {
                continue;
            }
            JsonObject node = element.getAsJsonObject();
            String type = string(node, "type", "group").toLowerCase(Locale.ROOT);
            if (!"group".equals(type)) {
                unsupported.add("locators");
                continue;
            }
            String uuid = string(node, "uuid", string(node, "name", ""));
            List<String> cubes = new ArrayList<>();
            JsonArray children = array(node, "children");
            for (JsonElement child : children) {
                if (child.isJsonPrimitive()) {
                    cubes.add(child.getAsString());
                }
            }
            groups.add(new Group(uuid, parent, vector(node, "origin"), vector(node, "rotation"), List.copyOf(cubes)));
            appendGroups(children, uuid, groups, unsupported);
        }
    }

    private static String name(JsonObject model, String uuid) {
        for (JsonElement element : array(model, "outliner")) {
            String found = nameIn(element, uuid);
            if (found != null) {
                return found;
            }
        }
        return sanitize(uuid);
    }

    private static String nameIn(JsonElement element, String uuid) {
        if (!element.isJsonObject()) {
            return null;
        }
        JsonObject node = element.getAsJsonObject();
        if (uuid.equals(string(node, "uuid", ""))) {
            return sanitize(string(node, "name", uuid));
        }
        for (JsonElement child : array(node, "children")) {
            String found = nameIn(child, uuid);
            if (found != null) {
                return found;
            }
        }
        return null;
    }

    private static List<Double> scaled(double[] origin, double[] reference) {
        return List.of((origin[0] - reference[0]) / UNITS_PER_BLOCK,
            (origin[1] - reference[1]) / UNITS_PER_BLOCK,
            (origin[2] - reference[2]) / UNITS_PER_BLOCK);
    }

    private static List<Double> degrees(double[] rotation) {
        if (rotation[0] == 0.0D && rotation[1] == 0.0D && rotation[2] == 0.0D) {
            return null;
        }
        return List.of(rotation[0], rotation[1], rotation[2]);
    }

    private static String unique(Set<String> used, String candidate) {
        String name = candidate.isBlank() ? "part" : candidate;
        String unique = name;
        int suffix = 2;
        while (!used.add(unique)) {
            unique = name + "-" + suffix;
            suffix++;
        }
        return unique;
    }

    private static String sanitize(String raw) {
        String trimmed = raw == null ? "" : raw.trim();
        StringBuilder cleaned = new StringBuilder(trimmed.length());
        for (int index = 0; index < trimmed.length(); index++) {
            char value = Character.toLowerCase(trimmed.charAt(index));
            if (value >= 'a' && value <= 'z' || value >= '0' && value <= '9' || value == '_' || value == '-') {
                cleaned.append(value);
            } else if (value == ' ' || value == '.') {
                cleaned.append('-');
            }
        }
        return cleaned.isEmpty() ? "part" : cleaned.toString();
    }

    private static double[] vector(JsonObject source, String name) {
        JsonArray array = array(source, name);
        double[] values = new double[3];
        for (int index = 0; index < 3 && index < array.size(); index++) {
            values[index] = array.get(index).getAsDouble();
        }
        return values;
    }

    private static JsonArray array(JsonObject source, String name) {
        return source.has(name) && source.get(name).isJsonArray() ? source.getAsJsonArray(name) : new JsonArray();
    }

    private static String string(JsonObject source, String name, String fallback) {
        return source.has(name) && source.get(name).isJsonPrimitive() ? source.get(name).getAsString() : fallback;
    }

    private static double number(JsonObject source, String name, double fallback) {
        if (!source.has(name) || !source.get(name).isJsonPrimitive()) {
            return fallback;
        }
        try {
            return source.get(name).getAsDouble();
        } catch (NumberFormatException notANumber) {
            return fallback;
        }
    }
}
