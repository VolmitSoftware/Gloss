package art.arcane.gloss.entity;

import art.arcane.gloss.Gloss;
import art.arcane.gloss.condition.GlossConditionScope;
import art.arcane.gloss.expr.Expr;
import art.arcane.gloss.expr.ExprParser;
import art.arcane.gloss.expr.ExprScope;
import art.arcane.gloss.hologram.AnimationTemplate;
import art.arcane.gloss.hologram.HologramAnimator;
import art.arcane.gloss.hologram.TextFrameSource;
import art.arcane.gloss.particle.ParticleText;
import art.arcane.gloss.text.TextPipeline;
import art.arcane.gloss.util.common.TextUtils;
import art.arcane.volmlib.util.format.ColorFormatter;
import net.kyori.adventure.text.serializer.legacy.LegacyComponentSerializer;
import net.kyori.adventure.text.minimessage.tag.resolver.Placeholder;
import net.kyori.adventure.text.minimessage.tag.resolver.TagResolver;
import org.bukkit.entity.Player;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.UnaryOperator;

public final class EntityOverlayText {
    private static final LegacyComponentSerializer LEGACY = LegacyComponentSerializer.builder()
        .character(LegacyComponentSerializer.SECTION_CHAR).hexColors()
        .useUnusualXRepeatedCharacterHexFormat().build();

    private EntityOverlayText() {
    }

    public static Prepared prepare(Gloss plugin, Player viewer, EntityOverlayDoc settings,
                                   Snapshot entity, List<String> insight) {
        ExprScope standard = plugin.text().expressionScope(viewer);
        ExprScope conditions = GlossConditionScope.viewer(plugin, viewer);
        return prepare(plugin.text(), plugin.animator(), viewer, new ViewerScope(standard, conditions),
            settings, entity, insight);
    }

    static Prepared prepare(TextPipeline pipeline, HologramAnimator animator, Player viewer, ExprScope parent,
                            EntityOverlayDoc settings, Snapshot entity, List<String> insight) {
        ExprScope scope = new EntityScope(parent, entity, !insight.isEmpty());
        if (!settings.show().matches(scope)) {
            return Prepared.hidden();
        }
        LiteralValues literals = new LiteralValues();
        List<String> lines = new ArrayList<>(settings.lines().size() + insight.size());
        Map<String, String> tokens = tokens(settings, entity);
        for (EntityOverlayDoc.Line line : settings.lines()) {
            if (!line.show().matches(scope)) {
                continue;
            }
            if (line.type().equals("spacer")) {
                lines.add(" ");
            } else if (line.type().equals("insight")) {
                for (String detail : insight) {
                    lines.add(authored(line.text(), tokens, detail, literals));
                }
            } else {
                lines.add(authored(line.text(), tokens, "", literals));
            }
        }
        if (lines.isEmpty()) {
            return Prepared.hidden();
        }
        String marked = ParticleText.parse(TextUtils.joinLegacyLines(lines)).marked();
        UnaryOperator<String> render = source -> pipeline.renderScoped(viewer, source, scope, literals::add);
        AnimationTemplate animation = animator == null ? null
            : animator.compileTemplate(List.of(marked), render, false);
        String rendered = animation == null ? render.apply(marked) : null;
        TextFrameSource frames = animation == null ? ignored -> rendered : animation;
        return new Prepared(true, animation != null, frames, literals.snapshot());
    }

    public static boolean refreshRequired(EntityOverlayDoc settings) {
        if (settings.show().isDynamic() && external(ExprParser.parse(settings.show().expression()))) {
            return true;
        }
        for (EntityOverlayDoc.Line line : settings.lines()) {
            if (line.show().isDynamic() && external(ExprParser.parse(line.show().expression()))) {
                return true;
            }
            if (TextPipeline.viewerDependent(line.text())) {
                return true;
            }
        }
        return false;
    }

    private static boolean external(Expr expression) {
        return switch (expression) {
            case Expr.Var variable -> !variable.name().startsWith("entity.")
                && !variable.name().equals("insight.active");
            case Expr.Unary unary -> external(unary.operand());
            case Expr.Binary binary -> external(binary.left()) || external(binary.right());
            case Expr.Ternary ternary -> external(ternary.condition()) || external(ternary.ifTrue())
                || external(ternary.ifFalse());
            case Expr.Call ignored -> true;
            case Expr.ListLiteral list -> list.items().stream().anyMatch(EntityOverlayText::external);
            default -> false;
        };
    }

    static String bar(EntityOverlayDoc settings, Snapshot entity) {
        double fraction = entity.maxHealth() > 0 ? Math.clamp(entity.health() / entity.maxHealth(), 0, 1) : 0;
        int filled = (int) Math.ceil(fraction * settings.healthSegments());
        double priorFraction = entity.maxHealth() > 0
            ? Math.clamp(entity.previousHealth() / entity.maxHealth(), 0, 1) : fraction;
        int prior = Math.max(filled, (int) Math.ceil(priorFraction * settings.healthSegments()));
        String color = fraction >= 0.5 ? "&a" : fraction >= 0.25 ? "&e" : "&c";
        return color + "|".repeat(filled) + "&c" + "|".repeat(prior - filled)
            + "&8" + "|".repeat(settings.healthSegments() - prior);
    }

