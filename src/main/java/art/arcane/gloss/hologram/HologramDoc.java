package art.arcane.gloss.hologram;

import art.arcane.gloss.doc.DocumentEnvelope;
import art.arcane.gloss.doc.DocumentParsers;
import art.arcane.gloss.api.ParticleLayer;
import art.arcane.gloss.api.HologramBox;
import art.arcane.gloss.api.IconDisplayStyle;
import art.arcane.gloss.condition.ShowCondition;
import art.arcane.gloss.config.action.MenuActionData;
import org.bukkit.util.Vector;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.Set;

public record HologramDoc(int schemaVersion, long revision, Anchor anchor, List<HologramLine> lines,
                          IconDisplayStyle style, HologramBox box, Double yaw, Double pitch,
                          List<ParticleLayer> particleLayers, ShowCondition show,
                          List<HologramPage> pages, List<MenuActionData> actions, Hitbox hitbox,
                          String motion) {
    public static final String KIND = "holograms";
    public static final int CURRENT_SCHEMA_VERSION = 3;
    public static final double DEFAULT_SCALE = 1.0D;
    public static final double MIN_SCALE = 0.01D;
    public static final double MAX_SCALE = 64.0D;
    public static final List<String> BILLBOARDS = List.of("CENTER", "FIXED", "HORIZONTAL", "VERTICAL");
    public static final String DEFAULT_BILLBOARD = "CENTER";
    public static final double MAX_YAW_DEGREES = 180.0D;
    public static final double MAX_PITCH_DEGREES = 90.0D;
    public static final int MAX_PAGES = 64;
    public static final int MAX_ACTIONS = 32;

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

    /** The interaction box a hologram with actions spawns for viewers in range. */
    public record Hitbox(Double width, Double height, Boolean perLine) {
        public static final double DEFAULT_WIDTH = 1.2D;
        public static final double DEFAULT_HEIGHT = 0.35D;
        public static final double MAX_SIZE = 64.0D;
        public static final Hitbox DEFAULTS = new Hitbox(null, null, null);

        public Hitbox {
            width = size(width, DEFAULT_WIDTH, "width");
            height = size(height, DEFAULT_HEIGHT, "height");
            perLine = perLine != null && perLine;
        }

        private static double size(Double value, double fallback, String name) {
            if (value == null) {
                return fallback;
            }
            if (!Double.isFinite(value) || value <= 0.0D || value > MAX_SIZE) {
                throw new IllegalArgumentException("hologram hitbox " + name + " must be within 0.." + MAX_SIZE);
            }
            return value;
        }
    }

    public HologramDoc {
        DocumentEnvelope.requireSchemaVersion(KIND, schemaVersion, CURRENT_SCHEMA_VERSION);
        DocumentEnvelope.requireRevision(KIND, revision);
        anchor = Objects.requireNonNull(anchor, "hologram requires an anchor");
        lines = copyLines(lines);
        pages = copyPages(pages);
        if (!lines.isEmpty() && !pages.isEmpty()) {
            throw new IllegalArgumentException("hologram declares both lines and pages; pages replace lines");
        }
        style = style == null ? IconDisplayStyle.hologramDefaults() : style;
        box = box == null ? HologramBox.defaults() : box;
        yaw = requireAngle("yaw", yaw, MAX_YAW_DEGREES);
        pitch = requireAngle("pitch", pitch, MAX_PITCH_DEGREES);
        particleLayers = ParticleLayer.copyLayers(particleLayers, "hologram");
        show = show == null ? ShowCondition.ALWAYS : show;
        actions = copyActions(actions);
        motion = motion == null || motion.isBlank() ? null : motion.trim();
    }

    /** The text-only form used by the importer and by holograms created from commands. */
    public HologramDoc(int schemaVersion, long revision, Anchor anchor, List<String> lines,
                       IconDisplayStyle style, HologramBox box, Double yaw, Double pitch,
                       List<ParticleLayer> particleLayers, ShowCondition show) {
        this(schemaVersion, revision, anchor, textLines(lines), style, box, yaw, pitch, particleLayers, show,
            List.of(), List.of(), null, null);
    }

    public HologramDoc withRevision(long revision) {
        return new HologramDoc(schemaVersion, revision, anchor, lines, style, box, yaw, pitch,
            particleLayers, show, pages, actions, hitbox, motion);
    }

    public static HologramDoc parse(String fileName, String raw) {
        return DocumentParsers.parseJson(fileName, raw, HologramDoc.class);
    }

    /** The authored text of every text line, in order; object lines are skipped. */
    public List<String> textLines() {
        return textOf(lines);
    }

    /** The lines a viewer on {@code pageId} reads; the declared lines when the hologram is not paged. */
    public List<HologramLine> linesFor(String pageId) {
        if (pages.isEmpty()) {
            return lines;
        }
        for (HologramPage page : pages) {
            if (page.id().equals(pageId)) {
                return page.lines();
            }
        }
        return pages.getFirst().lines();
    }

    public boolean isPaged() {
        return !pages.isEmpty();
    }

    public boolean hasMotion() {
        return motion != null;
    }

    public Hitbox hitboxOrDefault() {
        return hitbox == null ? Hitbox.DEFAULTS : hitbox;
    }

    static List<String> textOf(List<HologramLine> lines) {
        List<String> text = new ArrayList<>(lines.size());
        for (HologramLine line : lines) {
            if (line.isText()) {
                text.add(line.text());
            }
        }
        return List.copyOf(text);
    }

    static List<HologramLine> textLines(List<String> lines) {
        if (lines == null) {
            return List.of();
        }
        List<HologramLine> converted = new ArrayList<>(lines.size());
        for (String line : lines) {
            converted.add(HologramLine.text(line));
        }
        return List.copyOf(converted);
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

    private static List<HologramLine> copyLines(List<HologramLine> lines) {
        if (lines == null) {
            return List.of();
        }
        List<HologramLine> copied = new ArrayList<>(lines.size());
        for (HologramLine line : lines) {
            copied.add(line == null ? HologramLine.text("") : line);
        }
        return List.copyOf(copied);
    }

    private static List<HologramPage> copyPages(List<HologramPage> pages) {
        if (pages == null || pages.isEmpty()) {
            return List.of();
        }
        if (pages.size() > MAX_PAGES) {
            throw new IllegalArgumentException("hologram may declare at most " + MAX_PAGES + " pages");
        }
        Set<String> ids = new HashSet<>(pages.size() * 2);
        List<HologramPage> copied = new ArrayList<>(pages.size());
        for (HologramPage page : pages) {
            Objects.requireNonNull(page, "hologram pages must not contain null entries");
            if (!ids.add(page.id())) {
                throw new IllegalArgumentException("hologram page " + page.id() + " is declared twice");
            }
            copied.add(page);
        }
        return List.copyOf(copied);
    }

    private static List<MenuActionData> copyActions(List<MenuActionData> actions) {
        if (actions == null || actions.isEmpty()) {
            return List.of();
        }
        if (actions.size() > MAX_ACTIONS) {
            throw new IllegalArgumentException("hologram may declare at most " + MAX_ACTIONS + " actions");
        }
        List<MenuActionData> copied = new ArrayList<>(actions.size());
        for (MenuActionData action : actions) {
            copied.add(Objects.requireNonNull(action, "hologram actions must not contain null entries"));
        }
        return List.copyOf(copied);
    }
}
