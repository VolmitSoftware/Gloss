package art.arcane.gloss.rig;

import art.arcane.gloss.doc.DocumentEnvelope;
import art.arcane.gloss.doc.DocumentParsers;

import java.util.LinkedHashMap;
import java.util.Map;

public record RigInstanceDoc(
    int schemaVersion,
    long revision,
    String rig,
    String world,
    Double x,
    Double y,
    Double z,
    Float yaw,
    Float pitch,
    Double scale,
    String state,
    Map<String, Object> vars,
    RigDoc.Audience audience
) {
    public static final String KIND = "rig-instances";
    public static final int CURRENT_SCHEMA_VERSION = 1;
    public static final double MIN_SCALE = 0.01D;
    public static final double MAX_SCALE = 64.0D;
    public static final int MAX_VARS = 64;

    public RigInstanceDoc {
        DocumentEnvelope.requireSchemaVersion(KIND, schemaVersion, CURRENT_SCHEMA_VERSION);
        DocumentEnvelope.requireRevision(KIND, revision);
        if (rig == null || rig.isBlank()) {
            throw new IllegalArgumentException("rig instance needs a rig id");
        }
        rig = rig.trim();
        if (world == null || world.isBlank()) {
            throw new IllegalArgumentException("rig instance needs a world");
        }
        world = world.trim();
        x = coordinate(x, "x");
        y = coordinate(y, "y");
        z = coordinate(z, "z");
        yaw = yaw == null ? 0.0F : angle(yaw, "yaw");
        pitch = pitch == null ? 0.0F : angle(pitch, "pitch");
        scale = scale == null ? 1.0D : scale;
        if (!Double.isFinite(scale) || scale < MIN_SCALE || scale > MAX_SCALE) {
            throw new IllegalArgumentException("rig instance scale must be within " + MIN_SCALE + ".." + MAX_SCALE);
        }
        state = state == null || state.isBlank() ? null : state.trim();
        vars = copyVars(vars);
        audience = audience == null ? RigDoc.Audience.EVERYONE : audience;
    }

    public static RigInstanceDoc parse(String fileName, String raw) {
        return DocumentParsers.parseJson(fileName, raw, RigInstanceDoc.class);
    }

    public static RigInstanceDoc placed(String rig, String world, double x, double y, double z, float yaw, float pitch) {
        return new RigInstanceDoc(CURRENT_SCHEMA_VERSION, DocumentEnvelope.INITIAL_REVISION, rig, world, x, y, z, yaw,
            pitch, 1.0D, null, Map.of(), null);
    }

    public RigInstanceDoc withRevision(long revision) {
        return new RigInstanceDoc(schemaVersion, revision, rig, world, x, y, z, yaw, pitch, scale, state, vars, audience);
    }

    public RigInstanceDoc withPosition(String world, double x, double y, double z, float yaw, float pitch) {
        return new RigInstanceDoc(schemaVersion, revision, rig, world, x, y, z, yaw, pitch, scale, state, vars, audience);
    }

    public RigInstanceDoc withScale(double scale) {
        return new RigInstanceDoc(schemaVersion, revision, rig, world, x, y, z, yaw, pitch, scale, state, vars, audience);
    }

    public RigInstanceDoc withState(String state) {
        return new RigInstanceDoc(schemaVersion, revision, rig, world, x, y, z, yaw, pitch, scale, state, vars, audience);
    }

    public RigInstanceDoc withVars(Map<String, Object> vars) {
        return new RigInstanceDoc(schemaVersion, revision, rig, world, x, y, z, yaw, pitch, scale, state, vars, audience);
    }

    private static double coordinate(Double value, String name) {
        if (value == null || !Double.isFinite(value)) {
            throw new IllegalArgumentException("rig instance needs a finite " + name);
        }
        return value;
    }

    private static float angle(Float value, String name) {
        if (!Float.isFinite(value)) {
            throw new IllegalArgumentException("rig instance " + name + " must be finite");
        }
        return value;
    }

    private static Map<String, Object> copyVars(Map<String, Object> vars) {
        if (vars == null || vars.isEmpty()) {
            return Map.of();
        }
        if (vars.size() > MAX_VARS) {
            throw new IllegalArgumentException("rig instance may declare at most " + MAX_VARS + " vars");
        }
        Map<String, Object> copied = new LinkedHashMap<>(vars.size() * 2);
        for (Map.Entry<String, Object> entry : vars.entrySet()) {
            if (entry.getKey() == null || entry.getKey().isBlank()) {
                throw new IllegalArgumentException("rig instance var needs a name");
            }
            Object value = RigStateMachine.normalize(entry.getValue());
            if (value == null) {
                throw new IllegalArgumentException("rig instance var " + entry.getKey() + " must be a number, string or boolean");
            }
            copied.put(entry.getKey().trim(), value);
        }
        return Map.copyOf(copied);
    }
}