    private static Map<String, String> tokens(EntityOverlayDoc settings, Snapshot entity) {
        return Map.ofEntries(
            Map.entry("bar", bar(settings, entity)),
            Map.entry("health", number(entity.health())),
            Map.entry("max_health", number(entity.maxHealth())),
            Map.entry("count", Integer.toString(entity.stackCount())),
            Map.entry("attack", number(entity.attack())),
            Map.entry("armor", number(entity.armor())),
            Map.entry("damage", number(entity.damage())),
            Map.entry("name", entity.name()),
            Map.entry("type", entity.type()),
            Map.entry("distance", number(entity.distance())));
    }

    private static String authored(String source, Map<String, String> tokens, String insight, LiteralValues literals) {
        StringBuilder output = new StringBuilder(source.length());
        int cursor = 0;
        while (cursor < source.length()) {
            int open = source.indexOf('{', cursor);
            if (open < 0) {
                output.append(source, cursor, source.length());
                break;
            }
            output.append(source, cursor, open);
            if (source.startsWith("{{", open)) {
                int end = source.indexOf("}}", open + 2);
                int next = end < 0 ? source.length() : end + 2;
                output.append(source, open, next);
                cursor = next;
                continue;
            }
            int close = source.indexOf('}', open + 1);
            if (close < 0) {
                output.append(source, open, source.length());
                break;
            }
            String name = source.substring(open + 1, close);
            String value = name.equals("insight") ? insight : tokens.get(name);
            if (value == null) {
                output.append(source, open, close + 1);
            } else {
                output.append(literals.add(value));
            }
            cursor = close + 1;
        }
        return output.toString();
    }

    private static String number(double value) {
        return BigDecimal.valueOf(Double.isFinite(value) ? value : 0)
            .setScale(1, RoundingMode.HALF_UP).stripTrailingZeros().toPlainString();
    }

    public record Snapshot(String name, double health, double maxHealth, double previousHealth,
                           double damage, double attack, double armor, int stackCount,
                           String type, double distance) {
        public Snapshot {
            name = name == null ? "" : name;
            type = type == null ? "" : type;
        }
    }

    public static final class Prepared {
        private final boolean visible;
        private final boolean animated;
        private final TextFrameSource frames;
        private final Map<String, String> literals;
        private final TagResolver resolver;
        private String previousSource;
        private ParticleText.Rendered previousFrame;

        private Prepared(boolean visible, boolean animated, TextFrameSource frames, Map<String, String> literals) {
            this.visible = visible;
            this.animated = animated;
            this.frames = frames;
            this.literals = literals;
            TagResolver.Builder builder = TagResolver.builder();
            for (Map.Entry<String, String> literal : literals.entrySet()) {
                builder.resolver(Placeholder.component(tag(literal.getKey()), TextUtils.parseLegacy(literal.getValue())));
            }
            this.resolver = builder.build();
        }

        private static Prepared hidden() {
            return new Prepared(false, false, ignored -> "", Map.of());
        }

        public boolean visible() {
            return visible;
        }

        public boolean animated() {
            return animated;
        }

        public synchronized ParticleText.Rendered frame(long nowMs) {
            String source = frames.compose(nowMs);
            if (source.equals(previousSource)) {
                return previousFrame;
            }
            String authored = source;
            for (String marker : literals.keySet()) {
                authored = authored.replace(marker, "<" + tag(marker) + ">");
            }
            String rendered = LEGACY.serialize(TextUtils.parse(authored, resolver));
            ParticleText.Rendered frame = ParticleText.renderMarked(rendered, UnaryOperator.identity());
            previousSource = source;
            previousFrame = frame;
            return frame;
        }

        private static String tag(String marker) {
            return "gloss_value_" + marker.substring(1, marker.length() - 1);
        }

    }

    private static final class LiteralValues {
        private final Map<String, String> values = new LinkedHashMap<>();

        private String add(String value) {
            String marker = "\uE000" + values.size() + "\uE001";
            String sanitized = value.replace("\u0007", "").replace("\uE000", "").replace("\uE001", "");
            values.put(marker, TextUtils.scopeLegacyLines(ColorFormatter.translateColors(sanitized)));
            return marker;
        }

        private Map<String, String> snapshot() {
            return Map.copyOf(values);
        }
    }

    private record EntityScope(ExprScope parent, Snapshot entity, boolean insightActive) implements ExprScope {
        @Override
        public Object variable(String name) {
            return switch (name) {
                case "entity.name" -> entity.name();
                case "entity.named" -> !entity.name().isBlank();
                case "entity.type" -> entity.type();
                case "entity.health" -> entity.health();
                case "entity.maxHealth" -> entity.maxHealth();
                case "entity.healthPercent" -> entity.maxHealth() > 0
                    ? Math.clamp(entity.health() / entity.maxHealth() * 100, 0, 100) : 0.0D;
                case "entity.damage" -> entity.damage();
                case "entity.damaged" -> entity.damage() > 0;
                case "entity.attack" -> entity.attack();
                case "entity.armor" -> entity.armor();
                case "entity.stackCount" -> (double) entity.stackCount();
                case "entity.distance" -> entity.distance();
                case "insight.active" -> insightActive;
                default -> parent.variable(name);
            };
        }

        @Override
        public Object call(String name, List<Object> args) {
            return parent.call(name, args);
        }
    }

    private record ViewerScope(ExprScope standard, ExprScope conditions) implements ExprScope {
        @Override
        public Object variable(String name) {
            return conditions.variable(name);
        }

        @Override
        public Object call(String name, List<Object> args) {
            if ((name.equals("papi") || name.equals("papiNumber")) && args.size() < 3) {
                return standard.call(name, args);
            }
            return conditions.call(name, args);
        }
    }
}
