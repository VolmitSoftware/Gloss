package art.arcane.gloss.interaction;

import art.arcane.gloss.api.HoloClickTrigger;
import art.arcane.gloss.menu.action.ActionContext;
import art.arcane.gloss.menu.action.MenuAction;
import org.bukkit.Location;
import org.bukkit.entity.Player;

import java.util.List;
import java.util.Objects;
import java.util.function.BiFunction;
import java.util.function.BooleanSupplier;
import java.util.function.Supplier;

public record InteractionTarget(
    String owner,
    String id,
    Supplier<Location> position,
    float width,
    float height,
    List<MenuAction<?>> actions,
    BiFunction<Player, HoloClickTrigger, ActionContext> context,
    BooleanSupplier live
) {
    public static final float MAX_SIZE = 64.0F;

    public InteractionTarget {
        owner = Objects.requireNonNull(owner, "owner");
        id = Objects.requireNonNull(id, "id");
        position = Objects.requireNonNull(position, "position");
        if (!Float.isFinite(width) || !Float.isFinite(height) || width <= 0.0F || height <= 0.0F
            || width > MAX_SIZE || height > MAX_SIZE) {
            throw new IllegalArgumentException("interaction hitbox size must be within 0.." + MAX_SIZE);
        }
        actions = List.copyOf(actions);
        context = Objects.requireNonNull(context, "context");
        live = Objects.requireNonNull(live, "live");
    }
}
