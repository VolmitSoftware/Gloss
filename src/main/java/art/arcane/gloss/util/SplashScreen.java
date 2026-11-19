package art.arcane.gloss.util;

import art.arcane.gloss.Gloss;
import art.arcane.gloss.editor.EditorSettings;
import art.arcane.volmlib.util.plugin.SplashScreenSupport;
import org.bukkit.Bukkit;
import net.md_5.bungee.api.ChatColor;

import java.util.logging.Level;

public final class SplashScreen {
    private static final String SUPPORTED_MC_VERSION = "26.1.2 - 26.3";
    private static final String[] ART = {
        " ██████╗ ██╗      ██████╗ ███████╗███████╗",
        "██╔════╝ ██║     ██╔═══██╗██╔════╝██╔════╝",
        "██║  ███╗██║     ██║   ██║███████╗███████╗",
        "██║   ██║██║     ██║   ██║╚════██║╚════██║",
        "╚██████╔╝███████╗╚██████╔╝███████║███████║",
        " ╚═════╝ ╚══════╝ ╚═════╝ ╚══════╝╚══════╝"
    };

    private SplashScreen() {
    }

    /** The editor the banner names: the configured {@code editorUrl}, else the hosted one. */
    static String editorAddress(String editorUrl) {
        return editorUrl == null || editorUrl.isBlank() ? EditorSettings.EDITOR_URL : editorUrl;
    }

    public static void print(Gloss plugin, boolean success, String editorUrl) {
        try {
            printSplash(plugin, success, editorAddress(editorUrl));
        } catch (RuntimeException failure) {
            Gloss.logExceptionStack(false, failure, "Splash screen failed to render.");
        }
    }

    private static void printSplash(Gloss plugin, boolean success, String editor) {
        ChatColor fill = ChatColor.of("#2a1245");
        ChatColor edge = ChatColor.of("#8a2be2");
        ChatColor meta = ChatColor.of("#9a86c9");
        ChatColor accent = ChatColor.of("#b47aff");
        ChatColor statusColor = success ? ChatColor.GREEN : ChatColor.RED;
        String status = success ? "READY" : "DEGRADED";
        String pluginVersion = plugin.getDescription().getVersion();
        String releaseTrain = SplashScreenSupport.releaseTrain(pluginVersion);
        String serverVersion = SplashScreenSupport.serverVersionWithoutMcSuffix(Bukkit.getVersion());
        String startupDate = SplashScreenSupport.startupDate();

        String[] column = {
            "",
            accent + "   Gloss, " + meta + "Server Polish & Display Suite " + ChatColor.LIGHT_PURPLE + "[" + releaseTrain + " RELEASE]",
            meta + "   Version: " + accent + pluginVersion,
            meta + "   By: " + accent + "Volmit Software (Arcane Arts)" + meta + " | Web Editor: " + accent + editor + meta + " | Startup: " + statusColor + status,
            meta + "   Server: " + accent + serverVersion + meta + " | MC Support: " + accent + SUPPORTED_MC_VERSION,
            meta + "   Java: " + accent + SplashScreenSupport.javaMajorVersion() + meta + " | Date: " + accent + startupDate
        };

        StringBuilder splash = new StringBuilder("\n");
        for (int row = 0; row < ART.length; row++) {
            splash.append(SplashScreenSupport.colorizeArt(ART[row], fill.toString(), edge.toString())).append(column[row]).append('\n');
        }

        Gloss.log(Level.INFO, splash.toString());
    }
}
