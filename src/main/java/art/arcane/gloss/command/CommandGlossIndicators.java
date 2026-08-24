package art.arcane.gloss.command;

import art.arcane.gloss.Gloss;
import art.arcane.gloss.indicator.DamageIndicatorSettingsDoc;
import art.arcane.volmlib.util.director.annotations.Director;
import art.arcane.volmlib.util.director.annotations.Param;
import org.bukkit.command.CommandSender;

@Director(name = "indicators", aliases = {"indicator"}, descriptionKey = "command.help.indicators",
    description = "Damage and healing indicator tools")
public final class CommandGlossIndicators {
    private final Gloss plugin;

    public CommandGlossIndicators(Gloss plugin) {
        this.plugin = plugin;
    }

    @Director(name = "reset", sync = true, descriptionKey = "command.help.indicators.reset",
        description = "Restore the shipped damage-indicator settings document")
    public void reset(@Param(name = "sender", contextual = true) CommandSender sender) {
        if (GlossCommandMessages.denied(sender, "gloss.indicators.reset")) {
            return;
        }
        GlossCommandMessages.sendResetResult(sender, "damage indicator",
            DamageIndicatorSettingsDoc.DEFAULT_ID,
            plugin.indicators().resetToDefault(DamageIndicatorSettingsDoc.DEFAULT_ID));
    }
}
