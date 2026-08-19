package art.arcane.gloss.command;

import art.arcane.gloss.Gloss;
import art.arcane.gloss.locale.GlossMessages;
import art.arcane.volmlib.util.collection.KList;
import art.arcane.volmlib.util.director.DirectorOrigin;
import art.arcane.volmlib.util.director.DirectorParameterHandler;
import art.arcane.volmlib.util.director.annotations.Director;
import art.arcane.volmlib.util.director.annotations.Param;
import art.arcane.volmlib.util.director.exceptions.DirectorParsingException;
import art.arcane.volmlib.util.localization.MessageArgument;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;

@Director(name = "bubbles", aliases = {"bubble"}, descriptionKey = "command.help.bubbles", description = "Chat bubble styles")
public class CommandGlossBubbles {
    private static final String CLEAR_TOKEN = "clear";

    private final Gloss plugin;

    public CommandGlossBubbles(Gloss plugin) {
        this.plugin = plugin;
    }

    @Director(name = "style", sync = true, origin = DirectorOrigin.PLAYER, descriptionKey = "command.help.bubbles.style", description = "Choose your chat bubble style, or clear for automatic selection")
    public void style(@Param(name = "player", contextual = true) Player player,
                      @Param(name = "style", descriptionKey = "command.help.bubbles.style.style", description = "Style id, or clear", customHandler = StyleIdHandler.class) String style) {
        if (GlossCommandMessages.denied(player, "gloss.bubbles.style")) {
            return;
        }

        if (CLEAR_TOKEN.equalsIgnoreCase(style)) {
            plugin.bubbles().setPlayerStyle(player.getUniqueId(), null);
            GlossCommandMessages.send(player, GlossMessages.BUBBLES_STYLE_CLEARED);
            return;
        }

        if (!plugin.bubbles().setPlayerStyle(player.getUniqueId(), style)) {
            GlossCommandMessages.send(player, GlossMessages.BUBBLES_STYLE_UNKNOWN,
                    MessageArgument.untrusted("style", style));
            return;
        }

        GlossCommandMessages.send(player, GlossMessages.BUBBLES_STYLE_SET,
                MessageArgument.untrusted("style", style));
    }

    @Director(name = "reset", sync = true, descriptionKey = "command.help.bubbles.reset", description = "Restore shipped bubble style defaults")
    public void reset(@Param(name = "sender", contextual = true) CommandSender sender,
                      @Param(name = "name", defaultValue = "*", descriptionKey = "command.help.arg.reset_name", description = "Name to reset, or * for every shipped default") String name) {
        if (GlossCommandMessages.denied(sender, "gloss.bubbles.reset")) {
            return;
        }
        GlossCommandMessages.sendResetResult(sender, "bubble style", name, plugin.bubbles().resetToDefault(name));
    }

    public static final class StyleIdHandler implements DirectorParameterHandler<String> {
        @Override
        public KList<String> getPossibilities() {
            KList<String> out = new KList<>();
            out.add(CLEAR_TOKEN);
            Gloss plugin = Gloss.instance;
            if (plugin != null && plugin.bubbles() != null) {
                out.addAll(plugin.bubbles().styles());
            }
            return out;
        }

        @Override
        public String toString(String value) {
            return value == null ? "" : value;
        }

        @Override
        public String parse(String in, boolean force) throws DirectorParsingException {
            if (in == null || in.isBlank()) {
                throw new DirectorParsingException("style is required");
            }
            return in.strip();
        }

        @Override
        public boolean supports(Class<?> type) {
            return type == String.class;
        }
    }
}
