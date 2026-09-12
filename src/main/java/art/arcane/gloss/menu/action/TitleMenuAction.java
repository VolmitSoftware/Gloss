package art.arcane.gloss.menu.action;

import art.arcane.gloss.config.action.TitleActionData;
import art.arcane.gloss.surface.SurfaceDelivery;
import art.arcane.gloss.text.TextPipeline;
import org.bukkit.entity.Player;

public final class TitleMenuAction extends MenuAction<TitleActionData> {
    private final SurfaceDelivery injected;

    public TitleMenuAction(TitleActionData data) {
        this(data, null);
    }

    public TitleMenuAction(TitleActionData data, SurfaceDelivery delivery) {
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
        delivery.title(player, SurfaceActionDelivery.purpose(context), data.priorityValue(),
            TextPipeline.menuText(player, data.title()),
            TextPipeline.menuText(player, data.subtitleOrEmpty()),
            data.fadeInTicksOrDefault(), data.stayTicksOrDefault(), data.fadeOutTicksOrDefault());
        return ActionOutcome.CONTINUE;
    }
}
