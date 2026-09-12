package art.arcane.gloss.command;

import art.arcane.gloss.locale.GlossMessages;
import art.arcane.volmlib.util.director.annotations.Director;
import art.arcane.volmlib.util.director.annotations.Param;
import art.arcane.volmlib.util.hud.HudActionBar;
import art.arcane.volmlib.util.hud.HudBid;
import art.arcane.volmlib.util.hud.HudBossBarLane;
import art.arcane.volmlib.util.hud.HudSegmentCodec;
import art.arcane.volmlib.util.hud.HudStampedSegment;
import art.arcane.volmlib.util.hud.HudTitleService;
import art.arcane.volmlib.util.localization.MessageArgument;
import org.bukkit.Bukkit;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;
import org.bukkit.metadata.MetadataValue;

import java.util.ArrayList;
import java.util.List;

/**
 * Reads the shared HUD ledgers a viewer carries in metadata so an operator can see which plugin
 * owns each screen slot, at what priority, and for how long.
 */
@Director(name = "hud", descriptionKey = "command.help.hud.root", description = "Inspect the shared HUD compositor")
public class CommandGlossHud {
    private static final String BOSS_BAR_INDEX_KEY = HudBossBarLane.METADATA_KEY + "|index";

    @Director(name = "who", descriptionKey = "command.help.hud.who",
        description = "Show which plugin owns each HUD slot for a player")
    public void who(@Param(name = "sender", contextual = true) CommandSender sender,
                    @Param(name = "player", defaultValue = "", descriptionKey = "command.help.hud.who.player",
                        description = "Online player to inspect; defaults to you") String player) {
        if (GlossCommandMessages.denied(sender, "gloss.hud.who")) {
            return;
        }
        Player target = player == null || player.isBlank()
            ? (sender instanceof Player self ? self : null) : Bukkit.getPlayerExact(player);
        if (target == null) {
            GlossCommandMessages.send(sender, GlossMessages.SCREEN_PLAYER_UNKNOWN,
                MessageArgument.untrusted("player", player));
            return;
        }
        long now = System.currentTimeMillis();
        List<Claim> claims = new ArrayList<>();
        collectSegments(target, claims);
        collectTitle(target, claims);
        collectBossBars(target, claims);
        if (claims.isEmpty()) {
            GlossCommandMessages.send(sender, GlossMessages.SCREEN_HUD_EMPTY,
                MessageArgument.untrusted("player", target.getName()));
            return;
        }
        GlossCommandMessages.send(sender, GlossMessages.SCREEN_HUD_HEADER,
            MessageArgument.untrusted("player", target.getName()),
            MessageArgument.trusted("count", claims.size()));
        for (Claim claim : claims) {
            GlossCommandMessages.send(sender, GlossMessages.SCREEN_HUD_LINE,
                MessageArgument.untrusted("kind", claim.kind()),
                MessageArgument.untrusted("source", claim.owner()),
                MessageArgument.untrusted("value", claim.purpose()),
                MessageArgument.trusted("count", claim.priority()),
                MessageArgument.untrusted("state", age(now - claim.sinceMillis())));
        }
    }

    private static void collectSegments(Player target, List<Claim> claims) {
        for (MetadataValue value : target.getMetadata(HudActionBar.METADATA_KEY)) {
            if (value.getOwningPlugin() == null) {
                continue;
            }
            for (HudStampedSegment segment : HudSegmentCodec.decode(value.asString())) {
                claims.add(new Claim("actionbar", value.getOwningPlugin().getName(), segment.purpose(),
                    segment.priority(), segment.sinceMillis()));
            }
        }
    }

    private static void collectTitle(Player target, List<Claim> claims) {
        for (MetadataValue value : target.getMetadata(HudTitleService.METADATA_KEY)) {
            HudBid bid = HudBid.decode(value.asString());
            if (bid == null || value.getOwningPlugin() == null) {
                continue;
            }
            claims.add(new Claim("title", value.getOwningPlugin().getName(), bid.purpose(), bid.priority(),
                bid.sinceMillis()));
        }
    }

    private static void collectBossBars(Player target, List<Claim> claims) {
        for (MetadataValue index : target.getMetadata(BOSS_BAR_INDEX_KEY)) {
            if (index.getOwningPlugin() == null) {
                continue;
            }
            for (String laneId : index.asString().split(",")) {
                if (laneId.isEmpty()) {
                    continue;
                }
                for (MetadataValue value : target.getMetadata(HudBossBarLane.METADATA_KEY + "|" + laneId)) {
                    HudBid bid = HudBid.decode(value.asString());
                    if (bid == null || !index.getOwningPlugin().equals(value.getOwningPlugin())) {
                        continue;
                    }
                    claims.add(new Claim("bossbar", value.getOwningPlugin().getName(), bid.purpose(),
                        bid.priority(), bid.sinceMillis()));
                }
            }
        }
    }

    private static String age(long millis) {
        long seconds = Math.max(0L, millis / 1000L);
        return seconds < 60L ? seconds + "s" : seconds / 60L + "m" + seconds % 60L + "s";
    }

    private record Claim(String kind, String owner, String purpose, int priority, long sinceMillis) {
    }
}
