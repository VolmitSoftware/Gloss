package art.arcane.gloss.menu.action;

import art.arcane.gloss.Gloss;
import art.arcane.gloss.config.action.GlowActionData;
import art.arcane.gloss.glow.GlowService;
import org.bukkit.Bukkit;
import org.bukkit.entity.Entity;
import java.util.UUID;

public final class GlowMenuAction extends MenuAction<GlowActionData> {
    public GlowMenuAction(GlowActionData data) {
        super(data);
    }

    @Override
    public ActionOutcome execute(ActionContext context) {
        GlowService glow = Gloss.instance == null ? null : Gloss.instance.service(GlowService.class);
        if (glow == null || !glow.enabled()) {
            return ActionOutcome.CONTINUE;
        }
        Entity target = resolve(context);
        if (target != null) {
            glow.tag(context.player(), target, data.color(), data.purposeOrDefault(),
                data.priorityOrDefault(), data.ticksOrDefault());
        }
        return ActionOutcome.CONTINUE;
    }

    /** A menu only ever knows the clicking player, so both roles resolve to them. */
    private Entity resolve(ActionContext context) {
        UUID id = data.targetId();
        return id == null ? context.player() : Bukkit.getEntity(id);
    }
}
