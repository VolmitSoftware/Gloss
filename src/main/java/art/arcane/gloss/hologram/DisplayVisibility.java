package art.arcane.gloss.hologram;

import org.bukkit.entity.Entity;

import java.lang.reflect.Method;

public final class DisplayVisibility {
    private static final Method IS_VISIBLE_BY_DEFAULT = probe("isVisibleByDefault");
    private static final Method SET_VISIBLE_BY_DEFAULT = probe();

    private DisplayVisibility() {
    }

    public static boolean canHideByDefault() {
        return SET_VISIBLE_BY_DEFAULT != null;
    }

    public static boolean setVisibleByDefault(Entity entity, boolean visible) {
        if (entity == null || SET_VISIBLE_BY_DEFAULT == null) {
            return false;
        }

        try {
            SET_VISIBLE_BY_DEFAULT.invoke(entity, visible);
            return true;
        } catch (Throwable ignored) {
            return false;
        }
    }

    public static Boolean isVisibleByDefault(Entity entity) {
        if (entity == null || IS_VISIBLE_BY_DEFAULT == null) {
            return null;
        }

        try {
            Object visible = IS_VISIBLE_BY_DEFAULT.invoke(entity);
            return visible instanceof Boolean value ? value : null;
        } catch (Throwable ignored) {
            return null;
        }
    }

    private static Method probe() {
        try {
            return Entity.class.getMethod("setVisibleByDefault", boolean.class);
        } catch (Throwable ignored) {
            return null;
        }
    }

    private static Method probe(String name) {
        try {
            return Entity.class.getMethod(name);
        } catch (Throwable ignored) {
            return null;
        }
    }
}
