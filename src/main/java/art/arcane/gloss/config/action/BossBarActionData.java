package art.arcane.gloss.config.action;

import art.arcane.gloss.api.HoloClickTrigger;
import art.arcane.gloss.enums.MenuActionType;
import art.arcane.gloss.menu.action.BossBarMenuAction;
import art.arcane.gloss.menu.action.MenuAction;
import art.arcane.gloss.surface.SurfaceDoc;
import art.arcane.gloss.surface.SurfacePriorities;
import org.bukkit.boss.BarColor;
import org.bukkit.boss.BarStyle;

import java.util.Locale;

public record BossBarActionData(String id, String title, String progress, String color, String style,
                                Integer ticks, String priority, HoloClickTrigger trigger, String when,
                                Integer cooldownTicks) implements MenuActionData {
    public static final int DEFAULT_TICKS = 100;

    @Override
    public MenuActionType getType() {
        return MenuActionType.BOSSBAR;
    }

    public int ticksOrDefault() {
        return ticks == null ? DEFAULT_TICKS : Math.clamp(ticks.intValue(), 0, SurfaceDoc.MAX_TTL_TICKS);
    }

    public boolean hides() {
        return ticksOrDefault() == 0;
    }

    public BarColor barColor() {
        return color == null ? BarColor.WHITE : BarColor.valueOf(color.trim().toUpperCase(Locale.ROOT));
    }

    public BarStyle barStyle() {
        return style == null ? BarStyle.SOLID : BarStyle.valueOf(style.trim().toUpperCase(Locale.ROOT));
    }

    public double progressValue() {
        if (progress == null || progress.isBlank()) {
            return 1.0D;
        }
        try {
            return Math.clamp(Double.parseDouble(progress.trim()), 0.0D, 1.0D);
        } catch (NumberFormatException notLiteral) {
            return 1.0D;
        }
    }

    public int priorityValue() {
        return SurfacePriorities.of(priority == null ? "notice" : priority);
    }

    @Override
    public ActionEnvelope envelope() {
        return ActionEnvelope.of(when, cooldownTicks);
    }

    @Override
    public MenuAction<?> createAction() {
        return new BossBarMenuAction(this);
    }

    @Override
    public String invalidReason() {
        if (id == null || id.isBlank()) {
            return "declares an empty boss bar id";
        }
        if (title == null || title.isBlank()) {
            return "declares an empty boss bar title";
        }
        if (!SurfaceDoc.COLORS.contains(colorName())) {
            return "declares an unknown boss bar color " + color;
        }
        if (!SurfaceDoc.STYLES.contains(styleName())) {
            return "declares an unknown boss bar style " + style;
        }
        return null;
    }

    private String colorName() {
        return color == null ? "white" : color.trim().toLowerCase(Locale.ROOT);
    }

    private String styleName() {
        return style == null ? "solid" : style.trim().toLowerCase(Locale.ROOT);
    }
}
