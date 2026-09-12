package art.arcane.gloss.command;

import art.arcane.gloss.Gloss;
import art.arcane.gloss.behavior.BehaviorService;
import art.arcane.gloss.behavior.TriggerEvent;
import art.arcane.gloss.locale.GlossMessages;
import art.arcane.volmlib.util.localization.MessageArgument;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;

import java.util.Map;

/**
 * {@code /gloss do}: the operator-facing half of the {@code command} trigger. The permission is on
 * by default so a document can gate its own entry with the entry's {@code permission} field instead
 * of forcing every server to hand out a second node.
 */
public final class CommandGlossDo {
    private final Gloss plugin;

    public CommandGlossDo(Gloss plugin) {
        this.plugin = plugin;
    }

    public void fire(CommandSender sender, String name, String arguments) {
        if (GlossCommandMessages.denied(sender, "gloss.behaviors.do")) {
            return;
        }
        BehaviorService service = plugin == null ? null : plugin.service(BehaviorService.class);
        if (service == null || !service.enabled()) {
            GlossCommandMessages.send(sender, GlossMessages.BEHAVIORS_OFF);
            return;
        }
        Map<String, Object> parsed = TriggerEvent.parseArguments("*".equals(arguments) ? null : arguments);
        int matched = service.fireCommand(sender instanceof Player player ? player : null, name, parsed);
        if (matched == 0) {
            GlossCommandMessages.send(sender, GlossMessages.BEHAVIORS_DO_NONE,
                MessageArgument.untrusted("name", name));
            return;
        }
        GlossCommandMessages.send(sender, GlossMessages.BEHAVIORS_DO_RAN,
            MessageArgument.trusted("count", matched),
            MessageArgument.untrusted("name", name));
    }
}
