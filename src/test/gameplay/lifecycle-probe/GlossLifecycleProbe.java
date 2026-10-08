package art.arcane.gloss.acceptance;

import art.arcane.gloss.api.GlossAPI;
import org.bukkit.Bukkit;
import org.bukkit.command.Command;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;
import org.bukkit.plugin.java.JavaPlugin;

import java.util.logging.Level;

public final class GlossLifecycleProbe extends JavaPlugin {
    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] arguments) {
        if (!(sender instanceof Player player)) {
            return false;
        }
        try {
            if (arguments.length == 1 && arguments[0].equals("reopen")) {
                player.closeInventory();
                require(Bukkit.dispatchCommand(player, "gloss inventory open lifecycle"), "Immediate inventory reopen failed");
                player.sendMessage("GLOSS_LIFECYCLE_REOPEN_OK");
                return true;
            }
            GlossAPI api = Bukkit.getServicesManager().load(GlossAPI.class);
            require(api != null && api == GlossAPI.get(), "Public facade service registration is unavailable");
            require(api.getClass().getClassLoader() != getClass().getClassLoader(), "Facade did not cross plugin classloaders");
            require(api.menuIds() != null && api.holograms() != null, "Public collection facade returned null");
            require("LIFECYCLE_API_LINKED".equals(api.filter(player, "LIFECYCLE_API_LINKED")), "Public filter invocation failed");
            player.sendMessage("GLOSS_LIFECYCLE_API_OK");
        } catch (Throwable failure) {
            getLogger().log(Level.SEVERE, "Lifecycle API acceptance failed", failure);
            player.sendMessage("GLOSS_LIFECYCLE_API_FAIL " + failure.getClass().getSimpleName());
        }
        return true;
    }

    private static void require(boolean condition, String message) {
        if (!condition) {
            throw new IllegalStateException(message);
        }
    }
}
