package art.arcane.gloss.chat;

import art.arcane.gloss.Gloss;
import art.arcane.gloss.locale.GlossLocalization;
import art.arcane.gloss.locale.GlossMessages;
import art.arcane.volmlib.util.localization.MessageArgument;
import art.arcane.volmlib.util.localization.TextKey;
import org.bukkit.command.Command;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabExecutor;
import org.bukkit.entity.Player;
import org.jetbrains.annotations.NotNull;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.UUID;

/**
 * The three chat commands players type constantly. They are plain Bukkit commands rather than
 * Director nodes because Director's keyed-argument grammar is wrong for free text typed at speed.
 */
public final class ChatCommands implements TabExecutor {
    public static final String MESSAGE_COMMAND = "msg";
    public static final String REPLY_COMMAND = "r";
    public static final String CHANNEL_COMMAND = "ch";
    public static final List<String> COMMANDS = List.of(MESSAGE_COMMAND, REPLY_COMMAND, CHANNEL_COMMAND);

    public static final String MESSAGE_PERMISSION = "gloss.chat.msg";
    public static final String CHANNEL_PERMISSION = "gloss.chat.channel";
    private static final String LIST_TOKEN = "list";

    private final Gloss plugin;
    private final ChannelService channels;

    public ChatCommands(Gloss plugin, ChannelService channels) {
        this.plugin = plugin;
        this.channels = channels;
    }

    @Override
    public boolean onCommand(@NotNull CommandSender sender, @NotNull Command command,
                             @NotNull String label, @NotNull String[] args) {
        execute(sender, command.getName(), args);
        return true;
    }

    @Override
    public List<String> onTabComplete(@NotNull CommandSender sender, @NotNull Command command,
                                      @NotNull String label, @NotNull String[] args) {
        return complete(sender, command.getName(), args);
    }

    public void execute(CommandSender sender, String name, String[] args) {
        if (!(sender instanceof Player player)) {
            send(sender, GlossMessages.CHAT_MSG_USAGE, MessageArgument.untrusted("command", "/" + name));
            return;
        }
        switch (name.toLowerCase(Locale.ROOT)) {
            case MESSAGE_COMMAND -> message(player, args);
            case REPLY_COMMAND -> reply(player, args);
            case CHANNEL_COMMAND -> channel(player, args);
            default -> send(player, GlossMessages.CHAT_MSG_USAGE,
                MessageArgument.untrusted("command", "/" + name));
        }
    }

    public List<String> complete(CommandSender sender, String name, String[] args) {
        if (!(sender instanceof Player player)) {
            return List.of();
        }
        String prefix = args.length == 0 ? "" : args[args.length - 1].toLowerCase(Locale.ROOT);
        if (name.equalsIgnoreCase(MESSAGE_COMMAND) && args.length <= 1
            && player.hasPermission(MESSAGE_PERMISSION)) {
            return sorted(visibleNames(player), prefix);
        }
        if (name.equalsIgnoreCase(CHANNEL_COMMAND) && args.length <= 1
            && player.hasPermission(CHANNEL_PERMISSION)) {
            List<String> options = new ArrayList<>(selectableNames(player));
            options.add(LIST_TOKEN);
            return sorted(options, prefix);
        }
        return List.of();
    }

    /** The node a command is gated on, so a registration can ask the same question execute asks. */
    public static String permissionFor(String commandName) {
        return CHANNEL_COMMAND.equalsIgnoreCase(commandName) ? CHANNEL_PERMISSION : MESSAGE_PERMISSION;
    }

    private void message(Player sender, String[] args) {
        if (denied(sender, MESSAGE_PERMISSION)) {
            return;
        }
        if (args.length < 2) {
            send(sender, GlossMessages.CHAT_MSG_USAGE,
                MessageArgument.untrusted("command", "/msg <player> <message>"));
            return;
        }
        Player target = plugin.getServer().getPlayerExact(args[0]);
        if (target == null || !sender.canSee(target)) {
            send(sender, GlossMessages.CHAT_MSG_OFFLINE, MessageArgument.untrusted("player", args[0]));
            return;
        }
        deliver(sender, target, join(args, 1));
    }

