package art.arcane.gloss.menu.action;

import art.arcane.gloss.config.action.BossBarActionData;
import art.arcane.gloss.surface.SurfaceDelivery;
import art.arcane.gloss.text.TextPipeline;
import org.bukkit.entity.Player;

public final class BossBarMenuAction extends MenuAction<BossBarActionData> {
    private static final long TICK_MILLIS = 50L;

    private final SurfaceDelivery injected;

    public BossBarMenuAction(BossBarActionData data) {
        this(data, null);
    }

    public BossBarMenuAction(BossBarActionData data, SurfaceDelivery delivery) {
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
        String laneId = SurfaceActionDelivery.purpose(context) + "/" + data.id();
        if (data.hides()) {
            delivery.hideBossBar(player, laneId);
            return ActionOutcome.CONTINUE;
        }
        delivery.bossBar(player, laneId, data.priorityValue(),
            TextPipeline.menuText(player, data.title()), data.progressValue(), data.barColor(), data.barStyle(),
            data.ticksOrDefault() * TICK_MILLIS);
        return ActionOutcome.CONTINUE;
    }
}
