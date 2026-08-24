package art.arcane.gloss.doc;

import art.arcane.gloss.Gloss;
import art.arcane.gloss.command.GlossCommandService;
import art.arcane.gloss.locale.GlossLocalization;
import art.arcane.gloss.locale.GlossMessages;
import art.arcane.volmlib.util.director.theme.DirectorProduct;
import art.arcane.volmlib.util.director.theme.DirectorTheme;
import art.arcane.volmlib.util.director.theme.DirectorThemes;
import art.arcane.volmlib.util.hud.HudActionBar;
import art.arcane.volmlib.util.hud.HudPriority;
import art.arcane.volmlib.util.hud.HudSegment;
import art.arcane.volmlib.util.hud.HudSlot;
import art.arcane.volmlib.util.localization.MessageArgs;
import art.arcane.volmlib.util.scheduling.SchedulerUtils;
import org.bukkit.Bukkit;
import org.bukkit.SoundCategory;
import org.bukkit.entity.Player;

import java.util.List;

final class HotloadFeedback {
    private static final String ADMIN_PERMISSION = "gloss.admin";
    private static final String RELOAD_PURPOSE = "gloss:hotload";
    private static final long RELOAD_TTL_MILLIS = 2500L;
    private static final DirectorTheme THEME = DirectorThemes.forProduct(DirectorProduct.GLOSS);

    private final Gloss plugin;

    HotloadFeedback(Gloss plugin) {
        this.plugin = plugin;
    }

    void deliver(HotloadBatch.Snapshot snapshot) {
        if (plugin == null || snapshot.isEmpty()) {
            return;
        }
        String kinds = String.join(", ", snapshot.changesByKind().keySet());
        for (Player player : Bukkit.getOnlinePlayers()) {
            SchedulerUtils.runEntity(plugin, player, () -> deliver(player, kinds, snapshot.totalChanges()));
        }
    }

    private void deliver(Player player, String kinds, int changes) {
        if (!player.isOnline() || !player.hasPermission(ADMIN_PERMISSION)) {
            return;
        }
        GlossLocalization localization = plugin.getLocalization();
        HudActionBar hudBar = plugin.getHudBar();
        if (localization == null || hudBar == null) {
            return;
        }
        String notice = localization.legacy(
            changes == 1 ? GlossMessages.HOTLOAD_SINGULAR : GlossMessages.HOTLOAD_PLURAL,
            MessageArgs.builder().untrusted("kinds", kinds).untrusted("count", changes).build()
        );
        hudBar.publish(player, new HudSegment(
            RELOAD_PURPOSE,
            HudPriority.NOTICE,
            RELOAD_TTL_MILLIS,
            List.of(HudSlot.CENTER, HudSlot.RIGHT),
            notice
        ));
        if (GlossCommandService.commandSoundsEnabled()) {
            player.playSound(player.getLocation(), THEME.getSuccessSound(), SoundCategory.MASTER, 0.5F, 1.5F);
        }
    }

}
