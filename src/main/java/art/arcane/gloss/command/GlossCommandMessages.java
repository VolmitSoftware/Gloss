package art.arcane.gloss.command;

import art.arcane.gloss.locale.GlossLocalization;
import art.arcane.gloss.locale.GlossMessages;
import art.arcane.volmlib.util.director.compat.BukkitDirectorContext;
import art.arcane.volmlib.util.localization.MessageArgument;
import art.arcane.volmlib.util.localization.TextKey;
import org.bukkit.command.CommandSender;

final class GlossCommandMessages {
    private GlossCommandMessages() {
    }

    static boolean denied(CommandSender sender, String permission) {
        if (BukkitDirectorContext.hasPermission(permission)) {
            return false;
        }
        send(sender, GlossMessages.COMMAND_NO_PERMISSION);
        return true;
    }

    static void send(CommandSender sender, TextKey key) {
        GlossLocalization.sendGlobal(sender, key);
    }

    static void send(CommandSender sender, TextKey key, MessageArgument... arguments) {
        GlossLocalization.sendGlobal(sender, key, GlossLocalization.args(arguments));
    }

    static void sendResetResult(CommandSender sender, String kind, String name, java.util.List<String> affected) {
        if (affected.isEmpty()) {
            send(sender, GlossMessages.RESET_NONE,
                    MessageArgument.untrusted("kind", kind),
                    MessageArgument.untrusted("name", name));
            return;
        }
        send(sender, GlossMessages.RESET_DONE,
                MessageArgument.trusted("count", affected.size()),
                MessageArgument.untrusted("kind", kind));
    }
}
