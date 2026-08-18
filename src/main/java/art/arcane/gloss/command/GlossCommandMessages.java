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
        sender.sendMessage(GlossLocalization.english().legacy(key));
    }

    static void send(CommandSender sender, TextKey key, MessageArgument... arguments) {
        sender.sendMessage(GlossLocalization.english().legacy(key, GlossLocalization.args(arguments)));
    }
}
