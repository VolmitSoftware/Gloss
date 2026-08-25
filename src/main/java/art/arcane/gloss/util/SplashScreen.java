package art.arcane.gloss.util;

import art.arcane.gloss.Gloss;
import art.arcane.volmlib.util.plugin.SplashScreenSupport;
import net.md_5.bungee.api.ChatColor;

import java.util.logging.Level;

public final class SplashScreen {
    private static final String SUPPORTED_MC_VERSION = "26.1.2 - 26.2";
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

    public static void print(Gloss plugin, boolean success) {
        try {
            printSplash(plugin, success);
        } catch (RuntimeException failure) {
            Gloss.logExceptionStack(false, failure, "Splash screen failed to render.");
        }
    }

    private static void printSplash(Gloss plugin, boolean success) {
        ChatColor fill = ChatColor.of("#2a1245");
        ChatColor edge = ChatColor.of("#8a2be2");
        ChatColor meta = ChatColor.of("#9a86c9");
        ChatColor accent = ChatColor.of("#b47aff");
        ChatColor statusColor = success ? ChatColor.GREEN : ChatColor.RED;
        String status = success ? "READY" : "DEGRADED";
        String pluginVersion = plugin.getDescription().getVersion();
        String releaseTrain = SplashScreenSupport.releaseTrain(pluginVersion);
        String serverVersion = SplashScreenSupport.serverVersionWithoutMcSuffix();
        String startupDate = SplashScreenSupport.startupDate();

        String[] column = {
            "",
            accent + "   Gloss, " + meta + "Server Polish & Display Suite " + ChatColor.LIGHT_PURPLE + "[" + releaseTrain + " RELEASE]",
            meta + "   Version: " + accent + pluginVersion,
            meta + "   By: " + accent + "Volmit Software (Arcane Arts)" + meta + " | Web Editor: " + accent + "https://gloss.volmitsoftware.com" + meta + " | Startup: " + statusColor + status,
            meta + "   Server: " + accent + serverVersion + meta + " | MC Support: " + accent + SUPPORTED_MC_VERSION,
            meta + "   Java: " + accent + SplashScreenSupport.javaMajorVersion() + meta + " | Date: " + accent + startupDate
        };

        StringBuilder splash = new StringBuilder("\n");
        for (int row = 0; row < ART.length; row++) {
            splash.append(colorize(ART[row], fill, edge)).append(column[row]).append('\n');
        }

        Gloss.log(Level.INFO, splash.toString());
    }

    private static String colorize(String row, ChatColor fill, ChatColor edge) {
        StringBuilder out = new StringBuilder(row.length() * 2);
        boolean inFill = false;
        boolean inEdge = false;
        for (int i = 0; i < row.length(); i++) {
            char glyph = row.charAt(i);
            if (glyph == '█') {
                if (!inFill) {
                    out.append(fill);
                    inFill = true;
                    inEdge = false;
                }
            } else if (glyph != ' ' && !inEdge) {
                out.append(edge);
                inEdge = true;
                inFill = false;
            }
            out.append(glyph);
        }
        return out.toString();
    }
}