    private void reply(Player sender, String[] args) {
        if (denied(sender, MESSAGE_PERMISSION)) {
            return;
        }
        if (args.length < 1) {
            send(sender, GlossMessages.CHAT_MSG_USAGE,
                MessageArgument.untrusted("command", "/r <message>"));
            return;
        }
        UUID partnerId = channels.state().partnerOf(sender.getUniqueId());
        Player partner = partnerId == null ? null : plugin.getServer().getPlayer(partnerId);
        if (partner == null || !sender.canSee(partner)) {
            send(sender, GlossMessages.CHAT_REPLY_NONE);
            return;
        }
        deliver(sender, partner, join(args, 0));
    }

    private void deliver(Player sender, Player target, String text) {
        if (sender.getUniqueId().equals(target.getUniqueId())) {
            send(sender, GlossMessages.CHAT_MSG_SELF);
            return;
        }
        switch (channels.sendPrivate(sender, target, text)) {
            case SENT -> {
            }
            case DENIED -> send(sender, GlossMessages.COMMAND_NO_PERMISSION);
            case TOO_FAST -> send(sender, GlossMessages.CHAT_TOO_FAST);
            case REPEAT -> send(sender, GlossMessages.CHAT_REPEAT);
            default -> send(sender, GlossMessages.CHAT_FILTERED);
        }
    }

    private void channel(Player player, String[] args) {
        if (denied(player, CHANNEL_PERMISSION)) {
            return;
        }
        if (args.length == 0 || args[0].equalsIgnoreCase(LIST_TOKEN)) {
            send(player, GlossMessages.CHAT_CHANNEL_LIST,
                MessageArgument.untrusted("value", String.join(", ", selectableNames(player))));
            return;
        }
        if (!isSelectable(player, channels.channelFor(args[0])) || !channels.selectChannel(player, args[0])) {
            send(player, GlossMessages.CHAT_CHANNEL_UNKNOWN, MessageArgument.untrusted("name", args[0]));
            return;
        }
        send(player, GlossMessages.CHAT_CHANNEL_SET,
            MessageArgument.untrusted("name", channels.activeChannel(player).name()));
    }

    /** Direct channels carry {@code /msg}; they are never something a player switches into. */
    private boolean isSelectable(Player player, ChannelRuntime channel) {
        return channel != null && channel.scope() != ChannelDoc.Scope.DIRECT
            && channels.mayUse(player, channel);
    }

    private List<String> selectableNames(Player player) {
        List<String> names = new ArrayList<>();
        for (ChannelRuntime channel : channels.channels()) {
            if (isSelectable(player, channel)) {
                names.add(channel.name());
            }
        }
        names.sort(String::compareTo);
        return names;
    }

    private List<String> visibleNames(Player viewer) {
        List<String> names = new ArrayList<>();
        for (Player online : plugin.getServer().getOnlinePlayers()) {
            if (viewer.canSee(online)) {
                names.add(online.getName());
            }
        }
        return names;
    }

    private static List<String> sorted(List<String> values, String prefix) {
        List<String> matches = new ArrayList<>(values.size());
        for (String value : values) {
            if (value.toLowerCase(Locale.ROOT).startsWith(prefix)) {
                matches.add(value);
            }
        }
        matches.sort(String::compareTo);
        return List.copyOf(matches);
    }

    private static String join(String[] args, int from) {
        return String.join(" ", java.util.Arrays.copyOfRange(args, from, args.length));
    }

    private boolean denied(Player player, String permission) {
        if (player.hasPermission(permission)) {
            return false;
        }
        send(player, GlossMessages.COMMAND_NO_PERMISSION);
        return true;
    }

    private static void send(CommandSender sender, TextKey key, MessageArgument... arguments) {
        GlossLocalization.sendGlobal(sender, key, GlossLocalization.args(arguments));
    }
}
