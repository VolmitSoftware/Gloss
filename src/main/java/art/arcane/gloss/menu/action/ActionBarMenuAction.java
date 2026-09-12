package art.arcane.gloss.menu.action;

import art.arcane.gloss.config.action.ActionBarActionData;
import art.arcane.gloss.surface.SurfaceDelivery;
import art.arcane.gloss.text.TextPipeline;
import org.bukkit.entity.Player;

public final class ActionBarMenuAction extends MenuAction<ActionBarActionData> {
    private static final long TICK_MILLIS = 50L;

    private final SurfaceDelivery injected;

    public ActionBarMenuAction(ActionBarActionData data) {
        this(data, null);
    }

    public ActionBarMenuAction(ActionBarActionData data, SurfaceDelivery delivery) {
        super(data);
        this.injected = delivery;
    }

    @Override
    public ActionOutcome execute(ActionContext context) {
        SurfaceDelivery delivery = SurfaceActionDelivery.resolve(injected);
        if (delivery == null) {
            return ActionOutcome.CONTINUE;
        }
        Player player = context.player();
        delivery.actionBar(player, SurfaceActionDelivery.purpose(context), data.priorityValue(),
            data.ticksOrDefault() * TICK_MILLIS, data.hudSlots(),
            TextPipeline.menuText(player, data.text()));
        return ActionOutcome.CONTINUE;
    }
}
