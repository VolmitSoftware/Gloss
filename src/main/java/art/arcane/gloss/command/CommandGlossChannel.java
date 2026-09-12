package art.arcane.gloss.command;

import art.arcane.gloss.Gloss;
import art.arcane.gloss.chat.ChannelDoc;
import art.arcane.gloss.chat.ChannelRuntime;
import art.arcane.gloss.chat.ChannelService;
import art.arcane.gloss.locale.GlossLocalization;
import art.arcane.gloss.locale.GlossMessages;
import art.arcane.volmlib.util.collection.KList;
import art.arcane.volmlib.util.director.DirectorParameterHandler;
import art.arcane.volmlib.util.director.annotations.Director;
import art.arcane.volmlib.util.director.annotations.Param;
import art.arcane.volmlib.util.director.exceptions.DirectorParsingException;
import art.arcane.volmlib.util.director.help.DirectorMiniMenu;
import art.arcane.volmlib.util.localization.MessageArgument;
import org.bukkit.command.CommandSender;

import java.util.ArrayList;
import java.util.List;

/** {@code /gloss channel} — inspects and restores the {@code channels/} documents. */
@Director(name = "channel", aliases = {"channels"}, description = "Chat channels",
    descriptionKey = "command.help.channel.root")
public class CommandGlossChannel {
    private static final String PERMISSION = "gloss.channels";
    private static final String RESET_PERMISSION = "gloss.channels.reset";

    private final Gloss plugin;

    public CommandGlossChannel(Gloss plugin) {
        this.plugin = plugin;
    }

    @Director(name = "list", description = "List loaded chat channels",
        descriptionKey = "command.help.channel.list")
    public void list(@Param(name = "sender", contextual = true) CommandSender sender) {
        if (GlossCommandMessages.denied(sender, PERMISSION)) {
            return;
        }
        ChannelService channels = plugin.service(ChannelService.class);
        List<ChannelRuntime> loaded = channels == null ? List.of() : channels.channels();
        DirectorMiniMenu.Theme theme = GlossCommandService.menuTheme();
        List<String> lines = new ArrayList<>();
        lines.add(DirectorMiniMenu.banner(GlossLocalization.globalText(GlossMessages.CHANNELS_LIST_HEADER,
            GlossLocalization.args(MessageArgument.trusted("count", loaded.size()))), theme));
        if (loaded.isEmpty()) {
            lines.add(GlossLocalization.globalText(GlossMessages.CHANNELS_LIST_EMPTY));
        }
        for (ChannelRuntime channel : loaded) {
            lines.add("&7- &f" + channel.id() + " &7(" + channel.name() + ", "
                + channel.scope().name().toLowerCase(java.util.Locale.ROOT)
                + (channel.isDefault() ? ", default" : "") + ")");
        }
        lines.add(DirectorMiniMenu.bar(theme));
        DirectorMiniMenu.deliver(sender, lines);
    }

    @Director(name = "info", description = "Show a channel's scope, format and variants",
        descriptionKey = "command.help.channel.info")
    public void info(@Param(name = "id", description = "Channel document id",
                         descriptionKey = "command.help.channel.info.id",
                         customHandler = ChannelIdHandler.class) String id,
                     @Param(name = "sender", contextual = true) CommandSender sender) {
        if (GlossCommandMessages.denied(sender, PERMISSION)) {
            return;
        }
        ChannelService channels = plugin.service(ChannelService.class);
        ChannelRuntime channel = channels == null ? null : channels.channelFor(id);
        if (channel == null) {
            GlossLocalization.sendGlobal(sender, GlossMessages.CHANNEL_UNKNOWN,
                GlossLocalization.args(MessageArgument.untrusted("id", id)));
            return;
        }
        ChannelDoc doc = channel.doc();
        DirectorMiniMenu.Theme theme = GlossCommandService.menuTheme();
        List<String> lines = new ArrayList<>();
        lines.add(DirectorMiniMenu.banner(channel.id(), theme));
        lines.add("&7scope &f" + channel.scope().name().toLowerCase(java.util.Locale.ROOT)
            + (channel.scope() == ChannelDoc.Scope.RADIUS ? " &7radius &f" + doc.channel().radius() : ""));
        lines.add("&7aliases &f" + (channel.aliases().isEmpty() ? "-" : String.join(", ", channel.aliases())));
        lines.add("&7permission &f" + (doc.channel().permission().isEmpty() ? "-" : doc.channel().permission()));
        lines.add("&7format &f" + doc.format());
        lines.add("&7filters &f" + doc.filters().size() + " &7variants &f" + doc.variants().size());
        lines.add("&7throttle &f" + doc.throttle().minIntervalTicks() + "t &7repeats &f"
            + doc.throttle().maxRepeats() + " &7in &f" + doc.throttle().repeatWindowTicks() + "t");
        lines.add(DirectorMiniMenu.bar(theme));
        DirectorMiniMenu.deliver(sender, lines);
    }

    @Director(name = "reset", sync = true, description = "Restore shipped channel documents",
        descriptionKey = "command.help.channel.reset")
    public void reset(@Param(name = "sender", contextual = true) CommandSender sender,
                      @Param(name = "name", defaultValue = "*",
                          description = "Name to reset, or * for every shipped default",
                          descriptionKey = "command.help.arg.reset_name") String name) {
        if (GlossCommandMessages.denied(sender, RESET_PERMISSION)) {
            return;
        }
        ChannelService channels = plugin.service(ChannelService.class);
        GlossCommandMessages.sendResetResult(sender, "channel", name,
            channels == null ? List.of() : channels.resetToDefault(name));
    }

    public static final class ChannelIdHandler implements DirectorParameterHandler<String> {
        @Override
        public KList<String> getPossibilities() {
            KList<String> ids = new KList<>();
            Gloss plugin = Gloss.instance;
            ChannelService channels = plugin == null ? null : plugin.service(ChannelService.class);
            if (channels != null) {
                for (ChannelRuntime channel : channels.channels()) {
                    ids.add(channel.id());
                }
            }
            return ids;
        }

        @Override
        public String toString(String value) {
            return value == null ? "" : value;
        }

        @Override
        public String parse(String in, boolean force) throws DirectorParsingException {
            if (in == null || in.isBlank()) {
                throw new DirectorParsingException("channel id is required");
            }
            return in.strip();
        }

        @Override
        public boolean supports(Class<?> type) {
            return type == String.class;
        }
    }
}
