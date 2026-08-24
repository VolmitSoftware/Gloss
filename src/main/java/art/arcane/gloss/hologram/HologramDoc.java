package art.arcane.gloss.hologram;

import art.arcane.gloss.doc.DocumentEnvelope;
import art.arcane.gloss.doc.DocumentParsers;
import org.bukkit.util.Vector;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Objects;

public record HologramDoc(int schemaVersion, long revision, Anchor anchor, List<String> lines,
                          Boolean seeThrough, double scale, String billboard, Double yaw, Double pitch) {
    public static final String KIND = "holograms";
    public static final int CURRENT_SCHEMA_VERSION = 1;
    public static final double DEFAULT_SCALE = 1.0D;
    public static final double MIN_SCALE = 0.05D;
    public static final double MAX_SCALE = 16.0D;
    public static final List<String> BILLBOARDS = List.of("CENTER", "FIXED", "HORIZONTAL", "VERTICAL");
    public static final String DEFAULT_BILLBOARD = "CENTER";
    public static final double MAX_YAW_DEGREES = 180.0D;
    public static final double MAX_PITCH_DEGREES = 90.0D;

    public record Anchor(String world, Vector position) {
        public Anchor {
            if (world == null || world.isBlank()) {
                throw new IllegalArgumentException("hologram anchor requires a world");
            }
            position = Objects.requireNonNull(position, "hologram anchor requires a position").clone();
        }

        @Override
        public Vector position() {
            return position.clone();
        }
    }

    public HologramDoc {
        DocumentEnvelope.requireSchemaVersion(KIND, schemaVersion, CURRENT_SCHEMA_VERSION);
        DocumentEnvelope.requireRevision(KIND, revision);
        anchor = Objects.requireNonNull(anchor, "hologram requires an anchor");
        lines = copyLines(lines);
        seeThrough = seeThrough == null || seeThrough;
        scale = requireScale(scale);
        billboard = requireBillboard(billboard);
        yaw = requireAngle("yaw", yaw, MAX_YAW_DEGREES);
        pitch = requireAngle("pitch", pitch, MAX_PITCH_DEGREES);
    }

    public HologramDoc withRevision(long revision) {
        return new HologramDoc(schemaVersion, revision, anchor, lines, seeThrough, scale, billboard, yaw, pitch);
    }

    public static HologramDoc parse(String fileName, String raw) {
        return DocumentParsers.parseJson(fileName, raw, HologramDoc.class);
    }

    public static String normalizeBillboard(String billboard) {
        if (billboard == null || billboard.isBlank()) {
            return DEFAULT_BILLBOARD;
        }

        String normalized = billboard.trim().toUpperCase(Locale.ROOT);
        return BILLBOARDS.contains(normalized) ? normalized : null;
    }

    public static String requireBillboard(String billboard) {
        String normalized = normalizeBillboard(billboard);
        if (normalized == null) {
            throw new IllegalArgumentException("hologram billboard must be one of "
                + String.join(", ", BILLBOARDS) + "; got '" + billboard + "'");
        }
        return normalized;
    }

    public static boolean angleInRange(double angle, double limit) {
        return Double.isFinite(angle) && Math.abs(angle) <= limit;
    }

    public static double requireYaw(double yaw) {
        return requireAngle("yaw", yaw, MAX_YAW_DEGREES);
    }

    public static double requirePitch(double pitch) {
        return requireAngle("pitch", pitch, MAX_PITCH_DEGREES);
    }

    public static boolean scaleInRange(double scale) {
        return Double.isFinite(scale) && scale >= MIN_SCALE && scale <= MAX_SCALE;
    }

    public static double requireScale(double scale) {
        if (!scaleInRange(scale)) {
            throw new IllegalArgumentException("hologram scale must be a finite value between "
                + MIN_SCALE + " and " + MAX_SCALE + "; got " + scale);
        }
        return scale;
    }

    private static double requireAngle(String name, Double angle, double limit) {
        if (angle == null) {
            return 0.0D;
        }
        if (!angleInRange(angle, limit)) {
            throw new IllegalArgumentException("hologram " + name + " must be a finite angle between -"
                + limit + " and " + limit + " degrees; got " + angle);
        }
        return angle;
    }

    private static List<String> copyLines(List<String> lines) {
        if (lines == null) {
            return List.of();
        }
        List<String> copied = new ArrayList<>(lines.size());
        for (String line : lines) {
            copied.add(line == null ? "" : line);
        }
        return List.copyOf(copied);
    }
}
