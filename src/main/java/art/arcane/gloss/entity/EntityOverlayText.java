package art.arcane.gloss.entity;

import org.bukkit.ChatColor;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.List;

public final class EntityOverlayText {
    private EntityOverlayText() {
    }

    public static List<String> render(EntityOverlayDoc settings, Snapshot entity, List<String> insight) {
        List<String> lines = new ArrayList<>(5 + insight.size());
        String stack = entity.stackCount() > 1
            ? format(settings.stackFormat(), settings, entity) : "";
        boolean named = settings.showNames() && entity.name() != null && !entity.name().isBlank();
        if (named) {
            lines.add(format(settings.nameFormat(), settings, entity) + stack);
        }
        String health = settings.showHealthNumbers()
            ? format(settings.healthFormat(), settings, entity) : bar(settings, entity);
        lines.add(health + (named ? "" : stack));
        if (entity.damage() > 0 && !settings.damageFormat().isBlank()) {
            lines.add(format(settings.damageFormat(), settings, entity));
        }
        lines.addAll(insight);
        if (settings.showCombatStats() && !settings.statsFormat().isBlank()) {
            lines.add(format(settings.statsFormat(), settings, entity));
        }
        return lines.stream().map(line -> ChatColor.translateAlternateColorCodes('&', line)).toList();
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

    private static String format(String source, EntityOverlayDoc settings, Snapshot entity) {
        return source.replace("{bar}", bar(settings, entity))
            .replace("{health}", number(entity.health()))
            .replace("{max_health}", number(entity.maxHealth()))
            .replace("{count}", Integer.toString(entity.stackCount()))
            .replace("{attack}", number(entity.attack()))
            .replace("{armor}", number(entity.armor()))
            .replace("{damage}", number(entity.damage()))
            .replace("{name}", entity.name() == null ? "" : entity.name());
    }

    private static String number(double value) {
        return BigDecimal.valueOf(Double.isFinite(value) ? Math.max(0, value) : 0)
            .setScale(1, RoundingMode.HALF_UP).stripTrailingZeros().toPlainString();
    }

    public record Snapshot(String name, double health, double maxHealth, double previousHealth,
                           double damage, double attack, double armor, int stackCount) {
    }
}
